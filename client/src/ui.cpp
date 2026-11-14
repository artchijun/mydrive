// ui.cpp — Direct2D/DirectWrite 그리기 도구
#include "ui.h"
#include <cmath>
#include <algorithm>
#pragma comment(lib, "d2d1.lib")
#pragma comment(lib, "dwrite.lib")
#pragma comment(lib, "windowscodecs.lib")

namespace ui {

static ID2D1Factory* g_d2d;
static IDWriteFactory* g_dw;
static std::wstring g_iconFont, g_textFont;

ID2D1Factory* D2D() {
    if (!g_d2d) D2D1CreateFactory(D2D1_FACTORY_TYPE_SINGLE_THREADED, &g_d2d);
    return g_d2d;
}
static bool HasFamily(const wchar_t* name) {
    IDWriteFontCollection* fc = nullptr;
    if (FAILED(DWrite()->GetSystemFontCollection(&fc))) return false;
    UINT32 idx; BOOL ex = FALSE;
    fc->FindFamilyName(name, &idx, &ex);
    fc->Release();
    return ex;
}
IDWriteFactory* DWrite() {
    if (!g_dw) {
        DWriteCreateFactory(DWRITE_FACTORY_TYPE_SHARED, __uuidof(IDWriteFactory), (IUnknown**)&g_dw);
        g_iconFont = HasFamily(L"Segoe Fluent Icons") ? L"Segoe Fluent Icons" : L"Segoe MDL2 Assets";
        g_textFont = HasFamily(L"Segoe UI Variable Text") ? L"Segoe UI Variable Text" : L"Segoe UI";
    }
    return g_dw;
}
const wchar_t* IconFont() { DWrite(); return g_iconFont.c_str(); }

static D2D1_COLOR_F C(Color c) { return D2D1::ColorF(c.r, c.g, c.b, c.a); }
static D2D1_RECT_F R(Rect r) { return D2D1::RectF(r.l, r.t, r.r, r.b); }

bool Canvas::Init(HWND h) {
    hwnd_ = h;
    UINT dpi = GetDpiForWindow(h);
    scale = dpi / 96.f;
    RECT rc; GetClientRect(h, &rc);
    D2D1_RENDER_TARGET_PROPERTIES p = D2D1::RenderTargetProperties(D2D1_RENDER_TARGET_TYPE_DEFAULT,
        D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM, D2D1_ALPHA_MODE_PREMULTIPLIED), (float)dpi, (float)dpi);
    if (FAILED(D2D()->CreateHwndRenderTarget(p, D2D1::HwndRenderTargetProperties(h, D2D1::SizeU(rc.right, rc.bottom)), &rt_))) return false;
    rt_->SetTextAntialiasMode(D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE);
    size_ = rt_->GetSize();
    return true;
}
void Canvas::Release() {
    for (auto& [k, b] : brushes_) b->Release();
    brushes_.clear();
    if (rt_) { rt_->Release(); rt_ = nullptr; }
}
void Canvas::Resize() {
    if (!rt_) return;
    UINT dpi = GetDpiForWindow(hwnd_);
    if (dpi / 96.f != scale) { Release(); Init(hwnd_); return; }
    RECT rc; GetClientRect(hwnd_, &rc);
    rt_->Resize(D2D1::SizeU(rc.right, rc.bottom));
    size_ = rt_->GetSize();
}
void Canvas::Begin(Color clear) {
    if (!rt_) Init(hwnd_);
    hits.clear();
    rt_->BeginDraw();
    rt_->Clear(C(clear));
}
void Canvas::End() {
    if (rt_->EndDraw() == D2DERR_RECREATE_TARGET) { Release(); Init(hwnd_); InvalidateRect(hwnd_, nullptr, FALSE); }
}
ID2D1SolidColorBrush* Canvas::Brush(Color c) {
    uint64_t k = ((uint64_t)(c.r * 255) << 24) | ((uint64_t)(c.g * 255) << 16) | ((uint64_t)(c.b * 255) << 8) | (uint64_t)(c.a * 255);
    auto it = brushes_.find(k);
    if (it != brushes_.end()) return it->second;
    ID2D1SolidColorBrush* b = nullptr;
    rt_->CreateSolidColorBrush(C(c), &b);
    brushes_[k] = b;
    return b;
}
IDWriteTextFormat* Canvas::Format(const TextOpt& o) {
    uint64_t k = ((uint64_t)(o.size * 10) << 20) | ((uint64_t)o.weight << 8) | (o.icon ? 1 : 0) | (o.wrap ? 2 : 0) | ((uint64_t)o.align << 4);
    auto it = formats_.find(k);
    if (it != formats_.end()) return it->second;
    DWrite();
    IDWriteTextFormat* f = nullptr;
    DWrite()->CreateTextFormat(o.icon ? g_iconFont.c_str() : g_textFont.c_str(), nullptr, o.weight, DWRITE_FONT_STYLE_NORMAL,
                               DWRITE_FONT_STRETCH_NORMAL, o.size, L"ko-kr", &f);
    f->SetTextAlignment(o.align == Center ? DWRITE_TEXT_ALIGNMENT_CENTER : o.align == Right ? DWRITE_TEXT_ALIGNMENT_TRAILING : DWRITE_TEXT_ALIGNMENT_LEADING);
    f->SetWordWrapping(o.wrap ? DWRITE_WORD_WRAPPING_WRAP : DWRITE_WORD_WRAPPING_NO_WRAP);
    if (!o.wrap) {
        DWRITE_TRIMMING tr{DWRITE_TRIMMING_GRANULARITY_CHARACTER, 0, 0};
        IDWriteInlineObject* ell = nullptr;
        DWrite()->CreateEllipsisTrimmingSign(f, &ell);
        f->SetTrimming(&tr, ell);
        if (ell) ell->Release();
    }
    formats_[k] = f;
    return f;
}

void Canvas::Fill(Rect r, Color c, float radius) {
    if (c.a <= 0) return;
    if (radius > 0) rt_->FillRoundedRectangle(D2D1::RoundedRect(R(r), radius, radius), Brush(c));
    else rt_->FillRectangle(R(r), Brush(c));
}
void Canvas::Stroke(Rect r, Color c, float radius, float w) {
    Rect q = r.inset(w / 2, w / 2);
    if (radius > 0) rt_->DrawRoundedRectangle(D2D1::RoundedRect(R(q), radius, radius), Brush(c), w);
    else rt_->DrawRectangle(R(q), Brush(c), w);
}
void Canvas::Gradient(Rect r, Color a, Color b, float radius, bool diagonal) {
    D2D1_GRADIENT_STOP st[2] = {{0, C(a)}, {1, C(b)}};
    ID2D1GradientStopCollection* sc = nullptr;
    rt_->CreateGradientStopCollection(st, 2, &sc);
    ID2D1LinearGradientBrush* lb = nullptr;
    rt_->CreateLinearGradientBrush(D2D1::LinearGradientBrushProperties(D2D1::Point2F(r.l, r.t), diagonal ? D2D1::Point2F(r.r, r.b) : D2D1::Point2F(r.l, r.b)), sc, &lb);
    if (radius > 0) rt_->FillRoundedRectangle(D2D1::RoundedRect(R(r), radius, radius), lb);
    else rt_->FillRectangle(R(r), lb);
    lb->Release(); sc->Release();
}
void Canvas::Text(const std::wstring& s, Rect r, Color c, const TextOpt& o) {
    if (s.empty() || r.w() <= 0) return;
    IDWriteTextLayout* lay = nullptr;
    if (FAILED(DWrite()->CreateTextLayout(s.c_str(), (UINT32)s.size(), Format(o), r.w(), std::max(1.f, r.h()), &lay))) return;
    if (!o.top) lay->SetParagraphAlignment(DWRITE_PARAGRAPH_ALIGNMENT_CENTER);
    rt_->DrawTextLayout(D2D1::Point2F(r.l, r.t), lay, Brush(c), D2D1_DRAW_TEXT_OPTIONS_CLIP);
    lay->Release();
}
float Canvas::TextWidth(const std::wstring& s, const TextOpt& o) {
    IDWriteTextLayout* lay = nullptr;
    TextOpt q = o; q.wrap = false;
    if (FAILED(DWrite()->CreateTextLayout(s.c_str(), (UINT32)s.size(), Format(q), 10000, 100, &lay))) return 0;
    DWRITE_TEXT_METRICS m{}; lay->GetMetrics(&m); lay->Release();
    return m.widthIncludingTrailingWhitespace;
}
float Canvas::TextHeight(const std::wstring& s, float width, const TextOpt& o) {
    IDWriteTextLayout* lay = nullptr;
    TextOpt q = o; q.wrap = true;
    if (FAILED(DWrite()->CreateTextLayout(s.c_str(), (UINT32)s.size(), Format(q), width, 10000, &lay))) return 0;
    DWRITE_TEXT_METRICS m{}; lay->GetMetrics(&m); lay->Release();
    return m.height;
}
void Canvas::Icon(wchar_t glyph, Rect r, Color c, float size) {
    TextOpt o; o.size = size; o.icon = true; o.align = Center;
    Text(std::wstring(1, glyph), r, c, o);
}
void Canvas::Line(float x1, float y1, float x2, float y2, Color c, float w) {
    rt_->DrawLine(D2D1::Point2F(x1, y1), D2D1::Point2F(x2, y2), Brush(c), w);
}
void Canvas::Dot(float cx, float cy, float rad, Color c) {
    rt_->FillEllipse(D2D1::Ellipse(D2D1::Point2F(cx, cy), rad, rad), Brush(c));
}
void Canvas::PushClip(Rect r) { rt_->PushAxisAlignedClip(R(r), D2D1_ANTIALIAS_MODE_ALIASED); }
void Canvas::PopClip() { rt_->PopAxisAlignedClip(); }

const Canvas::Hit* Canvas::HitAt(float x, float y) const {
    for (size_t i = hits.size(); i-- > 0;) if (hits[i].r.in(x, y)) return &hits[i];
    return nullptr;
}

// ---------- 트레이 아이콘 ----------
HICON MakeStatusIcon(uint32_t argb, wchar_t glyph, int px) {
    IWICImagingFactory* wic = nullptr;
    if (FAILED(CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&wic)))) return nullptr;
    IWICBitmap* bmp = nullptr;
    wic->CreateBitmap(px, px, GUID_WICPixelFormat32bppPBGRA, WICBitmapCacheOnLoad, &bmp);
    ID2D1RenderTarget* rt = nullptr;
    D2D()->CreateWicBitmapRenderTarget(bmp, D2D1::RenderTargetProperties(D2D1_RENDER_TARGET_TYPE_SOFTWARE,
        D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM, D2D1_ALPHA_MODE_PREMULTIPLIED), 96, 96), &rt);
    HICON icon = nullptr;
    if (rt) {
        rt->BeginDraw();
        rt->Clear(D2D1::ColorF(0, 0, 0, 0));
        ID2D1SolidColorBrush *bg = nullptr, *fg = nullptr;
        Color c = Hex(argb);
        rt->CreateSolidColorBrush(C(c), &bg);
        rt->CreateSolidColorBrush(D2D1::ColorF(1, 1, 1, 1), &fg);
        float h = px / 2.f;
        rt->FillEllipse(D2D1::Ellipse(D2D1::Point2F(h, h), h - 0.5f, h - 0.5f), bg);
        IDWriteTextFormat* f = nullptr;
        DWrite()->CreateTextFormat(IconFont(), nullptr, DWRITE_FONT_WEIGHT_NORMAL, DWRITE_FONT_STYLE_NORMAL, DWRITE_FONT_STRETCH_NORMAL, px * 0.5f, L"", &f);
        f->SetTextAlignment(DWRITE_TEXT_ALIGNMENT_CENTER);
        f->SetParagraphAlignment(DWRITE_PARAGRAPH_ALIGNMENT_CENTER);
        rt->SetTextAntialiasMode(D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE);
        rt->DrawTextW(&glyph, 1, f, D2D1::RectF(0, 0.5f, (float)px, (float)px + 0.5f), fg);
        f->Release(); bg->Release(); fg->Release();
        rt->EndDraw();
        rt->Release();
        // WIC 화소 → 32비트 DIB → 아이콘
        BITMAPV5HEADER bi{}; bi.bV5Size = sizeof bi; bi.bV5Width = px; bi.bV5Height = -px; bi.bV5Planes = 1; bi.bV5BitCount = 32;
        bi.bV5Compression = BI_BITFIELDS; bi.bV5RedMask = 0x00FF0000; bi.bV5GreenMask = 0x0000FF00; bi.bV5BlueMask = 0x000000FF; bi.bV5AlphaMask = 0xFF000000;
        void* bits = nullptr;
        HDC dc = GetDC(nullptr);
        HBITMAP color = CreateDIBSection(dc, (BITMAPINFO*)&bi, DIB_RGB_COLORS, &bits, nullptr, 0);
        ReleaseDC(nullptr, dc);
        WICRect wr{0, 0, px, px};
        bmp->CopyPixels(&wr, px * 4, px * px * 4, (BYTE*)bits);
        // 미리 곱한 알파 → 곧은 알파 (아이콘은 곧은 알파)
        BYTE* p = (BYTE*)bits;
        for (int i = 0; i < px * px; i++, p += 4) if (p[3] && p[3] < 255) for (int k = 0; k < 3; k++) p[k] = (BYTE)std::min(255, p[k] * 255 / p[3]);
        HBITMAP mask = CreateBitmap(px, px, 1, 1, nullptr);
        ICONINFO ii{TRUE, 0, 0, mask, color};
        icon = CreateIconIndirect(&ii);
        DeleteObject(mask); DeleteObject(color);
    }
    // 확인용: MYDRIVE_ICON_DUMP 폴더가 있으면 PNG 로도 저장
    wchar_t dump[MAX_PATH];
    if (GetEnvironmentVariableW(L"MYDRIVE_ICON_DUMP", dump, MAX_PATH)) {
        wchar_t fn[MAX_PATH]; swprintf(fn, MAX_PATH, L"%s\\icon_%08X_%d.png", dump, argb, px);
        IWICStream* st = nullptr; IWICBitmapEncoder* enc = nullptr; IWICBitmapFrameEncode* fr = nullptr;
        if (SUCCEEDED(wic->CreateStream(&st)) && SUCCEEDED(st->InitializeFromFilename(fn, GENERIC_WRITE)) &&
            SUCCEEDED(wic->CreateEncoder(GUID_ContainerFormatPng, nullptr, &enc)) && SUCCEEDED(enc->Initialize(st, WICBitmapEncoderNoCache)) &&
            SUCCEEDED(enc->CreateNewFrame(&fr, nullptr)) && SUCCEEDED(fr->Initialize(nullptr))) {
            fr->WriteSource(bmp, nullptr); fr->Commit(); enc->Commit();
        }
        if (fr) fr->Release(); if (enc) enc->Release(); if (st) st->Release();
    }
    bmp->Release(); wic->Release();
    return icon;
}

} // namespace ui
