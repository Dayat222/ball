#include "cheat.h"

namespace he {

static uint64_t mix64(uint64_t x) {
    x ^= x >> 33;
    x *= 0xff51afd7ed558ccdull;
    x ^= x >> 33;
    x *= 0xc4ceb9fe1a85ec53ull;
    x ^= x >> 33;
    return x;
}

static uint64_t fresh_key() {
    std::random_device rd;
    LARGE_INTEGER q{};
    QueryPerformanceCounter(&q);
    uint64_t v = (uint64_t(rd()) << 32) ^ uint64_t(rd());
    v ^= uint64_t(q.QuadPart) ^ (uint64_t(GetCurrentProcessId()) << 17) ^ GetTickCount64();
    return mix64(v);
}

KeyRing::KeyRing(int rotateSec)
    : key_(fresh_key()), rotateSec_(rotateSec), last_(std::chrono::steady_clock::now()) {}

bool KeyRing::due() const {
    const auto age = std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::steady_clock::now() - last_).count();
    return age >= rotateSec_;
}

uint64_t KeyRing::rotate() {
    key_ = fresh_key();
    last_ = std::chrono::steady_clock::now();
    return key_;
}

} // namespace he
