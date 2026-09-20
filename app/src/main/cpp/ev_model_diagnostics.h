#pragma once

#include <atomic>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

namespace openautolink::jni::evdiag {

// IO-thread confined. A failed or unresolved send must not create a log storm.
// New JniSession => new token/budget. Tokens are process-local, not JNI generations.
class Sampler {
public:
    Sampler() : token_(++nextToken_) {}
    Sampler(const Sampler&) = delete;
    Sampler& operator=(const Sampler&) = delete;
    uint64_t token() const { return token_; }
    uint64_t begin(int64_t nowMs) {
        if (pending_ || (sequence_ && (nowMs < lastMs_ || nowMs - lastMs_ < 30000)))
            return 0;
        lastMs_ = nowMs;
        pending_ = ++sequence_;
        return pending_;
    }
    bool complete(uint64_t id, bool /*success*/) {
        if (!id || id != pending_) return false;
        pending_ = 0;
        return true;
    }
private:
    inline static std::atomic<uint64_t> nextToken_{0};
    const uint64_t token_;
    uint64_t sequence_ = 0;
    uint64_t pending_ = 0;
    int64_t lastMs_ = 0;
};

inline std::string prefix(uint64_t token, uint64_t sample) {
    return "VEM session=" + std::to_string(token) + " sample=" +
        std::to_string(sample) + " schema=legacy-local";
}

// No silent truncation: at most 4 KiB / 32 chunks; oversize is explicitly omitted.
// 128 bytes of hex plus worst-case uint64 metadata stays well below 500 chars.
inline std::vector<std::string> payloadLines(
        uint64_t token, uint64_t sample, const std::string& bytes) {
    const auto base = prefix(token, sample) + " payload=VehicleEnergyModel bytes=" +
        std::to_string(bytes.size());
    if (bytes.size() > 4096) return {base + " capture=omitted_oversize"};
    const size_t chunks = bytes.empty() ? 1 : (bytes.size() + 127) / 128;
    std::vector<std::string> lines;
    static constexpr char hex[] = "0123456789abcdef";
    for (size_t chunk = 0; chunk < chunks; ++chunk) {
        std::string line = base + " chunk=" + std::to_string(chunk + 1) + "/" +
            std::to_string(chunks) + " hex=";
        for (size_t i = chunk * 128; i < bytes.size() && i < (chunk + 1) * 128; ++i) {
            const auto b = static_cast<unsigned char>(bytes[i]);
            line += hex[b >> 4];
            line += hex[b & 15];
        }
        lines.push_back(std::move(line));
    }
    return lines;
}

} // namespace openautolink::jni::evdiag
