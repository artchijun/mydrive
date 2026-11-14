# Mydrive 아이콘 만들기 (외부 라이브러리 없이 PNG 를 직접 만들어 .ico 로 묶음)
#   mydrive.ico            앱 아이콘 (16~256)
#   tray_ok/sync/pause/warn/err.ico   트레이 아이콘 (오른쪽 아래 상태 점)
import math, os, struct, zlib

HERE = os.path.dirname(os.path.abspath(__file__))

def png(w, h, px):
    raw = b"".join(b"\x00" + bytes(px[y * w * 4:(y + 1) * w * 4]) for y in range(h))
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")

def rrect(x, y, r):  # 둥근 사각형 안인지 (0..1 좌표)
    m = 0.04
    cx = min(max(x, m + r), 1 - m - r); cy = min(max(y, m + r), 1 - m - r)
    return (x - cx) ** 2 + (y - cy) ** 2 <= r * r and m <= x <= 1 - m and m <= y <= 1 - m

def tri(px, py, a, b, c):
    def s(p, q, r): return (p[0] - r[0]) * (q[1] - r[1]) - (q[0] - r[0]) * (p[1] - r[1])
    d1, d2, d3 = s((px, py), a, b), s((px, py), b, c), s((px, py), c, a)
    return not ((d1 < 0 or d2 < 0 or d3 < 0) and (d1 > 0 or d2 > 0 or d3 > 0))

R, T = 0.255, 0.075   # 화살표 고리 반지름, 두께
def arrow_head(ang_deg, direction):
    a = math.radians(ang_deg)
    cx, cy = 0.5 + R * math.cos(a), 0.5 - R * math.sin(a)
    tx, ty = -math.sin(a) * direction, -math.cos(a) * direction   # 고리 접선 방향
    nx, ny = math.cos(a), -math.sin(a)                             # 바깥 방향
    L, W = 0.13, 0.12
    return ((cx + tx * L, cy + ty * L), (cx + nx * W, cy + ny * W), (cx - nx * W, cy - ny * W))
HEADS = [arrow_head(150, 1), arrow_head(330, 1)]

def white(x, y):
    d = math.hypot(x - 0.5, y - 0.5)
    ang = math.degrees(math.atan2(0.5 - y, x - 0.5)) % 360
    on_ring = abs(d - R) <= T / 2 and (20 <= ang <= 150 or 200 <= ang <= 330)
    return on_ring or any(tri(x, y, *h) for h in HEADS)

def draw(size, badge=None):
    ss = 4 if size <= 64 else 2
    px = []
    for j in range(size):
        for i in range(size):
            acc = [0.0, 0.0, 0.0, 0.0]
            for sj in range(ss):
                for si in range(ss):
                    x = (i + (si + 0.5) / ss) / size; y = (j + (sj + 0.5) / ss) / size
                    col = None
                    if badge and (x - 0.76) ** 2 + (y - 0.76) ** 2 <= 0.25 ** 2:
                        col = (255, 255, 255) if (x - 0.76) ** 2 + (y - 0.76) ** 2 > 0.19 ** 2 else badge
                    elif rrect(x, y, 0.22):
                        if white(x, y): col = (255, 255, 255)
                        else:
                            t = (x + y) / 2
                            col = (int(47 + (108 - 47) * t), int(111 + (76 - 111) * t), int(237 + (245 - 237) * t))
                    if col:
                        acc[0] += col[0]; acc[1] += col[1]; acc[2] += col[2]; acc[3] += 255
            n = ss * ss; a = acc[3] / n
            if a > 0: px += [int(acc[0] / (acc[3] / 255)), int(acc[1] / (acc[3] / 255)), int(acc[2] / (acc[3] / 255)), int(a)]
            else: px += [0, 0, 0, 0]
    return png(size, size, px)

def ico(path, sizes, badge=None):
    imgs = [(s, draw(s, badge)) for s in sizes]
    head = struct.pack("<HHH", 0, 1, len(imgs)); off = 6 + 16 * len(imgs); dirs = b""; body = b""
    for s, data in imgs:
        dirs += struct.pack("<BBBBHHII", s % 256, s % 256, 0, 0, 1, 32, len(data), off + len(body)); body += data
    open(path, "wb").write(head + dirs + body)

ico(os.path.join(HERE, "mydrive.ico"), [16, 24, 32, 48, 64, 256])
for name, col in [("ok", (34, 197, 94)), ("sync", (59, 130, 246)), ("pause", (148, 163, 184)), ("warn", (245, 158, 11)), ("err", (239, 68, 68))]:
    ico(os.path.join(HERE, f"tray_{name}.ico"), [16, 20, 24, 32, 40, 48], col)
# 눈으로 확인할 미리보기 (256 PNG)
open(os.path.join(HERE, "preview_256.png"), "wb").write(draw(256))
open(os.path.join(HERE, "preview_tray_warn_48.png"), "wb").write(draw(48, (245, 158, 11)))
print("done")
