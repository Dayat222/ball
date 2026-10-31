#include "cheat.h"

namespace he {

double power_for(double distPx, double distForFullPx, const PowerBounds& b, double variance, std::mt19937& rng) {
    if (distForFullPx <= 0.0) return b.min;
    double p = distPx / distForFullPx;
    p *= 1.0 + std::clamp(gauss(rng) * variance, -3.0 * variance, 3.0 * variance);
    return std::clamp(p, b.min, b.max);   // never outside the range the game itself allows
}

// Drag-back time interpolated from the bar's usable span, plus a fraction of
// measured ping so release lands near the server tick, plus jitter.
int power_hold_ms(double power01, const PowerBounds& b, double pingMs, std::mt19937& rng) {
    const double span = std::max(1e-6, b.max - b.min);
    const double t = std::clamp((power01 - b.min) / span, 0.0, 1.0);
    const int travel = int(320.0 + 480.0 * t);   // FILL: time your power bar end to end
    const int ping = int(std::clamp(pingMs * 0.25, 0.0, 40.0));
    return travel + ping + rnd(rng, 0, 22);
}

void shoot(double power01, const PowerBounds& b, double pingMs, std::mt19937& rng) {
    INPUT down{}, up{};
    down.type = INPUT_MOUSE;
    down.mi.dwFlags = MOUSEEVENTF_LEFTDOWN;
    up.type = INPUT_MOUSE;
    up.mi.dwFlags = MOUSEEVENTF_LEFTUP;
    SendInput(1, &down, sizeof(down));
    ::Sleep(power_hold_ms(power01, b, pingMs, rng));
    SendInput(1, &up, sizeof(up));
}

} // namespace he
