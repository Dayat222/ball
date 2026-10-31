#pragma once
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0A00
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <tlhelp32.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <functional>
#include <initializer_list>
#include <random>
#include <string>
#include <utility>
#include <vector>

namespace he {

// ---- config --------------------------------------------------------------
constexpr wchar_t  kProcessName[]  = L"8BallPool.exe";    // FILL: tasklist | findstr /i 8ball
constexpr wchar_t  kGameModule[]   = L"GameAssembly.dll"; // FILL: module holding statics
constexpr int      kPollMs         = 8;
constexpr int      kRedriveFrames  = 60;
constexpr int      kKeyRotateSec   = 45;
constexpr uint32_t kMaxChain       = 6;
constexpr double   kBallRadiusPx   = 9.0;  // FILL: measure a ball at your client resolution
constexpr size_t   kSlotCue        = 0;
constexpr size_t   kSlotTarget     = 1;

struct Chain { uint8_t len = 0; uint32_t enc[kMaxChain]{}; };

inline uint32_t word_key(uint64_t k)             { return uint32_t(k) ^ uint32_t(k >> 32); }
inline uint32_t enc_word(uint32_t v, uint64_t k) { return v ^ word_key(k); }
inline uint32_t dec_word(uint32_t v, uint64_t k) { return v ^ word_key(k); }

inline bool rpm(HANDLE h, uintptr_t addr, void* dst, size_t n) {
    SIZE_T got = 0;
    return addr != 0 && ReadProcessMemory(h, reinterpret_cast<LPCVOID>(addr), dst, n, &got) && got == n;
}

template <class T>
inline bool rpm_t(HANDLE h, uintptr_t addr, T& out) { return rpm(h, addr, &out, sizeof(T)); }

inline uint32_t find_pid(const wchar_t* name) {
    PROCESSENTRY32W pe{ sizeof(pe) };
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return 0;
    uint32_t pid = 0;
    if (Process32FirstW(snap, &pe)) {
        do {
            if (!_wcsicmp(pe.szExeFile, name)) { pid = pe.th32ProcessID; break; }
        } while (Process32NextW(snap, &pe));
    }
    CloseHandle(snap);
    return pid;
}

inline uintptr_t module_base(uint32_t pid, const wchar_t* name) {
    MODULEENTRY32W me{ sizeof(me) };
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, pid);
    if (snap == INVALID_HANDLE_VALUE) return 0;
    uintptr_t base = 0;
    if (Module32FirstW(snap, &me)) {
        do {
            if (!_wcsicmp(me.szModule, name)) { base = reinterpret_cast<uintptr_t>(me.modBaseAddr); break; }
        } while (Module32NextW(snap, &me));
    }
    CloseHandle(snap);
    return base;
}

struct WinFind { uint32_t pid; HWND best; };

inline BOOL CALLBACK enum_win_cb(HWND h, LPARAM lp) {
    auto* f = reinterpret_cast<WinFind*>(lp);
    DWORD wpid = 0;
    GetWindowThreadProcessId(h, &wpid);
    if (wpid != f->pid || !IsWindowVisible(h)) return TRUE;
    RECT r{};
    if (!GetClientRect(h, &r) || r.right - r.left < 200 || r.bottom - r.top < 120) return TRUE;
    f->best = h;
    return FALSE;
}

inline HWND find_game_window(uint32_t pid) {
    WinFind f{ pid, nullptr };
    EnumWindows(enum_win_cb, reinterpret_cast<LPARAM>(&f));
    return f.best;
}

inline double gauss(std::mt19937& rng) {
    static std::normal_distribution<double> d(0.0, 1.0);
    return d(rng);
}

inline int rnd(std::mt19937& rng, int lo, int hi) {
    if (hi <= lo) return lo;
    return std::uniform_int_distribution<int>(lo, hi)(rng);
}

} // namespace he
