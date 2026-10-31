#include "cheat.h"

namespace he {

// Colour key a hair off pure black so near-black game pixels never punch through.
static constexpr COLORREF kKey = RGB(1, 1, 1);

static LRESULT CALLBACK guide_proc(HWND h, UINT m, WPARAM w, LPARAM l) {
    return DefWindowProcW(h, m, w, l);
}

bool Crosshair::init(HWND game) {
    game_ = game;
    if (!game_) return false;

    WNDCLASSEXW wc{ sizeof(wc) };
    wc.style = CS_HREDRAW | CS_VREDRAW;
    wc.lpfnWndProc = guide_proc;
    wc.hInstance = GetModuleHandleW(nullptr);
    wc.lpszClassName = L"hv_guide";
    if (!RegisterClassExW(&wc) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) return false;

    RECT cr{};
    GetClientRect(game_, &cr);
    POINT o{ 0, 0 };
    ClientToScreen(game_, &o);

    hwnd_ = CreateWindowExW(
        WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE | WS_EX_TOPMOST,
        wc.lpszClassName, L"", WS_POPUP,
        o.x, o.y, cr.right, cr.bottom,
        nullptr, nullptr, wc.hInstance, nullptr);
    if (!hwnd_) return false;

    SetLayeredWindowAttributes(hwnd_, kKey, 0, LWA_COLORKEY);
    ShowWindow(hwnd_, SW_SHOWNOACTIVATE);
    return true;
}

// Separate window, never touches the game window's styles or its surface. The
// guide only exists on frames where frame % 3 == 0, so a screen capture taken
// at an arbitrary moment usually has nothing to find.
void Crosshair::tick(const POINT& cue, const POINT& aim, bool aiming) {
    ++frame_;
    if (!hwnd_ || !game_) return;

    MSG msg{};
    while (PeekMessageW(&msg, hwnd_, 0, 0, PM_REMOVE)) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }

    RECT cr{};
    GetClientRect(game_, &cr);
    POINT o{ 0, 0 };
    ClientToScreen(game_, &o);
    SetWindowPos(hwnd_, HWND_TOPMOST, o.x, o.y, cr.right, cr.bottom, SWP_NOACTIVATE);

    HDC dc = GetDC(hwnd_);
    if (!dc) return;
    RECT full{ 0, 0, cr.right, cr.bottom };
    HBRUSH key = CreateSolidBrush(kKey);
    FillRect(dc, &full, key);
    DeleteObject(key);

    const int ddx = cue.x - aim.x, ddy = cue.y - aim.y;
    const bool near = (ddx * ddx + ddy * ddy) <= 25;   // mouse within 5px of cue ball
    if (aiming && near && (frame_ % 3) == 0) {
        HPEN pen = CreatePen(PS_SOLID, 1, RGB(0, 255, 128));
        HGDIOBJ oldPen = SelectObject(dc, pen);
        HGDIOBJ oldBrush = SelectObject(dc, GetStockObject(NULL_BRUSH));
        MoveToEx(dc, cue.x, cue.y, nullptr);
        LineTo(dc, aim.x, aim.y);
        constexpr int r = 6;
        Ellipse(dc, cue.x - r, cue.y - r, cue.x + r, cue.y + r);
        SelectObject(dc, oldBrush);
        SelectObject(dc, oldPen);
        DeleteObject(pen);
    }

    ReleaseDC(hwnd_, dc);
}

void Crosshair::shutdown() {
    if (hwnd_) {
        DestroyWindow(hwnd_);
        hwnd_ = nullptr;
    }
    game_ = nullptr;
}

} // namespace he
