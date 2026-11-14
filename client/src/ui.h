// ui.h — Direct2D/DirectWrite 로 그리는 작은 화면 도구 (SynoWatch 와 같은 어두운 디자인)
//   좌표는 모두 DIP(96 dpi 기준). 그릴 때마다 누를 수 있는 자리(Hit)를 다시 등록한다.
#pragma once
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <d2d1.h>
#include <dwrite.h>
#include <wincodec.h>
#include <string>
#include <vector>
#include <map>

namespace ui {

struct Color { float r = 0, g = 0, b = 0, a = 1; };
inline Color Hex(uint32_t argb) {   // 0xAARRGGBB
    return {((argb >> 16) & 255) / 255.f, ((argb >> 8) & 255) / 255.f, (argb & 255) / 255.f, ((argb >> 24) & 255) / 255.f};
}
inline Color Alpha(Color c, float a) { c.a = a; return c; }

struct Rect {
    float l = 0, t = 0, r = 0, b = 0;
    float w() const { return r - l; }
    float h() const { return b - t; }
    bool in(float x, float y) const { return x >= l && x < r && y >= t && y < b; }
    Rect inset(float dx, float dy) const { return {l + dx, t + dy, r - dx, b - dy}; }
    Rect row(float y, float hh) const { return {l, y, r, y + hh}; }
};

enum Align { Left, Center, Right };
struct TextOpt {
    float size = 12.5f;
    DWRITE_FONT_WEIGHT weight = DWRITE_FONT_WEIGHT_NORMAL;
    Align align = Left;
    bool wrap = false;       // 여러 줄
    bool top = false;        // 위쪽 맞춤 (기본은 세로 가운데)
    bool icon = false;       // Segoe Fluent Icons
};

// 색 (SynoWatch 와 같음)
namespace col {
inline Color Text() { return Hex(0xFFEDEFF4); }
inline Color Muted() { return Hex(0xFF8C93A3); }
inline Color Sub() { return Hex(0xFFB4BAC6); }
inline Color Card() { return Hex(0xFF252931); }
inline Color Blue() { return Hex(0xFF5B9BFF); }
inline Color Up() { return Hex(0xFF7FB2FF); }
inline Color Green() { return Hex(0xFF38D39F); }
inline Color Purple() { return Hex(0xFFC08BFF); }
inline Color Red() { return Hex(0xFFFF6B74); }
inline Color Orange() { return Hex(0xFFF5B544); }
inline Color Hold() { return Hex(0xFFB18CFF); }
inline Color White(float a) { return {1, 1, 1, a}; }
}

class Canvas {
public:
    bool Init(HWND h);
    void Resize();
    void Begin(Color clear);
    void End();
    float scale = 1;   // 화소 / DIP
    float W() const { return size_.width; }
    float H() const { return size_.height; }

    void Fill(Rect r, Color c, float radius = 0);
    void Stroke(Rect r, Color c, float radius = 0, float w = 1);
    void Gradient(Rect r, Color a, Color b, float radius, bool diagonal);
    void Text(const std::wstring& s, Rect r, Color c, const TextOpt& o = {});
    float TextWidth(const std::wstring& s, const TextOpt& o);
    float TextHeight(const std::wstring& s, float width, const TextOpt& o);
    void Icon(wchar_t glyph, Rect r, Color c, float size = 13);
    void Line(float x1, float y1, float x2, float y2, Color c, float w = 1);
    void Dot(float cx, float cy, float rad, Color c);
    void PushClip(Rect r);
    void PopClip();

    // 누를 수 있는 자리
    struct Hit { Rect r; int id; std::wstring tip; bool hand; };
    std::vector<Hit> hits;
    int hover = 0, pressed = 0;
    void AddHit(Rect r, int id, const std::wstring& tip = L"", bool hand = true) { hits.push_back({r, id, tip, hand}); }
    const Hit* HitAt(float x, float y) const;

    ID2D1HwndRenderTarget* rt() { return rt_; }
private:
    HWND hwnd_ = nullptr;
    ID2D1HwndRenderTarget* rt_ = nullptr;
    D2D1_SIZE_F size_{};
    std::map<uint64_t, ID2D1SolidColorBrush*> brushes_;
    std::map<uint64_t, IDWriteTextFormat*> formats_;
    ID2D1SolidColorBrush* Brush(Color c);
    IDWriteTextFormat* Format(const TextOpt& o);
    void Release();
};

ID2D1Factory* D2D();
IDWriteFactory* DWrite();
const wchar_t* IconFont();
// 트레이 아이콘: 색 원 안에 기호 (SynoWatch 와 같은 모양)
HICON MakeStatusIcon(uint32_t argb, wchar_t glyph, int px);

} // namespace ui
