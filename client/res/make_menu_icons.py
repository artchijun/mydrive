# 오른쪽 클릭 메뉴 아이콘 (외부 라이브러리 없이 PNG → .ico)
#   menu_sync / pause / resume / skip / status / versions / deleted / copy .ico  (16, 20, 24, 32, 48)
#   밝은·어두운 메뉴 모두에서 보이게 가운데 밝기의 파란색 한 가지로 그림
import math, os, struct, zlib

HERE = os.path.dirname(os.path.abspath(__file__))
BLUE = (59, 130, 246)
RED = (239, 68, 68)

def png(w, h, px):
    raw = b"".join(b"\x00" + bytes(px[y * w * 4:(y + 1) * w * 4]) for y in range(h))
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")

def tri(px, py, a, b, c):
    def s(p, q, r): return (p[0] - r[0]) * (q[1] - r[1]) - (q[0] - r[0]) * (p[1] - r[1])
    d1, d2, d3 = s((px, py), a, b), s((px, py), b, c), s((px, py), c, a)
    return not ((d1 < 0 or d2 < 0 or d3 < 0) and (d1 > 0 or d2 > 0 or d3 > 0))

def rbox(x, y, x0, y0, x1, y1, r):   # 둥근 사각형 (채움)
    cx = min(max(x, x0 + r), x1 - r); cy = min(max(y, y0 + r), y1 - r)
    return x0 <= x <= x1 and y0 <= y <= y1 and (x - cx) ** 2 + (y - cy) ** 2 <= r * r

def ring(x, y, r, t, a0=0, a1=360):
    d = math.hypot(x - 0.5, y - 0.5)
    ang = math.degrees(math.atan2(0.5 - y, x - 0.5)) % 360
    inside = a0 <= ang <= a1 if a0 <= a1 else (ang >= a0 or ang <= a1)
    return abs(d - r) <= t / 2 and inside

def seg(x, y, ax, ay, bx, by, t):    # 굵은 선분
    vx, vy = bx - ax, by - ay; L = vx * vx + vy * vy
    k = max(0, min(1, ((x - ax) * vx + (y - ay) * vy) / L))
    return math.hypot(x - (ax + k * vx), y - (ay + k * vy)) <= t / 2

T = 0.12
def head(ang_deg, R, L=0.17, W=0.15):
    a = math.radians(ang_deg)
    cx, cy = 0.5 + R * math.cos(a), 0.5 - R * math.sin(a)
    tx, ty = -math.sin(a), -math.cos(a)
    nx, ny = math.cos(a), -math.sin(a)
    return ((cx + tx * L, cy + ty * L), (cx + nx * W, cy + ny * W), (cx - nx * W, cy - ny * W))

SH = [head(150, 0.32), head(330, 0.32)]
def g_sync(x, y):
    return ring(x, y, 0.32, T, 20, 150) or ring(x, y, 0.32, T, 200, 330) or any(tri(x, y, *h) for h in SH)
def g_pause(x, y):
    return rbox(x, y, 0.24, 0.18, 0.42, 0.82, 0.06) or rbox(x, y, 0.58, 0.18, 0.76, 0.82, 0.06)
def g_resume(x, y):
    return tri(x, y, (0.27, 0.15), (0.27, 0.85), (0.85, 0.5))
def g_skip(x, y):
    return ring(x, y, 0.33, T) or seg(x, y, 0.27, 0.27, 0.73, 0.73, T)
def g_status(x, y):
    return ring(x, y, 0.35, 0.1) or (x - 0.5) ** 2 + (y - 0.31) ** 2 <= 0.065 ** 2 or rbox(x, y, 0.445, 0.43, 0.555, 0.72, 0.03)
def g_versions(x, y):
    return ring(x, y, 0.35, 0.1) or seg(x, y, 0.5, 0.5, 0.5, 0.27, 0.1) or seg(x, y, 0.5, 0.5, 0.66, 0.6, 0.1)
def g_deleted(x, y):   # 휴지통 + 되돌리기 화살표
    lid = rbox(x, y, 0.16, 0.18, 0.84, 0.27, 0.03) or rbox(x, y, 0.4, 0.1, 0.6, 0.2, 0.03)
    body = rbox(x, y, 0.24, 0.31, 0.76, 0.9, 0.07) and not rbox(x, y, 0.33, 0.4, 0.67, 0.81, 0.04)
    return lid or body or tri(x, y, (0.5, 0.45), (0.38, 0.62), (0.62, 0.62)) or rbox(x, y, 0.455, 0.58, 0.545, 0.76, 0.01)
def g_copy(x, y):      # 겹친 두 장
    back = rbox(x, y, 0.16, 0.12, 0.62, 0.66, 0.07) and not rbox(x, y, 0.26, 0.22, 0.52, 0.56, 0.03)
    front = rbox(x, y, 0.38, 0.34, 0.84, 0.88, 0.07)
    hole = rbox(x, y, 0.48, 0.44, 0.74, 0.78, 0.03)
    return (front and not hole) or (back and not rbox(x, y, 0.33, 0.29, 0.89, 0.93, 0.07))

def g_keep(x, y):      # 꽉 찬 원 + 체크 (빈칸)
    d = math.hypot(x - 0.5, y - 0.5)
    return d <= 0.42 and not (seg(x, y, 0.3, 0.52, 0.45, 0.67, 0.1) or seg(x, y, 0.45, 0.67, 0.72, 0.36, 0.1))
def g_free(x, y):      # 구름 테두리
    def cloud(x, y, k):
        return (math.hypot(x - 0.36, y - 0.58) <= 0.17 * k or math.hypot(x - 0.56, y - 0.47) <= 0.22 * k or math.hypot(x - 0.72, y - 0.62) <= 0.14 * k
                or rbox(x, y, 0.36, 0.55, 0.74, 0.76, 0.0) and k >= 1)
    outer = cloud(x, y, 1.0) or rbox(x, y, 0.2, 0.58, 0.86, 0.76, 0.09)
    inner = (math.hypot(x - 0.36, y - 0.58) <= 0.10 or math.hypot(x - 0.56, y - 0.47) <= 0.15 or math.hypot(x - 0.72, y - 0.62) <= 0.07
             or rbox(x, y, 0.3, 0.6, 0.78, 0.69, 0.04))
    return outer and not inner
ICONS = {"keep": (g_keep, (46, 184, 92)), "free": (g_free, BLUE), "sync": (g_sync, BLUE), "pause": (g_pause, BLUE), "resume": (g_resume, BLUE), "skip": (g_skip, RED),
         "status": (g_status, BLUE), "versions": (g_versions, BLUE), "deleted": (g_deleted, BLUE), "copy": (g_copy, BLUE)}

def draw(size, g, col):
    ss = 4
    px = []
    for j in range(size):
        for i in range(size):
            a = 0
            for sj in range(ss):
                for si in range(ss):
                    if g((i + (si + 0.5) / ss) / size, (j + (sj + 0.5) / ss) / size): a += 1
            px += [col[0], col[1], col[2], int(255 * a / (ss * ss))]
    return png(size, size, px)

def ico(path, g, col, sizes=(16, 20, 24, 32, 48)):
    imgs = [(s, draw(s, g, col)) for s in sizes]
    head_ = struct.pack("<HHH", 0, 1, len(imgs)); off = 6 + 16 * len(imgs); dirs = b""; body = b""
    for s, data in imgs:
        dirs += struct.pack("<BBBBHHII", s % 256, s % 256, 0, 0, 1, 32, len(data), off + len(body)); body += data
    open(path, "wb").write(head_ + dirs + body)

for name, (g, col) in ICONS.items():
    ico(os.path.join(HERE, f"menu_{name}.ico"), g, col)
# 한눈에 보는 미리보기 (48px 8개 가로로)
W = 48 * len(ICONS) + 8 * (len(ICONS) - 1)
rows = [[0, 0, 0, 0] * W for _ in range(48)]
for k, (g, col) in enumerate(ICONS.values()):
    for j in range(48):
        for i in range(48):
            x0 = k * 56 + i
            a = sum(g((i + (si + 0.5) / 4) / 48, (j + (sj + 0.5) / 4) / 48) for si in range(4) for sj in range(4))
            rows[j][x0 * 4:x0 * 4 + 4] = [col[0], col[1], col[2], int(255 * a / 16)]
open(os.path.join(HERE, "preview_menu.png"), "wb").write(png(W, 48, [v for r in rows for v in r]))
print("done")
