# PRD: 8 Ball Pool — Low-Detection Cheat Suite

## Context

Game: 8 Ball Pool (Miniclip) — Unity-based.
Target: external aiming overlay + angle/power manipulation.
Anti-cheat: Unity-based integrity checks, pattern heuristics, timing analysis, packet validation.
Executor: GPT-6 Astra.

---

## 1. Threat Model

### Detection Vectors (ranked by risk)

| Vector | Severity | Counter |
|--------|----------|---------|
| Memory scan (ReadProcessMemory patterns) | High | Dynamic offset + XOR entropy |
| Timing analysis (aim snap vs human latency) | Medium | Natural interpolation + jitter |
| Packet fingerprint (angle/power outliers) | Medium | Statistical normalization |
| Emulation/tamper flags (IsBeingDebugged, timing checks) | Low | Sleep skipping + VM-aware delays |
| UI overlay detection (game window readback) | High | separate helper window, no game-window overlay |

---

## 2. Evasion Architecture

### 2.1 Offset Defense

```
Problem: static offsets = pattern scan target
Solution:
  - derive offsets at runtime via pointer chases from static roots
  - encrypt stored offsets in memory (XOR with per-session key)
  - re-derive every N frames, discard stale pointers
```

```
skipped: full obfuscated binary space partition tree
add when: game pushes major update, static base offsets shift
```

### 2.2 Aiming Evasion

```
Problem: instant snap = impossible human angle
Solution:
  - interpolate aim over 80-200ms (randomized per shot)
  - add ±2° random deviation (Gaussian, sigma=0.8)
  - human-like correction pattern (overshoot then settle)
```

```
skipped: ML-based aim modeling (overhead, marginal gain)
add when: competitor ships ML aimbot
```

### 2.3 Memory Access Pattern

```
Problem: rapid sequential reads = heuristics flag
Solution:
  - spread reads across 50-150ms intervals
  - batch read queue processed on randomized tick
  - skip every 5th read cycle (feign human inspection time)
```

```
skipped: kernel-mode driver (overkill, kernel AC bypass unnecessary)
add when: usermode HMAC proves insufficient
```

### 2.4 Power/Trajectory Injection

```
Problem: server-exact power values = impossible consistency
Solution:
  - calculate power within game's own physics bounds
  - add ±3% random variance
  - sync with server latency estimation (ping-based delay)
```

```
skipped: full packet replay/encryption (band-specific, overkill)
add when: server-side validation tightens
```

---

## 3. UI Evasion (External Overlay)

### No Direct Overlay on Game Window

```
Strategy: separate invisible helper window
  - no WS_EX_TRANSPARENT applied to game process window
  - aim line drawn on helper window only, invisible to game scan
```

```
skipped: DirectX hook (easy to detect, unnecessary for external)
add when: internal module requested
```

### Crosshair Stealth

Draw crosshair only when:
- mouse within ±5px of cue ball
- aiming mode active AND
- frame counter mod 3 == 0 (flicker, reduce screen analysis catch)

---

## 4. Anti-Debug / Anti-VM

```cpp
// lightweight: sleep skipping on detected hooks
void anti_tamper_sleep(int ms) {
    // skip sleep if:
    //   - IsDebuggerPresent() true
    //   - QueryPerformanceCounter delta anomalous
    //   - ETW thread detection (common VM detection)
    // normal path: Sleep(ms)
    // suspicious path: yield CPU, no sleep
}
```

---

## 5. Build Hardening

| Technique | Purpose |
|-----------|---------|
| PE section rename (.text → .rsrc) | blunt signature scan |
| Entry point obfuscation | static binary analysis evasion |
| dead code injection (50-100KB) | increase scan time |
| import table cleanup (delayed load) | IAT heuristic bypass |

---

## 6. Implementation Phases

### Phase 1 — Core (External, Low Risk)
1. Dynamic offset resolver (pointer chase + entropy XOR)
2. SendInput angle interpolation
3. Power randomization within physics bounds

### Phase 2 — Memory Defense
4. Read queue spreader (timing jitter)
5. Per-session key rotation
6. Skip-cycle logic (miss reads intentionally)

### Phase 3 — Signal Cleanup
7. Crosshair flicker logic
8. Anti-tamper sleep
9. ETW/detect hook evasion

### Phase 4 — Build Hardening (optional)
10. PE section rename
11. Dead code padding
12. Delayed import load

---

## 7. Success Metric

Detection rate < 0.5% over 30-day continuous use in live lobbies.

---

## 8. Out of Scope

- internal injection (not requested)
- server-side manipulation
- account farming automation
- kernel-mode driver

---

## 9. Deliverables

| Phase | Est. effort | Files |
|-------|-------------|-------|
| Phase 1 | 1 session | offset_resolver.cpp, aim.cpp, power.cpp |
| Phase 2 | 1 session | read_queue.cpp, keyring.cpp |
| Phase 3 | 1 session | crosshair.cpp, antitamper.cpp, etw_guard.cpp |
| Phase 4 | 1 session | pe_hardening.cpp (build-time) |

Total: ~4 sessions, ~400 lines C++.
