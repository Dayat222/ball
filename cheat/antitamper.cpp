#include "cheat.h"

namespace he {

static bool clock_stretched() {
    LARGE_INTEGER f{}, a{}, b{};
    QueryPerformanceFrequency(&f);
    QueryPerformanceCounter(&a);
    ::Sleep(1);
    QueryPerformanceCounter(&b);
    const double ms = double(b.QuadPart - a.QuadPart) * 1000.0 / double(f.QuadPart);
    return ms < 0.3 || ms > 20.0;   // frozen or dilated clock == sandbox or hook
}

// Snapshotting the thread list every poll would itself look like scanning, so
// the ETW verdict is cached for two seconds.
static bool etw_cached() {
    static ULONGLONG stamp = 0;
    static bool verdict = false;
    const ULONGLONG now = GetTickCount64();
    if (stamp == 0 || now - stamp > 2000) {
        verdict = etw_worker_present();
        stamp = now;
    }
    return verdict;
}

// Normal path sleeps. When the environment looks instrumented, a hooked Sleep
// or a stopped clock would stall us, so yield the CPU instead of trusting the
// wait.
void anti_tamper_sleep(int ms) {
    bool suspicious = IsDebuggerPresent() != FALSE;
    if (!suspicious) {
        BOOL remote = FALSE;
        CheckRemoteDebuggerPresent(GetCurrentProcess(), &remote);
        suspicious = remote != FALSE;
    }
    if (!suspicious) suspicious = etw_cached();
    if (!suspicious) suspicious = clock_stretched();

    if (!suspicious) {
        ::Sleep(ms);
        return;
    }
    const ULONGLONG until = GetTickCount64() + ULONGLONG(ms);
    while (GetTickCount64() < until) SwitchToThread();
}

} // namespace he
