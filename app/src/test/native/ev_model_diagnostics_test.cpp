#include "ev_model_diagnostics.h"
#include <cassert>
#include <iostream>

using namespace openautolink::jni::evdiag;
int main() {
    Sampler s;
    const auto first = s.begin(0);
    assert(first == 1);
    assert(s.begin(30000) == 0); // no accumulating pending captures
    assert(!s.complete(999, false));
    assert(s.complete(first, false));
    assert(!s.complete(first, true)); // duplicate/stale outcome
    assert(s.begin(29999) == 0); // failures cannot create a log storm
    assert(s.begin(30000) == 2);
    assert(s.complete(2, true));
    assert(s.begin(59999) == 0);
    assert(s.begin(60000) == 3);
    assert(s.complete(3, true));
    assert(s.begin(10) == 0); // backwards clock is conservative
    Sampler replacement;
    assert(replacement.token() != s.token());
    assert(replacement.begin(0) == 1); // independent new-session budget
    std::cout << "PASS sample failure/time/pending/session boundaries\n";
    for (size_t size : {size_t(0), size_t(1), size_t(128), size_t(129), size_t(4096)}) {
        std::string bytes;
        for (size_t i = 0; i < size; ++i) bytes.push_back(static_cast<char>(i));
        const auto lines = payloadLines(UINT64_MAX, UINT64_MAX, bytes);
        std::string reconstructed;
        for (const auto& line : lines) {
            assert(line.size() < 500);
            assert(line.find("schema=legacy-local") != std::string::npos);
            const auto hex = line.substr(line.find(" hex=") + 5);
            for (size_t i = 0; i < hex.size(); i += 2)
                reconstructed.push_back(static_cast<char>(std::stoul(hex.substr(i, 2), nullptr, 16)));
        }
        assert(reconstructed == bytes);
    }
    const auto oversized = payloadLines(1, 1, std::string(4097, 'x'));
    assert(oversized.size() == 1);
    assert(oversized[0].find("capture=omitted_oversize") != std::string::npos);
    assert(oversized[0].find("hex=") == std::string::npos);
    assert(oversized[0].size() < 500);
    std::cout << "PASS chunk roundtrip/line bound/oversize\n";
}
