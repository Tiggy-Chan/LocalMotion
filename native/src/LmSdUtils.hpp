// LocalMotion SD utilities - simplified version without MNN
#ifndef LM_SDUTILS_HPP
#define LM_SDUTILS_HPP

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iostream>
#include <memory>
#include <random>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

struct GenerationResult {
  std::vector<uint8_t> image_data;
  int width;
  int height;
  int channels;
  int generation_time_ms;
  int first_step_time_ms;
};

inline std::string base64_encode(const unsigned char* data, size_t length) {
  static const char kBase64Chars[] =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
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

inline std::string base64_encode_string(const std::string& in) {
  return base64_encode(reinterpret_cast<const unsigned char*>(in.data()),
                       in.size());
}

inline unsigned hashSeed(unsigned long long seed) {
  seed = ((seed >> 16) ^ seed) * 0x45d9f3b;
  seed = ((seed >> 16) ^ seed) * 0x45d9f3b;
  seed = (seed >> 16) ^ seed;
  return static_cast<unsigned>(seed);
}

inline std::string LoadBytesFromFile(const std::string& path) {
  std::ifstream fs(path, std::ios::in | std::ios::binary);
  if (fs.fail()) {
    std::cerr << "Cannot open " << path << std::endl;
    throw std::runtime_error("Failed to open file: " + path);
  }
  std::string data;
  fs.seekg(0, std::ios::end);
  size_t size = static_cast<size_t>(fs.tellg());
  fs.seekg(0, std::ios::beg);
  data.resize(size);
  fs.read(data.data(), size);
  return data;
}

// Convert VAE output tensor (1,3,H,W CHW) to JPEG base64
inline std::string tensor_to_jpeg_base64(const std::vector<float>& pixels,
                                         int width, int height) {
  // pixels is (1, 3, height, width) CHW format, values in [-1, 1]
  // Convert to HWC uint8 RGB
  std::vector<uint8_t> rgb(width * height * 3);
  for (int y = 0; y < height; y++) {
    for (int x = 0; x < width; x++) {
      for (int c = 0; c < 3; c++) {
        // CHW -> HWC: pixel[c * H * W + y * W + x]
        float val = pixels[c * height * width + y * width + x];
        // Normalize from [-1, 1] to [0, 255]
        val = ((val + 1.0f) / 2.0f) * 255.0f;
        val = std::max(0.0f, std::min(255.0f, val));
        rgb[(y * width + x) * 3 + c] = static_cast<uint8_t>(val);
      }
    }
  }

  // Encode as JPEG using callback
  struct JpegContext {
    std::vector<unsigned char> data;
  };
  JpegContext ctx;
  if (!stbi_write_jpg_to_func(
          [](void *context, void *data, int size) {
            auto *c = static_cast<JpegContext *>(context);
            c->data.insert(c->data.end(),
                           static_cast<unsigned char *>(data),
                           static_cast<unsigned char *>(data) + size);
          },
          &ctx, width, height, 3, rgb.data(), 95)) {
    return "";
  }

  // Base64 encode
  static const char kBase64Chars[] =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  const unsigned char *d = ctx.data.data();
  size_t len = ctx.data.size();
  std::string result;
  result.reserve(((len + 2) / 3) * 4);
  for (size_t i = 0; i < len;) {
    uint32_t octet_a = i < len ? d[i++] : 0;
    uint32_t octet_b = i < len ? d[i++] : 0;
    uint32_t octet_c = i < len ? d[i++] : 0;
    uint32_t triple = (octet_a << 16) | (octet_b << 8) | octet_c;
    result += kBase64Chars[(triple >> 18) & 0x3F];
    result += kBase64Chars[(triple >> 12) & 0x3F];
    result += kBase64Chars[(triple >> 6) & 0x3F];
    result += kBase64Chars[(triple >> 0) & 0x3F];
  }
  size_t pad = (3 - len % 3) % 3;
  for (size_t i = 0; i < pad; i++) {
    result[result.size() - 1 - i] = '=';
  }
  return result;
}

#endif  // LM_SDUTILS_HPP
