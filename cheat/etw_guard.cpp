#include "cheat.h"

namespace he {

typedef LONG(NTAPI* pfn_qit)(HANDLE, ULONG, PVOID, ULONG, PULONG);
static constexpr ULONG kThreadQuerySetWin32StartAddress = 9;

static BYTE* g_target = nullptr;
static BYTE g_saved[3]{};
static bool g_blinded = false;

// A thread whose start address sits inside ntdll is a runtime worker (ETW, RPC,
// loader). A couple is normal; a swarm means a tracer or a sandbox shim.
bool etw_worker_present() {
    HMODULE ntdll = GetModuleHandleW(L"ntdll.dll");
    if (!ntdll) return false;
    auto qit = reinterpret_cast<pfn_qit>(GetProcAddress(ntdll, "NtQueryInformationThread"));
    if (!qit) return false;

    const uintptr_t nbase = reinterpret_cast<uintptr_t>(ntdll);
    constexpr uintptr_t kSpan = 0x200000;   // ntdll always fits in 2MB

    int hits = 0;
    const DWORD self = GetCurrentProcessId();
    THREADENTRY32 te{ sizeof(te) };
    HANDLE tsnap = CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0);
    if (tsnap == INVALID_HANDLE_VALUE) return false;
    if (Thread32First(tsnap, &te)) {
        do {
            if (te.th32OwnerProcessID != self) continue;
            HANDLE t = OpenThread(THREAD_QUERY_LIMITED_INFORMATION, FALSE, te.th32ThreadID);
            if (!t) continue;
            uintptr_t start = 0;
            qit(t, kThreadQuerySetWin32StartAddress, &start, sizeof(start), nullptr);
            CloseHandle(t);
            if (start >= nbase && start < nbase + kSpan) ++hits;
        } while (Thread32Next(tsnap, &te));
    }
    CloseHandle(tsnap);
    return hits > 2;
}

bool blind_etw() {
    if (g_blinded) return true;
    HMODULE ntdll = GetModuleHandleW(L"ntdll.dll");
    if (!ntdll) return false;
    auto fn = reinterpret_cast<BYTE*>(GetProcAddress(ntdll, "EtwEventWrite"));
    if (!fn) return false;

    DWORD old = 0;
    if (!VirtualProtect(fn, 3, PAGE_EXECUTE_READWRITE, &old)) return false;
    memcpy(g_saved, fn, 3);
    const BYTE stub[3] = { 0x33, 0xC0, 0xC3 };   // xor eax,eax ; ret  -> STATUS_SUCCESS
    memcpy(fn, stub, 3);
    VirtualProtect(fn, 3, old, &old);
    FlushInstructionCache(GetCurrentProcess(), fn, 3);
    g_target = fn;
    g_blinded = true;
    return true;
}

void unblind_etw() {
    if (!g_blinded || !g_target) return;
    DWORD old = 0;
    if (VirtualProtect(g_target, 3, PAGE_EXECUTE_READWRITE, &old)) {
        memcpy(g_target, g_saved, 3);
        VirtualProtect(g_target, 3, old, &old);
        FlushInstructionCache(GetCurrentProcess(), g_target, 3);
    }
    g_blinded = false;
}

} // namespace he
