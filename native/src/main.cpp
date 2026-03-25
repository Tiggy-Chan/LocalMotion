#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <chrono>
#include <csignal>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace {

std::atomic<bool> g_cancel_requested{false};
std::atomic<bool> g_running{true};
std::string g_runtime_dir;

#ifdef LOCALMOTION_QNN_AVAILABLE
constexpr bool kQnnCompiled = true;
#else
constexpr bool kQnnCompiled = false;
#endif

constexpr bool kQnnPipelineImplemented = false;

struct HttpRequest {
  std::string method;
  std::string path;
  std::unordered_map<std::string, std::string> headers;
  std::string body;
};

struct RuntimeProbe {
  bool manifest_present = false;
  bool runtime_ready = false;
  bool can_generate = false;
  bool qnn_compiled = kQnnCompiled;
  bool qnn_pipeline_implemented = kQnnPipelineImplemented;
  std::string backend_mode = "placeholder";
  std::string bundle_version;
  std::vector<std::string> missing_files;
  std::string detail = "runtime_bundle_missing";
};

std::string trim(const std::string &value) {
  size_t start = value.find_first_not_of(" \r\n\t");
  size_t end = value.find_last_not_of(" \r\n\t");
  if (start == std::string::npos || end == std::string::npos) {
    return "";
  }
  return value.substr(start, end - start + 1);
}

bool file_exists(const std::string &path) {
  std::ifstream input(path, std::ios::binary);
  return input.good();
}

std::string read_text_file(const std::string &path) {
  std::ifstream input(path, std::ios::binary);
  std::ostringstream buffer;
  buffer << input.rdbuf();
  return buffer.str();
}

std::string json_escape(const std::string &value) {
  std::ostringstream escaped;
  for (char ch : value) {
    switch (ch) {
      case '\\':
        escaped << "\\\\";
        break;
      case '"':
        escaped << "\\\"";
        break;
      case '\n':
        escaped << "\\n";
        break;
      case '\r':
        escaped << "\\r";
        break;
      case '\t':
        escaped << "\\t";
        break;
      default:
        escaped << ch;
        break;
    }
  }
  return escaped.str();
}

bool send_all(int socket_fd, const std::string &data) {
  size_t total_sent = 0;
  while (total_sent < data.size()) {
    ssize_t sent = send(socket_fd, data.data() + total_sent,
                        data.size() - total_sent, 0);
    if (sent <= 0) {
      return false;
    }
    total_sent += static_cast<size_t>(sent);
  }
  return true;
}

std::string reason_phrase_for_status(int status_code) {
  switch (status_code) {
    case 200:
      return "OK";
    case 404:
      return "Not Found";
    case 500:
      return "Internal Server Error";
    default:
      return "OK";
  }
}

void send_json(int client_fd, int status_code, const std::string &payload) {
  std::ostringstream response;
  response << "HTTP/1.1 " << status_code << " "
           << reason_phrase_for_status(status_code) << "\r\n";
  response << "Content-Type: application/json\r\n";
  response << "Content-Length: " << payload.size() << "\r\n";
  response << "Connection: close\r\n\r\n";
  response << payload;
  send_all(client_fd, response.str());
}

void send_not_found(int client_fd) {
  send_json(client_fd, 404, R"({"error":"not_found"})");
}

bool read_http_request(int client_fd, HttpRequest &request) {
  std::string raw;
  char buffer[4096];
  while (raw.find("\r\n\r\n") == std::string::npos) {
    ssize_t bytes = recv(client_fd, buffer, sizeof(buffer), 0);
    if (bytes <= 0) {
      return false;
    }
    raw.append(buffer, static_cast<size_t>(bytes));
    if (raw.size() > 1024 * 1024) {
      return false;
    }
  }

  size_t header_end = raw.find("\r\n\r\n");
  std::string header_blob = raw.substr(0, header_end);
  std::string remainder = raw.substr(header_end + 4);

  std::istringstream stream(header_blob);
  std::string request_line;
  std::getline(stream, request_line);
  request_line = trim(request_line);

  std::istringstream request_line_stream(request_line);
  request_line_stream >> request.method >> request.path;

  std::string line;
  size_t content_length = 0;
  while (std::getline(stream, line)) {
    line = trim(line);
    if (line.empty()) {
      continue;
    }
    size_t separator = line.find(':');
    if (separator == std::string::npos) {
      continue;
    }
    std::string key = trim(line.substr(0, separator));
    std::string value = trim(line.substr(separator + 1));
    request.headers[key] = value;
    if (key == "Content-Length") {
      content_length =
          static_cast<size_t>(std::strtoul(value.c_str(), nullptr, 10));
    }
  }

  request.body = remainder;
  while (request.body.size() < content_length) {
    ssize_t bytes = recv(client_fd, buffer, sizeof(buffer), 0);
    if (bytes <= 0) {
      return false;
    }
    request.body.append(buffer, static_cast<size_t>(bytes));
  }
  return true;
}

std::string extract_string(const std::string &body, const std::string &key,
                           const std::string &fallback) {
  std::string pattern = "\"" + key + "\"";
  size_t key_pos = body.find(pattern);
  if (key_pos == std::string::npos) {
    return fallback;
  }
  size_t colon_pos = body.find(':', key_pos + pattern.size());
  if (colon_pos == std::string::npos) {
    return fallback;
  }
  size_t value_start = body.find('"', colon_pos + 1);
  if (value_start == std::string::npos) {
    return fallback;
  }
  size_t value_end = body.find('"', value_start + 1);
  if (value_end == std::string::npos) {
    return fallback;
  }
  return body.substr(value_start + 1, value_end - value_start - 1);
}

long extract_long(const std::string &body, const std::string &key,
                  long fallback) {
  std::string pattern = "\"" + key + "\"";
  size_t key_pos = body.find(pattern);
  if (key_pos == std::string::npos) {
    return fallback;
  }
  size_t colon_pos = body.find(':', key_pos + pattern.size());
  if (colon_pos == std::string::npos) {
    return fallback;
  }
  size_t value_start = body.find_first_of("-0123456789", colon_pos + 1);
  if (value_start == std::string::npos) {
    return fallback;
  }
  size_t value_end = body.find_first_not_of("0123456789", value_start + 1);
  std::string value = body.substr(value_start, value_end - value_start);
  return std::strtol(value.c_str(), nullptr, 10);
}

double extract_double(const std::string &body, const std::string &key,
                      double fallback) {
  std::string pattern = "\"" + key + "\"";
  size_t key_pos = body.find(pattern);
  if (key_pos == std::string::npos) {
    return fallback;
  }
  size_t colon_pos = body.find(':', key_pos + pattern.size());
  if (colon_pos == std::string::npos) {
    return fallback;
  }
  size_t value_start = body.find_first_of("-0123456789.", colon_pos + 1);
  if (value_start == std::string::npos) {
    return fallback;
  }
  size_t value_end = body.find_first_not_of("0123456789.", value_start + 1);
  std::string value = body.substr(value_start, value_end - value_start);
  return std::strtod(value.c_str(), nullptr);
}

bool send_sse_event(int client_fd, const std::string &event_name,
                    const std::string &payload) {
  std::ostringstream stream;
  stream << "event: " << event_name << "\n";
  stream << "data: " << payload << "\n\n";
  return send_all(client_fd, stream.str());
}

bool send_sse_headers(int client_fd) {
  std::ostringstream response_headers;
  response_headers << "HTTP/1.1 200 OK\r\n";
  response_headers << "Content-Type: text/event-stream\r\n";
  response_headers << "Cache-Control: no-cache\r\n";
  response_headers << "Connection: close\r\n";
  response_headers << "Access-Control-Allow-Origin: *\r\n\r\n";
  return send_all(client_fd, response_headers.str());
}

RuntimeProbe probe_runtime_bundle() {
  RuntimeProbe probe;

  if (g_runtime_dir.empty()) {
    probe.detail = "runtime_dir_not_set";
    return probe;
  }

  const std::string manifest_path = g_runtime_dir + "/runtime-manifest.json";
  if (!file_exists(manifest_path)) {
    probe.missing_files.push_back("runtime-manifest.json");
    probe.detail = "runtime_manifest_missing";
    return probe;
  }

  probe.manifest_present = true;
  const std::string manifest = read_text_file(manifest_path);
  probe.bundle_version = extract_string(manifest, "version", "");

  const std::vector<std::string> required_files = {
      "models/sd15_img2img.bin",
      "models/depth_anything_v2_small.bin",
      "models/rife46_lite.bin",
  };
  for (const std::string &relative_path : required_files) {
    const std::string full_path = g_runtime_dir + "/" + relative_path;
    if (!file_exists(full_path)) {
      probe.missing_files.push_back(relative_path);
    }
  }

  if (!probe.missing_files.empty()) {
    probe.detail = "runtime_files_missing";
    return probe;
  }

  probe.runtime_ready = true;
  if (probe.qnn_compiled && probe.qnn_pipeline_implemented) {
    probe.backend_mode = "qnn";
    probe.can_generate = true;
    probe.detail = "ready";
  } else if (probe.qnn_compiled) {
    probe.detail = "qnn_pipeline_not_implemented";
  } else {
    probe.detail = "binary_built_without_qnn_sdk";
  }

  return probe;
}

std::string build_health_payload(const RuntimeProbe &probe) {
  std::ostringstream payload;
  payload << "{";
  payload << "\"status\":\"" << (probe.runtime_ready ? "ok" : "degraded")
          << "\",";
  payload << "\"backendMode\":\"" << json_escape(probe.backend_mode) << "\",";
  payload << "\"runtimeReady\":" << (probe.runtime_ready ? "true" : "false")
          << ",";
  payload << "\"canGenerate\":" << (probe.can_generate ? "true" : "false")
          << ",";
  payload << "\"qnnCompiled\":" << (probe.qnn_compiled ? "true" : "false")
          << ",";
  payload << "\"qnnPipelineImplemented\":"
          << (probe.qnn_pipeline_implemented ? "true" : "false") << ",";
  payload << "\"bundleVersion\":\"" << json_escape(probe.bundle_version)
          << "\",";
  payload << "\"runtimeDir\":\"" << json_escape(g_runtime_dir) << "\",";
  payload << "\"detail\":\"" << json_escape(probe.detail) << "\",";
  payload << "\"missingFiles\":[";
  for (size_t index = 0; index < probe.missing_files.size(); ++index) {
    if (index > 0) {
      payload << ",";
    }
    payload << "\"" << json_escape(probe.missing_files[index]) << "\"";
  }
  payload << "]";
  payload << "}";
  return payload.str();
}

void stream_generate(int client_fd, const HttpRequest &request) {
  RuntimeProbe probe = probe_runtime_bundle();
  if (!send_sse_headers(client_fd)) {
    return;
  }

  if (!probe.can_generate) {
    std::ostringstream error_payload;
    error_payload << "{"
                  << "\"message\":\""
                  << json_escape("Native QNN backend is not ready: " +
                                 probe.detail)
                  << "\""
                  << "}";
    send_sse_event(client_fd, "error", error_payload.str());
    return;
  }

  long duration_sec = extract_long(request.body, "durationSec", 4);
  long fps = extract_long(request.body, "fps", 12);
  long output_size = extract_long(request.body, "outputSize", 512);
  long seed = extract_long(
      request.body, "seed",
      std::chrono::system_clock::now().time_since_epoch().count());
  double style_strength = extract_double(request.body, "styleStrength", 0.25);
  std::string prompt = extract_string(request.body, "prompt", "");

  g_cancel_requested = false;
  std::vector<std::string> stages = {"preprocess", "stylize", "depth",
                                     "render", "interpolate"};
  int total_steps = static_cast<int>(stages.size()) * 4;
  int current_step = 0;
  for (const std::string &stage : stages) {
    for (int part = 0; part < 4; ++part) {
      if (g_cancel_requested.load()) {
        send_sse_event(client_fd, "error",
                       R"({"message":"Generation cancelled"})");
        return;
      }
      ++current_step;
      std::ostringstream payload;
      payload << "{"
              << "\"stage\":\"" << json_escape(stage) << "\","
              << "\"progress\":"
              << static_cast<double>(current_step) /
                     static_cast<double>(total_steps)
              << ","
              << "\"message\":\""
              << json_escape("qnn_placeholder prompt=" + prompt)
              << "\""
              << "}";
      if (!send_sse_event(client_fd, "progress", payload.str())) {
        return;
      }
      std::this_thread::sleep_for(std::chrono::milliseconds(140));
    }
  }

  std::ostringstream payload;
  payload << "{"
          << "\"width\":" << output_size << ","
          << "\"height\":" << output_size << ","
          << "\"fps\":" << fps << ","
          << "\"durationMs\":" << (duration_sec * 1000) << ","
          << "\"seed\":" << seed << ","
          << "\"styleStrength\":" << style_strength
          << "}";
  send_sse_event(client_fd, "complete", payload.str());
}

void handle_client(int client_fd) {
  HttpRequest request;
  if (!read_http_request(client_fd, request)) {
    close(client_fd);
    return;
  }

  if (request.method == "GET" && request.path == "/health") {
    const RuntimeProbe probe = probe_runtime_bundle();
    send_json(client_fd, 200, build_health_payload(probe));
  } else if (request.method == "POST" && request.path == "/cancel") {
    g_cancel_requested = true;
    send_json(client_fd, 200, R"({"status":"cancelling"})");
  } else if (request.method == "POST" && request.path == "/generate_clip") {
    stream_generate(client_fd, request);
  } else {
    send_not_found(client_fd);
  }

  close(client_fd);
}

void signal_handler(int) { g_running = false; }

}  // namespace

int main(int argc, char *argv[]) {
  std::signal(SIGINT, signal_handler);
  std::signal(SIGTERM, signal_handler);
  std::signal(SIGPIPE, SIG_IGN);

  int port = 8081;
  for (int i = 1; i < argc; ++i) {
    std::string arg = argv[i];
    if (arg == "--port" && i + 1 < argc) {
      port = std::atoi(argv[++i]);
    } else if (arg == "--runtime-dir" && i + 1 < argc) {
      g_runtime_dir = argv[++i];
    }
  }

  int server_fd = socket(AF_INET, SOCK_STREAM, 0);
  if (server_fd < 0) {
    std::cerr << "Unable to create socket\n";
    return 1;
  }

  int opt = 1;
  setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

  sockaddr_in address{};
  address.sin_family = AF_INET;
  address.sin_addr.s_addr = inet_addr("127.0.0.1");
  address.sin_port = htons(static_cast<uint16_t>(port));

  if (bind(server_fd, reinterpret_cast<sockaddr *>(&address),
           sizeof(address)) < 0) {
    std::cerr << "Unable to bind on port " << port << "\n";
    close(server_fd);
    return 1;
  }

  if (listen(server_fd, 8) < 0) {
    std::cerr << "Unable to listen\n";
    close(server_fd);
    return 1;
  }

  std::cout << "LocalMotion backend listening on 127.0.0.1:" << port
            << " runtime_dir=" << g_runtime_dir << "\n";

  while (g_running.load()) {
    sockaddr_in client_address{};
    socklen_t client_length = sizeof(client_address);
    int client_fd = accept(server_fd, reinterpret_cast<sockaddr *>(&client_address),
                           &client_length);
    if (client_fd < 0) {
      if (errno == EINTR) {
        continue;
      }
      break;
    }
    std::thread(handle_client, client_fd).detach();
  }

  close(server_fd);
  return 0;
}
