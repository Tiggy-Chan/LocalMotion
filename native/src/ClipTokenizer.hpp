// Lightweight CLIP BPE tokenizer for SD1.5
// Based on the GPT-2 ByteLevelBPE algorithm
// This is a simplified implementation that parses tokenizer.json directly
#ifndef CLIP_TOKENIZER_HPP
#define CLIP_TOKENIZER_HPP

#include <algorithm>
#include <cstdint>
#include <fstream>
#include <map>
#include <string>
#include <unordered_map>
#include <vector>

// SD1.5 CLIP tokenizer special tokens
static constexpr int CLIP_BOS_TOKEN = 49406;
static constexpr int CLIP_EOS_TOKEN = 49407;
static constexpr int CLIP_PAD_TOKEN = 49407;

class ClipTokenizer {
 public:
  bool LoadFromJson(const std::string& path) {
    // Read entire file
    std::ifstream file(path, std::ios::binary);
    if (!file.is_open()) return false;
    std::string content((std::istreambuf_iterator<char>(file)),
                        std::istreambuf_iterator<char>());
    file.close();
    return LoadFromJsonString(content);
  }

  bool LoadFromJsonString(const std::string& json) {
    // Simple JSON parser for the tokenizer format
    // We need: model.vocab (string -> int), model.merges (list of "a b" strings)

    // Parse vocab: find "model" -> "vocab"
    size_t model_pos = json.find("\"model\"");
    if (model_pos == std::string::npos) return false;

    size_t vocab_pos = json.find("\"vocab\"", model_pos);
    if (vocab_pos == std::string::npos) return false;

    // Find the opening { of vocab
    size_t vocab_start = json.find('{', vocab_pos);
    if (vocab_start == std::string::npos) return false;

    // Parse vocab entries
    size_t pos = vocab_start + 1;
    int depth = 1;
    while (depth > 0 && pos < json.size()) {
      // Skip whitespace
      while (pos < json.size() && (json[pos] == ' ' || json[pos] == '\n' ||
                                   json[pos] == '\r' || json[pos] == '\t'))
        pos++;

      if (json[pos] == '}') {
        depth--;
        pos++;
        continue;
      }

      if (json[pos] == ',') {
        pos++;
        continue;
      }

      // Parse key (string)
      if (json[pos] != '"') {
        pos++;
        continue;
      }
      size_t key_start = pos + 1;
      size_t key_end = key_start;
      while (key_end < json.size() && json[key_end] != '"') {
        if (json[key_end] == '\\') key_end++;  // skip escaped char
        key_end++;
      }
      std::string key = unescape_json_string(
          json.substr(key_start, key_end - key_start));
      pos = key_end + 1;

      // Skip colon
      while (pos < json.size() && (json[pos] == ' ' || json[pos] == ':'))
        pos++;

      // Parse value (integer)
      size_t num_start = pos;
      while (pos < json.size() &&
             (json[pos] == '-' || (json[pos] >= '0' && json[pos] <= '9')))
        pos++;
      if (pos > num_start) {
        int token_id = std::stoi(json.substr(num_start, pos - num_start));
        vocab_[key] = token_id;
      }
    }

    // Parse merges: find "merges"
    size_t merges_pos = json.find("\"merges\"");
    if (merges_pos == std::string::npos) return false;

    size_t merges_start = json.find('[', merges_pos);
    if (merges_start == std::string::npos) return false;

    pos = merges_start + 1;
    while (pos < json.size()) {
      while (pos < json.size() && (json[pos] == ' ' || json[pos] == '\n' ||
                                   json[pos] == '\r' || json[pos] == '\t' ||
                                   json[pos] == ','))
        pos++;

      if (json[pos] == ']') break;
      if (json[pos] != '"') {
        pos++;
        continue;
      }

      size_t merge_start = pos + 1;
      size_t merge_end = merge_start;
      while (merge_end < json.size() && json[merge_end] != '"') {
        if (json[merge_end] == '\\') merge_end++;
        merge_end++;
      }
      std::string merge_line = json.substr(merge_start, merge_end - merge_start);
      pos = merge_end + 1;

      // Split on space
      size_t space_pos = merge_line.find(' ');
      if (space_pos != std::string::npos) {
        std::string first = merge_line.substr(0, space_pos);
        std::string second = merge_line.substr(space_pos + 1);
        merges_.push_back({first, second});
      }
    }

    // Build ranks map (merge index = rank)
    for (size_t i = 0; i < merges_.size(); i++) {
      merge_ranks_[merges_[i]] = static_cast<int>(i);
    }

    return true;
  }

  std::vector<int> Encode(const std::string& text, int max_length = 77) {
    std::vector<int> tokens;
    tokens.push_back(CLIP_BOS_TOKEN);

    // Byte-level encoding: convert text to byte-level string
    std::string byte_encoded = bytes_to_unicode(text);

    // Split into words (whitespace tokenization)
    std::vector<std::string> words = split_on_whitespace(byte_encoded);

    // BPE each word
    for (const auto& word : words) {
      std::vector<std::string> sub_tokens = bpe(word);
      for (const auto& tok : sub_tokens) {
        auto it = vocab_.find(tok);
        if (it != vocab_.end()) {
          tokens.push_back(it->second);
        } else {
          // Unknown token - try individual characters
          for (char c : tok) {
            std::string s(1, c);
            auto cit = vocab_.find(s);
            if (cit != vocab_.end()) {
              tokens.push_back(cit->second);
            }
          }
        }
        if (static_cast<int>(tokens.size()) >= max_length - 1) break;
      }
      if (static_cast<int>(tokens.size()) >= max_length - 1) break;
    }

    // Pad to max_length
    while (static_cast<int>(tokens.size()) < max_length) {
      tokens.push_back(CLIP_PAD_TOKEN);
    }

    // Truncate (keep BOS, truncate middle, keep EOS at end position)
    if (static_cast<int>(tokens.size()) > max_length) {
      tokens.resize(max_length);
      tokens[max_length - 1] = CLIP_EOS_TOKEN;
    }

    return tokens;
  }

  std::string Decode(const std::vector<int>& ids) {
    // Reverse vocab
    std::unordered_map<int, std::string> rev_vocab;
    for (const auto& p : vocab_) {
      rev_vocab[p.second] = p.first;
    }

    std::string result;
    for (int id : ids) {
      if (id == CLIP_BOS_TOKEN || id == CLIP_EOS_TOKEN ||
          id == CLIP_PAD_TOKEN)
        continue;
      auto it = rev_vocab.find(id);
      if (it != rev_vocab.end()) {
        result += it->second;
      }
    }

    // Reverse byte-to-unicode mapping
    return unicode_to_bytes(result);
  }

 private:
  // GPT-2 byte-to-unicode mapping
  static std::map<uint8_t, std::string> build_byte_to_unicode() {
    std::map<uint8_t, std::string> mapping;
    // Printable ASCII
    for (uint8_t i = 33; i <= 126; i++) {
      mapping[i] = std::string(1, static_cast<char>(i));
    }
    // Extended Unicode range for bytes 0-31, 127-255
    int n = 0;
    for (int b = 0; b < 256; b++) {
      if (mapping.find(static_cast<uint8_t>(b)) == mapping.end()) {
        // Map to Unicode private use area starting at 256
        uint32_t codepoint = 256 + n;
        // UTF-8 encoding
        if (codepoint < 0x80) {
          mapping[b] = std::string(1, static_cast<char>(codepoint));
        } else if (codepoint < 0x800) {
          char buf[3];
          buf[0] = 0xC0 | (codepoint >> 6);
          buf[1] = 0x80 | (codepoint & 0x3F);
          buf[2] = 0;
          mapping[b] = std::string(buf, 2);
        } else {
          char buf[4];
          buf[0] = 0xE0 | (codepoint >> 12);
          buf[1] = 0x80 | ((codepoint >> 6) & 0x3F);
          buf[2] = 0x80 | (codepoint & 0x3F);
          buf[3] = 0;
          mapping[b] = std::string(buf, 3);
        }
        n++;
      }
    }
    return mapping;
  }

  std::string bytes_to_unicode(const std::string& text) {
    static std::map<uint8_t, std::string> byte_to_unicode_map =
        build_byte_to_unicode();

    std::string result;
    for (uint8_t c : text) {
      auto it = byte_to_unicode_map.find(c);
      if (it != byte_to_unicode_map.end()) {
        result += it->second;
      } else {
        result += static_cast<char>(c);
      }
    }
    return result;
  }

  std::string unicode_to_bytes(const std::string& text) {
    static std::map<uint8_t, std::string> byte_to_unicode_map =
        build_byte_to_unicode();
    // Build reverse mapping
    static std::map<std::string, uint8_t> unicode_to_byte_map;
    if (unicode_to_byte_map.empty()) {
      for (const auto& p : byte_to_unicode_map) {
        unicode_to_byte_map[p.second] = p.first;
      }
    }

    std::string result;
    size_t i = 0;
    while (i < text.size()) {
      // Try multi-byte first (3 bytes, then 2, then 1)
      bool found = false;
      for (int len = 3; len >= 1; len--) {
        if (i + len <= static_cast<int>(text.size())) {
          std::string key = text.substr(i, len);
          auto it = unicode_to_byte_map.find(key);
          if (it != unicode_to_byte_map.end()) {
            result += static_cast<char>(it->second);
            i += len;
            found = true;
            break;
          }
        }
      }
      if (!found) {
        result += text[i];
        i++;
      }
    }
    return result;
  }

  std::vector<std::string> split_on_whitespace(const std::string& text) {
    std::vector<std::string> words;
    size_t start = 0;
    bool in_word = false;

    for (size_t i = 0; i <= text.size(); i++) {
      bool is_space = (i == text.size()) || (text[i] == ' ') ||
                      (text[i] == '\n') || (text[i] == '\r') ||
                      (text[i] == '\t');

      if (!is_space && !in_word) {
        start = i;
        in_word = true;
      } else if (is_space && in_word) {
        // Add </w> suffix to mark end of word
        words.push_back(text.substr(start, i - start) + "</w>");
        in_word = false;
      }
    }
    return words;
  }

  std::vector<std::string> bpe(const std::string& token) {
    if (token.empty()) return {};

    // Split into characters
    std::vector<std::string> word;
    for (size_t i = 0; i < token.size(); i++) {
      // Handle UTF-8 multi-byte
      unsigned char c = token[i];
      if ((c & 0x80) == 0) {
        // ASCII
        word.push_back(std::string(1, token[i]));
      } else {
        // Multi-byte UTF-8
        int bytes = 1;
        if ((c & 0xE0) == 0xC0)
          bytes = 2;
        else if ((c & 0xF0) == 0xE0)
          bytes = 3;
        else if ((c & 0xF8) == 0xF0)
          bytes = 4;
        word.push_back(token.substr(i, bytes));
        i += bytes - 1;
      }
    }

    while (true) {
      // Find the best merge
      int best_rank = -1;
      int best_idx = -1;

      for (size_t i = 0; i + 1 < word.size(); i++) {
        std::pair<std::string, std::string> pair = {word[i], word[i + 1]};
        auto it = merge_ranks_.find(pair);
        if (it != merge_ranks_.end() &&
            (best_rank == -1 || it->second < best_rank)) {
          best_rank = it->second;
          best_idx = static_cast<int>(i);
        }
      }

      if (best_rank == -1) break;

      // Merge
      word[best_idx] = word[best_idx] + word[best_idx + 1];
      word.erase(word.begin() + best_idx + 1);
    }

    return word;
  }

  std::string unescape_json_string(const std::string& s) {
    std::string result;
    for (size_t i = 0; i < s.size(); i++) {
      if (s[i] == '\\' && i + 1 < s.size()) {
        switch (s[i + 1]) {
          case 'n':
            result += '\n';
            break;
          case 'r':
            result += '\r';
            break;
          case 't':
            result += '\t';
            break;
          case '\\':
            result += '\\';
            break;
          case '"':
            result += '"';
            break;
          case '/':
            result += '/';
            break;
          case 'u': {
            // Unicode escape \uXXXX
            if (i + 5 < s.size()) {
              std::string hex = s.substr(i + 2, 4);
              uint32_t cp = std::stoul(hex, nullptr, 16);
              // Simple UTF-8 encoding
              if (cp < 0x80) {
                result += static_cast<char>(cp);
              } else if (cp < 0x800) {
                result += static_cast<char>(0xC0 | (cp >> 6));
                result += static_cast<char>(0x80 | (cp & 0x3F));
              } else {
                result += static_cast<char>(0xE0 | (cp >> 12));
                result += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
                result += static_cast<char>(0x80 | (cp & 0x3F));
              }
              i += 4;
            }
            break;
          }
          default:
            result += s[i + 1];
        }
        i++;
      } else {
        result += s[i];
      }
    }
    return result;
  }

  std::unordered_map<std::string, int> vocab_;
  std::vector<std::pair<std::string, std::string>> merges_;
  std::map<std::pair<std::string, std::string>, int> merge_ranks_;
};

#endif  // CLIP_TOKENIZER_HPP
