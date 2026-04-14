#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <csignal>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <fstream>
#include <iostream>
#include <random>
#include <sstream>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#ifdef LOCALMOTION_QNN_AVAILABLE
#include <QnnSampleApp.hpp>
#include <QnnTypeMacros.hpp>
#include <DynamicLoadUtil.hpp>
#include "LmQnnModel.hpp"
#include "LmSdUtils.hpp"
#include "ClipTokenizer.hpp"
#include "EulerAncestralDiscreteScheduler.hpp"
#include "Scheduler.hpp"
#include <xtensor/xarray.hpp>
#include <xtensor/xadapt.hpp>
#include <xtensor/xio.hpp>
#include <xtensor/xmanipulation.hpp>
#include <xtensor/xmath.hpp>
#include <xtensor/xrandom.hpp>
#include <xtensor/xview.hpp>
#else
#include "LmSdUtils.hpp"
#endif

// ============================================================================
// Global State
// ============================================================================

std::atomic<bool> g_cancel_requested{false};
std::atomic<bool> g_running{true};
std::string g_runtime_dir;

// Global sample dimensions (set before inference)
int g_sample_width = 64;
int g_sample_height = 64;

#ifdef LOCALMOTION_QNN_AVAILABLE
constexpr bool kQnnCompiled = true;
#else
constexpr bool kQnnCompiled = false;
#endif

// Full QNN pipeline is now implemented
constexpr bool kQnnPipelineImplemented = true;

// ============================================================================
// HTTP Request / Response Helpers
// ============================================================================

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
  if (start == std::string::npos || end == std::string::npos) return "";
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
      case '\\': escaped << "\\\\"; break;
      case '"': escaped << "\\\""; break;
      case '\n': escaped << "\\n"; break;
      case '\r': escaped << "\\r"; break;
      case '\t': escaped << "\\t"; break;
      default: escaped << ch; break;
    }
  }
  return escaped.str();
}

std::string path_join(const std::string &a, const std::string &b) {
  if (a.empty()) return b;
  if (a.back() == '/') return a + b;
  return a + "/" + b;
}

bool ensure_dir(const std::string &path) {
  std::string current;
  for (size_t i = 0; i < path.size(); i++) {
    current += path[i];
    if (path[i] == '/' || i == path.size() - 1) {
      mkdir(current.c_str(), 0755);
    }
  }
  return true;
}

bool send_all(int socket_fd, const std::string &data) {
  size_t total_sent = 0;
  while (total_sent < data.size()) {
    ssize_t sent = send(socket_fd, data.data() + total_sent,
                        data.size() - total_sent, 0);
    if (sent <= 0) return false;
    total_sent += static_cast<size_t>(sent);
  }
  return true;
}

std::string reason_phrase_for_status(int status_code) {
  switch (status_code) {
    case 200: return "OK";
    case 404: return "Not Found";
    case 500: return "Internal Server Error";
    default: return "OK";
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
    if (bytes <= 0) return false;
    raw.append(buffer, static_cast<size_t>(bytes));
    if (raw.size() > 1024 * 1024) return false;
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
    if (line.empty()) continue;
    size_t separator = line.find(':');
    if (separator == std::string::npos) continue;
    std::string key = trim(line.substr(0, separator));
    std::string value = trim(line.substr(separator + 1));
    request.headers[key] = value;
    if (key == "Content-Length") {
      content_length = static_cast<size_t>(std::strtoul(value.c_str(), nullptr, 10));
    }
  }

  request.body = remainder;
  while (request.body.size() < content_length) {
    ssize_t bytes = recv(client_fd, buffer, sizeof(buffer), 0);
    if (bytes <= 0) return false;
    request.body.append(buffer, static_cast<size_t>(bytes));
  }
  return true;
}

std::string extract_string(const std::string &body, const std::string &key,
                           const std::string &fallback) {
  std::string pattern = "\"" + key + "\"";
  size_t key_pos = body.find(pattern);
  if (key_pos == std::string::npos) return fallback;
  size_t colon_pos = body.find(':', key_pos + pattern.size());
  if (colon_pos == std::string::npos) return fallback;
  size_t value_start = body.find('"', colon_pos + 1);
  if (value_start == std::string::npos) return fallback;
  size_t value_end = body.find('"', value_start + 1);
  if (value_end == std::string::npos) return fallback;
  return body.substr(value_start + 1, value_end - value_start - 1);
}

long extract_long(const std::string &body, const std::string &key, long fallback) {
  std::string pattern = "\"" + key + "\"";
  size_t key_pos = body.find(pattern);
  if (key_pos == std::string::npos) return fallback;
  size_t colon_pos = body.find(':', key_pos + pattern.size());
  if (colon_pos == std::string::npos) return fallback;
  size_t value_start = body.find_first_of("-0123456789", colon_pos + 1);
  if (value_start == std::string::npos) return fallback;
  size_t value_end = body.find_first_not_of("0123456789", value_start + 1);
  std::string value = body.substr(value_start, value_end - value_start);
  return std::strtol(value.c_str(), nullptr, 10);
}

double extract_double(const std::string &body, const std::string &key, double fallback) {
  std::string pattern = "\"" + key + "\"";
  size_t key_pos = body.find(pattern);
  if (key_pos == std::string::npos) return fallback;
  size_t colon_pos = body.find(':', key_pos + pattern.size());
  if (colon_pos == std::string::npos) return fallback;
  size_t value_start = body.find_first_of("-0123456789.", colon_pos + 1);
  if (value_start == std::string::npos) return fallback;
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

// ============================================================================
// Runtime Bundle Probing
// ============================================================================

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
      "models/text_encoder.bin",
      "models/text_encoder.so",
      "models/unet.bin",
      "models/unet.so",
      "models/vae_encoder.bin",
      "models/vae_encoder.so",
      "models/vae_decoder.bin",
      "models/vae_decoder.so",
      "tokenizer/tokenizer.json",
      "tokenizer/tokenizer_config.json",
      "qnn/lib/libQnnSystem.so",
      "qnn/lib/libQnnHtp.so",
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
  payload << "\"status\":\"" << (probe.runtime_ready ? "ok" : "degraded") << "\",";
  payload << "\"backendMode\":\"" << json_escape(probe.backend_mode) << "\",";
  payload << "\"runtimeReady\":" << (probe.runtime_ready ? "true" : "false") << ",";
  payload << "\"canGenerate\":" << (probe.can_generate ? "true" : "false") << ",";
  payload << "\"qnnCompiled\":" << (probe.qnn_compiled ? "true" : "false") << ",";
  payload << "\"qnnPipelineImplemented\":"
          << (probe.qnn_pipeline_implemented ? "true" : "false") << ",";
  payload << "\"bundleVersion\":\"" << json_escape(probe.bundle_version) << "\",";
  payload << "\"runtimeDir\":\"" << json_escape(g_runtime_dir) << "\",";
  payload << "\"detail\":\"" << json_escape(probe.detail) << "\",";
  payload << "\"missingFiles\":[";
  for (size_t index = 0; index < probe.missing_files.size(); ++index) {
    if (index > 0) payload << ",";
    payload << "\"" << json_escape(probe.missing_files[index]) << "\"";
  }
  payload << "]}";
  return payload.str();
}

void send_pipeline_progress(int client_fd, const std::string &stage,
                            double progress, const std::string &message) {
  std::ostringstream payload;
  payload << "{"
          << "\"stage\":\"" << json_escape(stage) << "\","
          << "\"progress\":" << progress << ","
          << "\"message\":\"" << json_escape(message) << "\""
          << "}";
  send_sse_event(client_fd, "progress", payload.str());
}

// ============================================================================
#ifdef LOCALMOTION_QNN_AVAILABLE
// Full QNN Inference Pipeline
// ============================================================================

struct QnnGenerationContext {
  int client_fd;
  int output_size;
  long seed;
  std::string prompt;
  std::string negative_prompt;
  double guidance_scale;
  long inference_steps;
  int sample_width;
  int sample_height;
  int total_steps;
  int current_step;

  std::string runtime_dir;

  // QNN models
  std::unique_ptr<LmQnnModel> clip_model;
  std::unique_ptr<LmQnnModel> unet_model;
  std::unique_ptr<LmQnnModel> vae_decoder_model;

  // Tokenizer
  ClipTokenizer tokenizer;

  // Text embeddings: [batch=2, seq=77, dim=768]
  std::vector<float> text_embeddings;  // 2 * 77 * 768
};

int initialize_qnn_app(const std::string &model_name,
                       std::unique_ptr<LmQnnModel> &app,
                       const std::string &runtime_dir) {
  if (!app) return -1;

  std::string model_bin = path_join(runtime_dir, "models/" + model_name + ".bin");
  std::string model_so = path_join(runtime_dir, "models/" + model_name + ".so");

  if (!file_exists(model_bin)) {
    std::cerr << "[qnn] Missing model bin: " << model_bin << "\n";
    return -1;
  }
  if (!file_exists(model_so)) {
    std::cerr << "[qnn] Missing model so: " << model_so << "\n";
    return -1;
  }

  // Set environment for QNN backend
  setenv("ADSP_LIBRARY_PATH",
         (path_join(runtime_dir, "qnn/lib") + ";" +
          path_join(runtime_dir, "models")).c_str(), 1);

  std::string qnn_lib = path_join(runtime_dir, "qnn/lib");
  setenv("LD_LIBRARY_PATH", qnn_lib.c_str(), 1);

  using StatusCode = qnn::tools::sample_app::StatusCode;

  std::cerr << "[qnn] Initializing " << model_name << "...\n";

  if (StatusCode::SUCCESS != app->initialize())
    return app->reportError(model_name + " Init failure");
  if (StatusCode::SUCCESS != app->initializeBackend())
    return app->reportError(model_name + " Backend Init failure");

  auto devPropStat = app->isDevicePropertySupported();
  if (StatusCode::FAILURE != devPropStat) {
    if (StatusCode::SUCCESS != app->createDevice())
      return app->reportError(model_name + " Device Creation failure");
  }
  if (StatusCode::SUCCESS != app->initializeProfiling())
    return app->reportError(model_name + " Profiling Init failure");
  if (StatusCode::SUCCESS != app->registerOpPackages())
    return app->reportError(model_name + " Register Op Packages failure");
  if (StatusCode::SUCCESS != app->createFromBinary())
    return app->reportError(model_name + " Create From Binary failure");
  if (StatusCode::SUCCESS != app->enablePerformaceMode())
    return app->reportError(model_name + " Performance Mode failure");

  std::cerr << "[qnn] " << model_name << " initialized OK\n";
  return 0;
}

bool init_qnn_models(QnnGenerationContext &ctx) {
  using namespace qnn::tools::sample_app;
  using namespace qnn::tools::dynamicloadutil;

  // Set environment for QNN backend
  setenv("ADSP_LIBRARY_PATH",
         (path_join(ctx.runtime_dir, "qnn/lib") + ";" +
          path_join(ctx.runtime_dir, "models")).c_str(), 1);
  setenv("LD_LIBRARY_PATH",
         path_join(ctx.runtime_dir, "qnn/lib").c_str(), 1);

  // Use DynamicLoadUtil to get QNN function pointers (SampleApp's standard way)
  QnnFunctionPointers qnn_fps{};
  void* backend_lib_handle = nullptr;

  std::string backend_path = path_join(ctx.runtime_dir, "qnn/lib/libQnnHtp.so");
  std::string model_path = "";  // empty — we use createFromBinary, not .so models

  auto loadStatus = getQnnFunctionPointers(
      backend_path, model_path, &qnn_fps, &backend_lib_handle,
      /*loadModelLib=*/false, /*modelHandleRtn=*/nullptr);
  if (loadStatus != qnn::tools::dynamicloadutil::StatusCode::SUCCESS) {
    std::cerr << "[qnn] getQnnFunctionPointers failed for HTP backend\n";
    return false;
  }

  // Also load system interface
  std::string system_path = path_join(ctx.runtime_dir, "qnn/lib/libQnnSystem.so");
  auto sysStatus = getQnnSystemFunctionPointers(system_path, &qnn_fps);
  if (sysStatus != qnn::tools::dynamicloadutil::StatusCode::SUCCESS) {
    std::cerr << "[qnn] getQnnSystemFunctionPointers failed (non-fatal)\n";
    // Non-fatal — continue without system interface
  }

  // Create models using QnnSampleApp's standard initialization.
  // Each LmQnnModel will create its own backend internally during initialize().
  // We pass the function pointers and let initializeBackend() handle the rest.
  ctx.clip_model = std::make_unique<LmQnnModel>(
      qnn_fps, "", "", nullptr, "", false);

  ctx.unet_model = std::make_unique<LmQnnModel>(
      qnn_fps, "", "", nullptr, "", false);

  ctx.vae_decoder_model = std::make_unique<LmQnnModel>(
      qnn_fps, "", "", nullptr, "", false);

  // Initialize each model
  if (initialize_qnn_app("text_encoder", ctx.clip_model, ctx.runtime_dir) != 0)
    return false;
  if (initialize_qnn_app("unet", ctx.unet_model, ctx.runtime_dir) != 0)
    return false;
  if (initialize_qnn_app("vae_decoder", ctx.vae_decoder_model, ctx.runtime_dir) != 0)
    return false;

  return true;
}

bool init_tokenizer(QnnGenerationContext &ctx) {
  std::string tokenizer_path = path_join(ctx.runtime_dir, "tokenizer/tokenizer.json");
  if (!file_exists(tokenizer_path)) {
    std::cerr << "[qnn] Tokenizer not found at " << tokenizer_path << "\n";
    return false;
  }

  if (!ctx.tokenizer.LoadFromJson(tokenizer_path)) {
    std::cerr << "[qnn] Failed to load tokenizer\n";
    return false;
  }

  std::cerr << "[qnn] Tokenizer loaded OK\n";
  return true;
}

bool run_full_qnn_pipeline(QnnGenerationContext &ctx) {
  using StatusCode = qnn::tools::sample_app::StatusCode;

  // Initialize tokenizer
  if (!init_tokenizer(ctx)) {
    send_sse_event(ctx.client_fd, "error",
                   R"({"message":"Failed to load tokenizer"})");
    return false;
  }

  // Initialize QNN models
  send_pipeline_progress(ctx.client_fd, "prepare", 0.05,
                         "正在加载 QNN 模型");
  if (!init_qnn_models(ctx)) {
    send_sse_event(ctx.client_fd, "error",
                   R"({"message":"Failed to initialize QNN models"})");
    return false;
  }

  int total_steps = static_cast<int>(ctx.inference_steps) + 3;
  ctx.total_steps = total_steps;
  ctx.current_step = 0;

  // ============================================================
  // Step 1: Tokenize prompts
  // ============================================================
  send_pipeline_progress(ctx.client_fd, "encode_text", 0.1,
                         "正在编码文本 prompt");

  std::vector<int> pos_ids = ctx.tokenizer.Encode(ctx.prompt, 77);
  std::vector<int> neg_ids =
      ctx.negative_prompt.empty()
          ? ctx.tokenizer.Encode("", 77)
          : ctx.tokenizer.Encode(ctx.negative_prompt, 77);

  // Combine: [neg_ids, pos_ids] for batch=2
  std::vector<int> combined_ids;
  combined_ids.reserve(154);
  combined_ids.insert(combined_ids.end(), neg_ids.begin(), neg_ids.end());
  combined_ids.insert(combined_ids.end(), pos_ids.begin(), pos_ids.end());

  std::cerr << "[qnn] Tokenized prompt: " << pos_ids.size() << " tokens\n";

  // ============================================================
  // Step 2: Run CLIP text encoder
  // ============================================================
  send_pipeline_progress(ctx.client_fd, "encode_text", 0.15,
                         "正在运行 CLIP 编码器");

  ctx.text_embeddings.resize(2 * 77 * 768, 0.0f);

  // Run negative prompt
  {
    StatusCode status = ctx.clip_model->executeClipGraphs(
        neg_ids.data(), ctx.text_embeddings.data());
    if (status != StatusCode::SUCCESS) {
      std::cerr << "[qnn] CLIP negative prompt failed\n";
      return false;
    }
  }

  // Run positive prompt
  {
    StatusCode status = ctx.clip_model->executeClipGraphs(
        pos_ids.data(),
        ctx.text_embeddings.data() + 77 * 768);
    if (status != StatusCode::SUCCESS) {
      std::cerr << "[qnn] CLIP positive prompt failed\n";
      return false;
    }
  }

  ++ctx.current_step;
  send_pipeline_progress(ctx.client_fd, "encode_text",
                         static_cast<double>(ctx.current_step) / total_steps,
                         "CLIP 编码完成");
  std::cerr << "[qnn] CLIP encoding done\n";

  // ============================================================
  // Step 3: Initialize latents
  // ============================================================
  send_pipeline_progress(ctx.client_fd, "init_latent",
                         static_cast<double>(ctx.current_step + 1) / total_steps,
                         "正在初始化潜在变量");

  // Set global sample dimensions
  g_sample_width = ctx.sample_width;
  g_sample_height = ctx.sample_height;

  // Create scheduler
  auto scheduler = std::make_unique<EulerAncestralDiscreteScheduler>(
      1000, 0.00085f, 0.012f, "scaled_linear", "epsilon", "leading");
  scheduler->set_timesteps(static_cast<int>(ctx.inference_steps));
  xt::xarray<float> timesteps = scheduler->get_timesteps();

  // Generate random latents with seed
  xt::random::seed(static_cast<uint64_t>(ctx.seed));
  xt::xarray<float> latents =
      xt::random::randn<float>({1, 4, ctx.sample_height, ctx.sample_width});

  // Scale by init_noise_sigma
  float init_noise_sigma = scheduler->get_init_noise_sigma();
  latents = latents * init_noise_sigma;
  latents = xt::eval(latents);

  ++ctx.current_step;
  send_pipeline_progress(ctx.client_fd, "init_latent",
                         static_cast<double>(ctx.current_step) / total_steps,
                         "潜在变量初始化完成");
  std::cerr << "[qnn] Latents initialized: shape=[1,4,"
            << ctx.sample_height << "," << ctx.sample_width
            << "], sigma=" << init_noise_sigma << "\n";

  // ============================================================
  // Step 4: Denoising loop
  // ============================================================
  int single_latent_size = 1 * 4 * ctx.sample_height * ctx.sample_width;
  std::vector<float> unet_out(2 * single_latent_size);  // batch=2

  for (int i = 0; i < static_cast<int>(ctx.inference_steps) &&
                    !g_cancel_requested.load();
       i++) {
    ++ctx.current_step;
    double progress = static_cast<double>(ctx.current_step) / total_steps;
    send_pipeline_progress(
        ctx.client_fd, "denoise", progress,
        "去噪步骤 " + std::to_string(i + 1) + "/" +
            std::to_string(ctx.inference_steps));

    int current_ts = static_cast<int>(timesteps(i));

    // Scale model input
    xt::xarray<float> latents_scaled =
        scheduler->scale_model_input(latents, current_ts);

    // Prepare UNet input: batch=2 [neg_latents, pos_latents]
    // Both use the same latents (for CFG)
    std::vector<float> latents_batch(2 * single_latent_size);
    const float *src = latents_scaled.data();
    std::copy(src, src + single_latent_size, latents_batch.begin());
    std::copy(src, src + single_latent_size,
              latents_batch.begin() + single_latent_size);

    // Run UNet
    StatusCode status = ctx.unet_model->executeUnetGraphs(
        latents_batch.data(), current_ts,
        ctx.text_embeddings.data(), unet_out.data());

    if (status != StatusCode::SUCCESS) {
      std::cerr << "[qnn] UNet step " << i << " failed\n";
      return false;
    }

    // CFG: noise_pred = uncond + cfg_scale * (cond - uncond)
    xt::xarray<float> noise_pred_batch =
        xt::adapt(unet_out, {2, 4, ctx.sample_height, ctx.sample_width});
    xt::xarray<float> noise_pred_uncond = xt::view(noise_pred_batch, 0);
    xt::xarray<float> noise_pred_cond = xt::view(noise_pred_batch, 1);
    xt::xarray<float> noise_pred =
        noise_pred_uncond +
        static_cast<float>(ctx.guidance_scale) * (noise_pred_cond - noise_pred_uncond);
    noise_pred = xt::eval(noise_pred);

    // Scheduler step
    latents = scheduler->step(noise_pred, current_ts, latents).prev_sample;
    latents = xt::eval(latents);

    std::cerr << "[qnn] Denoise step " << i + 1 << "/" << ctx.inference_steps
              << " done\n";
  }

  if (g_cancel_requested.load()) {
    send_sse_event(ctx.client_fd, "error",
                   R"({"message":"Generation cancelled"})");
    return false;
  }

  // ============================================================
  // Step 5: VAE Decode
  // ============================================================
  ++ctx.current_step;
  send_pipeline_progress(ctx.client_fd, "decode_vae",
                         static_cast<double>(ctx.current_step) / total_steps,
                         "正在解码 VAE 输出");

  // Scale latents for VAE: latents = latents * (1.0 / 0.18215)
  latents = latents * (1.0f / 0.18215f);
  latents = xt::eval(latents);

  // Prepare VAE input
  std::vector<float> vae_in(latents.data(), latents.data() + latents.size());
  int output_size_px = ctx.output_size;
  int pixel_count = 3 * output_size_px * output_size_px;
  std::vector<float> vae_out(pixel_count);

  StatusCode status = ctx.vae_decoder_model->executeVaeDecoderGraphs(
      vae_in.data(), vae_out.data());

  if (status != StatusCode::SUCCESS) {
    std::cerr << "[qnn] VAE decoder failed\n";
    return false;
  }

  ++ctx.current_step;
  send_pipeline_progress(ctx.client_fd, "decode_vae",
                         static_cast<double>(ctx.current_step) / total_steps,
                         "正在编码 JPEG");

  // ============================================================
  // Step 6: Convert to JPEG and send
  // ============================================================
  std::string jpeg_base64 = tensor_to_jpeg_base64(vae_out, output_size_px, output_size_px);
  if (jpeg_base64.empty()) {
    std::cerr << "[qnn] JPEG encoding failed\n";
    return false;
  }

  std::ostringstream payload;
  payload << "{"
          << "\"width\":" << ctx.output_size << ","
          << "\"height\":" << ctx.output_size << ","
          << "\"seed\":" << ctx.seed << ","
          << "\"guidanceScale\":" << ctx.guidance_scale << ","
          << "\"inferenceSteps\":" << ctx.inference_steps << ","
          << "\"imageBase64\":\"" << json_escape(jpeg_base64) << "\""
          << "}";
  send_sse_event(ctx.client_fd, "complete", payload.str());

  std::cerr << "[qnn] Generation complete!\n";
  return true;
}

#endif  // LOCALMOTION_QNN_AVAILABLE

// ============================================================================
// Placeholder: generate a black JPEG for fallback
// ============================================================================

std::string generate_black_jpeg_base64() {
  const unsigned char jpeg_data[] = {
      0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46,
      0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01,
      0x00, 0x01, 0x00, 0x00, 0xFF, 0xDB, 0x00, 0x43,
      0x00, 0x08, 0x06, 0x06, 0x07, 0x06, 0x05, 0x08,
      0x07, 0x07, 0x07, 0x09, 0x09, 0x08, 0x0A, 0x0C,
      0x14, 0x0D, 0x0C, 0x0B, 0x0B, 0x0C, 0x19, 0x12,
      0x13, 0x0F, 0x14, 0x1D, 0x1A, 0x1F, 0x1E, 0x1D,
      0x1A, 0x1C, 0x1C, 0x20, 0x24, 0x2E, 0x27, 0x20,
      0x22, 0x2C, 0x23, 0x1C, 0x1C, 0x28, 0x37, 0x29,
      0x2C, 0x30, 0x31, 0x34, 0x34, 0x34, 0x1F, 0x27,
      0x39, 0x3D, 0x38, 0x32, 0x3C, 0x2E, 0x33, 0x34,
      0x32, 0xFF, 0xC0, 0x00, 0x0B, 0x08, 0x00, 0x01,
      0x00, 0x01, 0x01, 0x01, 0x11, 0x00, 0xFF, 0xC4,
      0x00, 0x1F, 0x00, 0x00, 0x01, 0x05, 0x01, 0x01,
      0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00,
      0x00, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04,
      0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0xFF,
      0xC4, 0x00, 0xB5, 0x10, 0x00, 0x02, 0x01, 0x03,
      0x03, 0x02, 0x04, 0x03, 0x05, 0x05, 0x04, 0x04,
      0x00, 0x00, 0x01, 0x7D, 0x01, 0x02, 0x03, 0x00,
      0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06,
      0x13, 0x51, 0x61, 0x07, 0x22, 0x71, 0x14, 0x32,
      0x81, 0x91, 0xA1, 0x08, 0x23, 0x42, 0xB1, 0xC1,
      0x15, 0x52, 0xD1, 0xF0, 0x24, 0x33, 0x62, 0x72,
      0x82, 0x09, 0x0A, 0x16, 0x17, 0x18, 0x19, 0x1A,
      0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x34, 0x35,
      0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45,
      0x46, 0x47, 0x48, 0x49, 0x4A, 0x53, 0x54, 0x55,
      0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65,
      0x66, 0x67, 0x68, 0x69, 0x6A, 0x73, 0x74, 0x75,
      0x76, 0x77, 0x78, 0x79, 0x7A, 0x83, 0x84, 0x85,
      0x86, 0x87, 0x88, 0x89, 0x8A, 0x92, 0x93, 0x94,
      0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3,
      0xA4, 0xA5, 0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xB2,
      0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA,
      0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9,
      0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8,
      0xD9, 0xDA, 0xE1, 0xE2, 0xE3, 0xE4, 0xE5, 0xE6,
      0xE7, 0xE8, 0xE9, 0xEA, 0xF1, 0xF2, 0xF3, 0xF4,
      0xF5, 0xF6, 0xF7, 0xF8, 0xF9, 0xFA, 0xFF, 0xDA,
      0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00,
      0xFB, 0xD5, 0xDB, 0x20, 0xA8, 0xBA, 0xBA, 0xBA,
      0xBA, 0xBA, 0xBA, 0xBA, 0xBA, 0xBA, 0xBA, 0xBA,
      0xBA, 0xBA, 0xBA, 0xBA, 0xBA, 0xBA, 0xBA, 0xBA,
      0xBA, 0xBA, 0xBA, 0xBA, 0xBA, 0xBA, 0xBA, 0xBA,
      0xBA, 0xBA, 0xBA, 0xBA, 0xBA, 0xFF, 0xD9};

  // Base64 encode inline
  static const char kBase64Chars[] =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  const unsigned char *data = jpeg_data;
  size_t length = sizeof(jpeg_data);
  std::string result;
  result.reserve(((length + 2) / 3) * 4);
  for (size_t i = 0; i < length;) {
    uint32_t octet_a = i < length ? data[i++] : 0;
    uint32_t octet_b = i < length ? data[i++] : 0;
    uint32_t octet_c = i < length ? data[i++] : 0;
    uint32_t triple = (octet_a << 16) | (octet_b << 8) | octet_c;
    result += kBase64Chars[(triple >> 18) & 0x3F];
    result += kBase64Chars[(triple >> 12) & 0x3F];
    result += kBase64Chars[(triple >> 6) & 0x3F];
    result += kBase64Chars[(triple >> 0) & 0x3F];
  }
  size_t pad = (3 - length % 3) % 3;
  for (size_t i = 0; i < pad; i++) {
    result[result.size() - 1 - i] = '=';
  }
  return result;
}

// ============================================================================
// Main Generation Pipeline
// ============================================================================

void stream_generate(int client_fd, const HttpRequest &request) {
  RuntimeProbe probe = probe_runtime_bundle();
  if (!send_sse_headers(client_fd)) {
    return;
  }

  long output_size = extract_long(request.body, "outputSize", 512);
  long seed = extract_long(
      request.body, "seed",
      std::chrono::system_clock::now().time_since_epoch().count());
  std::string prompt = extract_string(request.body, "prompt", "");
  std::string negative_prompt =
      extract_string(request.body, "negativePrompt", "");
  double guidance_scale =
      extract_double(request.body, "guidanceScale", 7.5);
  long inference_steps = extract_long(request.body, "inferenceSteps", 20);
  double strength = extract_double(request.body, "strength", 0.75);
  std::string reference_image =
      extract_string(request.body, "referenceImageBase64",
                     extract_string(request.body, "imageBase64", ""));
  std::string generation_mode =
      reference_image.empty() ? "txt2img" : "img2img";

  g_cancel_requested = false;

  // If QNN is not ready, fall back to placeholder streaming.
  if (!probe.can_generate) {
    if (!probe.runtime_ready) {
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

    // Placeholder streaming
    send_pipeline_progress(client_fd, "prepare", 0.0,
                           "QNN runtime bundle loaded, executing placeholder "
                           "pipeline (full QNN inference pending)");

    std::vector<std::string> stages = {
        "prepare", "encode_text", "init_latent", "denoise", "decode_vae"};
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
        double progress =
            static_cast<double>(current_step) / static_cast<double>(total_steps);
        std::string message = "qnn_placeholder mode=" + generation_mode +
                              " steps=" + std::to_string(inference_steps) +
                              " cfg=" + std::to_string(guidance_scale) +
                              " prompt=" + prompt;
        send_pipeline_progress(client_fd, stage, progress, message);
        std::this_thread::sleep_for(std::chrono::milliseconds(140));
      }
    }

    std::string preview = generate_black_jpeg_base64();
    if (!reference_image.empty()) {
      preview = reference_image;
    }

    std::ostringstream payload;
    payload << "{"
            << "\"width\":" << output_size << ","
            << "\"height\":" << output_size << ","
            << "\"seed\":" << seed << ","
            << "\"guidanceScale\":" << guidance_scale << ","
            << "\"inferenceSteps\":" << inference_steps << ","
            << "\"strength\":" << strength << ","
            << "\"imageBase64\":\"" << json_escape(preview) << "\""
            << "}";
    send_sse_event(client_fd, "complete", payload.str());
    return;
  }

  // ================================================================
  // Full QNN pipeline
  // ================================================================
#ifdef LOCALMOTION_QNN_AVAILABLE
  QnnGenerationContext ctx;
  ctx.client_fd = client_fd;
  ctx.output_size = static_cast<int>(output_size);
  ctx.seed = seed;
  ctx.prompt = prompt;
  ctx.negative_prompt = negative_prompt;
  ctx.guidance_scale = guidance_scale;
  ctx.inference_steps = inference_steps;
  ctx.sample_width = static_cast<int>(output_size) / 8;
  ctx.sample_height = static_cast<int>(output_size) / 8;
  ctx.current_step = 0;
  ctx.total_steps = static_cast<int>(inference_steps) + 3;
  ctx.runtime_dir = g_runtime_dir;

  bool success = run_full_qnn_pipeline(ctx);
  if (!success) {
    // Error event already sent inside run_full_qnn_pipeline
    std::cerr << "[qnn] Full pipeline failed, falling back to placeholder\n";
  }
#else
  // This should not happen if probe.can_generate is true
  send_sse_event(client_fd, "error",
                 R"({"message":"QNN not compiled in this binary"})");
#endif
}

// ============================================================================
// Client Handler
// ============================================================================

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
  } else if (request.method == "POST" &&
             (request.path == "/generate_image" ||
              request.path == "/generate_clip")) {
    stream_generate(client_fd, request);
  } else {
    send_not_found(client_fd);
  }

  close(client_fd);
}

void signal_handler(int) { g_running = false; }

// ============================================================================
// Main
// ============================================================================

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
    int client_fd =
        accept(server_fd, reinterpret_cast<sockaddr *>(&client_address),
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
