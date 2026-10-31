#include "cheat.h"

namespace he {

// Cursor travel over 80-200ms: cubic ease-out toward an overshoot target, then
// a settle back. The whole vector is rotated by a Gaussian angle, so the path
// never lands on the exact geometric line and the endpoint carries the
// intended deviation. Points are absolute, so partial rounding cannot drift.
void aim_path(int dx, int dy, const AimProfile& p, std::mt19937& rng, std::vector<POINT>& out) {
    const int dur = rnd(rng, p.minMs, p.maxMs);
    const int steps = std::max(4, dur / std::max(1, p.stepMs));

    const double rad = gauss(rng) * p.sigmaDeg * 3.14159265358979323846 / 180.0;
    const double cs = std::cos(rad), sn = std::sin(rad);
    const double tx = dx * cs - dy * sn;
    const double ty = dx * sn + dy * cs;
    const double k = 1.0 + p.overshoot + std::fabs(gauss(rng)) * 0.02;

    out.clear();
    out.reserve(size_t(steps) + 1);
    for (int i = 1; i <= steps; ++i) {
        const double t = double(i) / double(steps);
        double f;
        if (t < 0.7) {
            const double u = t / 0.7;
            f = (1.0 - std::pow(1.0 - u, 3.0)) * k;
        } else {
            f = k - (k - 1.0) * ((t - 0.7) / 0.3);
        }
        out.push_back(POINT{ int(std::lround(tx * f)), int(std::lround(ty * f)) });
    }
    out.push_back(POINT{ int(std::lround(tx)), int(std::lround(ty)) });
}

void aim_move(int dx, int dy, const AimProfile& p, std::mt19937& rng) {
    std::vector<POINT> path;
    aim_path(dx, dy, p, rng, path);

    POINT last{ 0, 0 };
    for (size_t i = 0; i < path.size(); ++i) {
        const POINT& pt = path[i];
        INPUT in{};
        in.type = INPUT_MOUSE;
        in.mi.dx = pt.x - last.x;
        in.mi.dy = pt.y - last.y;
        in.mi.dwFlags = MOUSEEVENTF_MOVE;
        SendInput(1, &in, sizeof(in));
        last = pt;
        if (i + 1 < path.size()) ::Sleep(std::max(1, p.stepMs + rnd(rng, -1, 2)));
    }
}

// Solves the ghost-ball aim position accounting for Cut-Induced Throw (CIT).
// Ball-to-ball friction pushes the object ball slightly off the line of centres.
// To counteract this, the aim vector is rotated into the cut angle.
POINT solve_ghost_with_cit(const POINT& cue, const POINT& target, const POINT& pocket) {
    const double bx = pocket.x - target.x;
    const double by = pocket.y - target.y;
    const double nb = std::hypot(bx, by);
    if (nb <= 1.0) return target;

    // Direct shot vector from target ball to pocket mouth
    const double sx = bx / nb;
    const double sy = by / nb;

    // Vector from cue ball to target ball
    const double cx = target.x - cue.x;
    const double cy = target.y - cue.y;
    const double nc = std::hypot(cx, cy);

    double aimX = sx;
    double aimY = sy;

    if (nc > 1.0) {
        const double inX = cx / nc;
        const double inY = cy / nc;
        // 2D cross product gives sine of cut angle (positive = cut to left, negative = cut to right)
        const double sinCut = inX * sy - inY * sx;
        const double cosCut = inX * sx + inY * sy;

        // Cut-Induced Throw peaks around 30 degrees (sin(2*theta) model).
        // Max deflection approx 1.8 degrees (~0.031 radians) for standard clean balls.
        constexpr double kMaxThrowRad = 1.8 * 3.14159265358979323846 / 180.0;
        if (cosCut > 0.0) {
            const double throwRad = kMaxThrowRad * (2.0 * sinCut * cosCut);
            // Rotate aim vector by -throwRad to counteract the throw
            const double cThrow = std::cos(-throwRad);
            const double sThrow = std::sin(-throwRad);
            aimX = sx * cThrow - sy * sThrow;
            aimY = sx * sThrow + sy * cThrow;
        }
    }

    return POINT{
        int(std::lround(target.x - aimX * 2.0 * kBallRadiusPx)),
        int(std::lround(target.y - aimY * 2.0 * kBallRadiusPx))
    };
}

} // namespace he
