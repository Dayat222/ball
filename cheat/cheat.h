#pragma once
#include "core.h"

namespace he {

// offset_resolver.cpp -------------------------------------------------------
class OffsetResolver {
public:
    OffsetResolver(HANDLE proc, uintptr_t moduleBase, std::vector<Chain> chains, uint64_t key);

    bool resolve(size_t slot, uintptr_t& out) const;
    void resolve_all();
    uintptr_t slot(size_t i) const;
    void rotate(uint64_t key);
    void tick();
    bool stale() const;

private:
    HANDLE proc_;
    uintptr_t base_;
    uint64_t key_;
    std::vector<Chain> chains_;
    std::vector<uintptr_t> cache_;
    uint32_t frames_ = 0;
};

// keyring.cpp ---------------------------------------------------------------
class KeyRing {
public:
    explicit KeyRing(int rotateSec);
    uint64_t current() const { return key_; }
    bool due() const;
    uint64_t rotate();

private:
    uint64_t key_;
    int rotateSec_;
    std::chrono::steady_clock::time_point last_;
};

// read_queue.cpp ------------------------------------------------------------
class ReadQueue {
public:
    using Job = std::function<void()>;
    void every(Job job, int loMs, int hiMs);   // tick() assumed ~kPollMs apart
    void tick();

private:
    struct Entry { Job job; int lo, hi, wait; };
    std::vector<Entry> jobs_;
    uint32_t pass_ = 0;
    std::mt19937 rng_{ std::random_device{}() };
};

// aim.cpp -------------------------------------------------------------------
struct AimProfile {
    int minMs = 80;
    int maxMs = 200;
    double sigmaDeg = 0.8;
    double overshoot = 0.06;
    int stepMs = 8;
};

void aim_path(int dx, int dy, const AimProfile& p, std::mt19937& rng, std::vector<POINT>& out);
void aim_move(int dx, int dy, const AimProfile& p, std::mt19937& rng);
POINT solve_ghost_with_cit(const POINT& cue, const POINT& target, const POINT& pocket);

// power.cpp -----------------------------------------------------------------
struct PowerBounds { double min = 0.02; double max = 1.0; };

double power_for(double distPx, double distForFullPx, const PowerBounds& b, double variance, std::mt19937& rng);
int    power_hold_ms(double power01, const PowerBounds& b, double pingMs, std::mt19937& rng);
void   shoot(double power01, const PowerBounds& b, double pingMs, std::mt19937& rng);

// crosshair.cpp -------------------------------------------------------------
class Crosshair {
public:
    bool init(HWND game);
    void tick(const POINT& cueBallClient, const POINT& aimClient, bool aiming);
    void shutdown();

private:
    HWND hwnd_ = nullptr;
    HWND game_ = nullptr;
    uint32_t frame_ = 0;
};

// antitamper.cpp ------------------------------------------------------------
void anti_tamper_sleep(int ms);

// etw_guard.cpp -------------------------------------------------------------
bool etw_worker_present();
bool blind_etw();
void unblind_etw();

} // namespace he
