// Headless checks for the parts that can be verified without the game:
// offset-chain decode, aim-path endpoint accuracy, power clamping, queue skip.
#include "cheat.h"
#include <cassert>
#include <cstdio>

using namespace he;

static void test_offset_roundtrip() {
    const uint64_t key = 0xDEADBEEFCAFEF00Dull;
    const uint32_t plain[4] = { 0x01B4C2E8, 0x18, 0x30, 0x0 };
    Chain c{};
    c.len = 4;
    for (int i = 0; i < 4; ++i) c.enc[i] = enc_word(plain[i], key);
    for (int i = 0; i < 4; ++i) assert(dec_word(c.enc[i], key) == plain[i]);

    // rekey must preserve the plaintext hops
    const uint64_t key2 = 0x0123456789ABCDEFull;
    for (int i = 0; i < 4; ++i) c.enc[i] = enc_word(dec_word(c.enc[i], key), key2);
    for (int i = 0; i < 4; ++i) assert(dec_word(c.enc[i], key2) == plain[i]);
}

static void test_aim_endpoint() {
    std::mt19937 rng(1234);
    AimProfile p;
    for (int n = 0; n < 500; ++n) {
        const int dx = rnd(rng, -900, 900);
        const int dy = rnd(rng, -900, 900);
        std::vector<POINT> path;
        aim_path(dx, dy, p, rng, path);
        assert(path.size() >= 5);
        const POINT& end = path.back();
        // endpoint is rotated by up to ~2.4deg, so allow the arc-length drift
        const double len = std::hypot(double(dx), double(dy));
        const double err = std::hypot(double(end.x - dx), double(end.y - dy));
        assert(err <= len * 0.05 + 1.0);
    }
}

static void test_power_bounds() {
    std::mt19937 rng(99);
    const PowerBounds b{ 0.02, 1.0 };
    for (int n = 0; n < 2000; ++n) {
        const double d = double(rnd(rng, 0, 2000));
        const double pw = power_for(d, 620.0, b, 0.03, rng);
        assert(pw >= b.min && pw <= b.max);
    }
    assert(power_for(0.0, 0.0, b, 0.03, rng) == b.min);
    assert(power_for(5000.0, 620.0, b, 0.03, rng) == b.max);
}

static void test_ghost_cit() {
    const POINT cue{ 100, 100 };
    const POINT target{ 200, 100 };
    const POINT pocketStraight{ 400, 100 };
    // Straight shot (0 cut angle) -> no CIT deflection
    const POINT gStraight = solve_ghost_with_cit(cue, target, pocketStraight);
    assert(gStraight.y == 100);
    assert(gStraight.x < target.x);

    // 45 degree cut shot to corner
    const POINT pocketCut{ 300, 200 };
    const POINT gCut = solve_ghost_with_cit(cue, target, pocketCut);
    const double dist = std::hypot(double(gCut.x - target.x), double(gCut.y - target.y));
    assert(std::fabs(dist - 2.0 * kBallRadiusPx) <= 1.0);
}

static void test_queue_skip() {
    ReadQueue q;
    int fired = 0;
    q.every([&] { ++fired; }, 0, 0);
    for (int i = 0; i < 10; ++i) q.tick();
    assert(fired == 8);   // passes 5 and 10 read nothing
}

int main() {
    test_offset_roundtrip();
    test_aim_endpoint();
    test_power_bounds();
    test_ghost_cit();
    test_queue_skip();
    std::puts("selftest ok");
    return 0;
}
