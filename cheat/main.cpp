#include "cheat.h"
#include <cstdio>

using namespace he;

// Pocket mouth centres in game-client pixels. FILL: read off your own client.
static constexpr POINT kPockets[6] = {
    { 40,  40 }, { 800,  20 }, { 1560, 40 },
    { 40, 700 }, { 800, 720 }, { 1560, 700 },
};

static constexpr PowerBounds kPower{ 0.02, 1.0 };
static constexpr double kFullPowerDistPx = 620.0;   // FILL: cue->object distance that needs a full pull

// Pointer chains: plaintext hops from a dumper, longest chain <= kMaxChain.
// Encrypting here is only so the literals are not static targets in the binary;
// a released build should ship the words already encrypted.
static std::vector<Chain> make_chains(uint64_t key) {
    auto mk = [&](std::initializer_list<uint32_t> plain) {
        Chain c{};
        c.len = uint8_t(plain.size());
        size_t i = 0;
        for (uint32_t w : plain) c.enc[i++] = enc_word(w, key);
        return c;
    };
    return {
        mk({ 0x01B4C2E8, 0x18, 0x30, 0x0 }),   // slot 0: cue ball position, 2x float (x,y)
        mk({ 0x01B4C2E8, 0x18, 0x38, 0x0 }),   // slot 1: target ball position, 2x float (x,y)
    };
}

static void read_state(HANDLE proc, const OffsetResolver& offs, POINT& cue, POINT& target) {
    float v[2]{};
    const uintptr_t p0 = offs.slot(kSlotCue);
    if (p0 && rpm(proc, p0, v, sizeof(v))) { cue.x = int(v[0]); cue.y = int(v[1]); }
    const uintptr_t p1 = offs.slot(kSlotTarget);
    if (p1 && rpm(proc, p1, v, sizeof(v))) { target.x = int(v[0]); target.y = int(v[1]); }
}

// Best pocket = the one whose object->pocket line is most collinear with
// cue->object, with a small distance penalty so a tie goes short.
static int best_pocket(const POINT& cue, const POINT& obj) {
    int best = -1;
    double bestCost = 1e9;
    for (int i = 0; i < 6; ++i) {
        const double ax = obj.x - cue.x, ay = obj.y - cue.y;
        const double bx = kPockets[i].x - obj.x, by = kPockets[i].y - obj.y;
        const double na = std::hypot(ax, ay), nb = std::hypot(bx, by);
        if (na < 1.0 || nb < 1.0) continue;
        const double cost = -(ax * bx + ay * by) / (na * nb) + nb / 4000.0;
        if (cost < bestCost) { bestCost = cost; best = i; }
    }
    return best;
}

int wmain() {
    const uint32_t pid = find_pid(kProcessName);
    if (!pid) {
        std::fwprintf(stderr, L"process not found: %s\n", kProcessName);
        return 1;
    }
    HANDLE proc = OpenProcess(PROCESS_VM_READ | PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (!proc) {
        std::fwprintf(stderr, L"OpenProcess failed: %lu\n", GetLastError());
        return 1;
    }

    uintptr_t base = module_base(pid, kGameModule);
    if (!base) base = module_base(pid, kProcessName);
    if (!base) {
        std::fwprintf(stderr, L"module not found\n");
        return 1;
    }

    HWND game = find_game_window(pid);
    if (!game) {
        std::fwprintf(stderr, L"game window not found\n");
        return 1;
    }

    KeyRing ring(kKeyRotateSec);
    OffsetResolver offs(proc, base, make_chains(ring.current()), ring.current());
    offs.resolve_all();

    Crosshair guide;
    guide.init(game);
    blind_etw();

    ReadQueue queue;
    queue.every([&] { offs.resolve_all(); }, 50, 150);

    std::mt19937 rng{ std::random_device{}() };
    AimProfile aim;
    POINT cue{}, target{};
    bool wasDown = false;

    // F4 = aim + shoot, END = exit.
    while (!(GetAsyncKeyState(VK_END) & 0x8000)) {
        offs.tick();
        if (offs.stale()) offs.resolve_all();
        if (ring.due()) offs.rotate(ring.rotate());
        queue.tick();

        read_state(proc, offs, cue, target);
        const bool assist = (GetAsyncKeyState(VK_F4) & 0x8000) != 0;
        guide.tick(cue, target, assist);

        if (assist && !wasDown) {
            const int pocket = best_pocket(cue, target);
            if (pocket >= 0) {
                const POINT ghost = solve_ghost_with_cit(cue, target, kPockets[pocket]);
                POINT ghostScreen = ghost;
                ClientToScreen(game, &ghostScreen);
                POINT cur{};
                GetCursorPos(&cur);
                aim_move(ghostScreen.x - cur.x, ghostScreen.y - cur.y, aim, rng);

                const double dist = std::hypot(double(ghost.x - cue.x), double(ghost.y - cue.y));
                const double power = power_for(dist, kFullPowerDistPx, kPower, 0.03, rng);
                ::Sleep(rnd(rng, 60, 160));       // hesitation before the shot
                shoot(power, kPower, 0.0, rng);   // FILL: pass measured ping for release sync
            }
        }
        wasDown = assist;

        anti_tamper_sleep(kPollMs);
    }

    guide.shutdown();
    unblind_etw();
    CloseHandle(proc);
    return 0;
}
