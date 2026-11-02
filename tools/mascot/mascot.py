#!/usr/bin/env python3
"""
Herc, the Hercules mascot: a round cub whose mane is the readiness dial.

One source of truth for the geometry. Running this script writes:
  * Android vector drawables for the phone and the watch (launcher icon, monochrome icon, splash mark,
    notification glyph), so the icon is crisp at every size and needs no bitmaps;
  * docs/images/hercules-icon.png and docs/images/hercules-mascot.png (preview, needs Pillow).

The Compose version (`Herc` in mobile/.../Mascot.kt and wear/.../Mascot.kt) is a hand port of the same numbers.
Viewport is 108 x 108 (the adaptive-icon canvas); everything important sits inside the 66-unit safe circle.
"""
import math, os, sys

CX = CY = 54.0
CYAN, INDIGO, DIM = "#3FE0FF", "#6E6BFF", "#2A2D45"
INK_PHONE, INK_WATCH = "#05050E", "#08080F"

N_TICKS = 19            # a 270 degree dial, like the score gauge
LIT = 15                # about 78 percent lit in the icon
R_HEAD = 17.0
R_TICK_IN, R_TICK_OUT, R_TICK_MAJOR_IN = 26.0, 31.0, 24.5
EAR_R, EAR_DIST = 5.5, 18.5
EYE_DX, EYE_Y = 6.4, 52.0


def tick(i):
    a = math.radians(135 + 270 * i / (N_TICKS - 1))
    r0 = R_TICK_MAJOR_IN if i % 3 == 0 else R_TICK_IN
    return (CX + r0 * math.cos(a), CY + r0 * math.sin(a), CX + R_TICK_OUT * math.cos(a), CY + R_TICK_OUT * math.sin(a))


def ears():
    for deg in (225, 315):
        a = math.radians(deg)
        yield CX + EAR_DIST * math.cos(a), CY + EAR_DIST * math.sin(a)


def f(v):
    return ("%.2f" % v).rstrip("0").rstrip(".")


def circle_path(cx, cy, r):
    return f"M{f(cx - r)},{f(cy)}a{f(r)},{f(r)} 0 1,0 {f(2 * r)},0a{f(r)},{f(r)} 0 1,0 {f(-2 * r)},0z"


def ellipse_path(cx, cy, rx, ry):
    return f"M{f(cx - rx)},{f(cy)}a{f(rx)},{f(ry)} 0 1,0 {f(2 * rx)},0a{f(rx)},{f(ry)} 0 1,0 {f(-2 * rx)},0z"


GEM = "M54,38.4L56.3,41L54,43.6L51.7,41Z"   # the hero's gem, the same diamond as the Plan artwork
SMILE = "M49,58.4Q54,64.2 59,58.4Q54,60.7 49,58.4Z"   # a thin crescent: a hole in the mono icon, dark in colour


def vector(body, size_dp=108, extra_ns=""):
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f'<vector xmlns:android="http://schemas.android.com/apk/res/android"{extra_ns}\n'
        f'    android:width="{size_dp}dp" android:height="{size_dp}dp" android:viewportWidth="108" android:viewportHeight="108">\n'
        + body + "</vector>\n"
    )


def color_icon(ink=None):
    """Full-colour mascot. With [ink] a square background is included (splash); otherwise transparent (adaptive foreground)."""
    out = []
    if ink:
        out.append(f'    <path android:fillColor="{ink}" android:pathData="M0,0h108v108h-108z" />\n')
    for i in range(N_TICKS):
        x0, y0, x1, y1 = tick(i)
        col = CYAN if i < LIT else DIM
        w = 2.8 if i % 3 == 0 else 2.2
        out.append(f'    <path android:strokeColor="{col}" android:strokeWidth="{w}" android:strokeLineCap="round" android:pathData="M{f(x0)},{f(y0)}L{f(x1)},{f(y1)}" />\n')
    for ex, ey in ears():
        out.append(f'    <path android:fillColor="{INDIGO}" android:pathData="{circle_path(ex, ey, EAR_R)}" />\n')
        out.append(f'    <path android:fillColor="{CYAN}" android:pathData="{circle_path(ex, ey, EAR_R * 0.46)}" />\n')
    out.append(
        f'    <path android:pathData="{circle_path(CX, CY, R_HEAD)}">\n'
        '        <aapt:attr name="android:fillColor">\n'
        f'            <gradient android:type="linear" android:startX="{f(CX - 8)}" android:startY="{f(CY - R_HEAD)}" android:endX="{f(CX + 8)}" android:endY="{f(CY + R_HEAD)}">\n'
        f'                <item android:offset="0" android:color="{CYAN}" />\n'
        f'                <item android:offset="1" android:color="{INDIGO}" />\n'
        '            </gradient>\n        </aapt:attr>\n    </path>\n'
    )
    out.append(f'    <path android:fillColor="#EBFFFFFF" android:pathData="{GEM}" />\n')
    dark = ink or INK_PHONE
    for s in (-1, 1):
        out.append(f'    <path android:fillColor="{dark}" android:pathData="{ellipse_path(CX + s * EYE_DX, EYE_Y, 2.3, 3.3)}" />\n')
        out.append(f'    <path android:fillColor="#FFFFFFFF" android:pathData="{circle_path(CX + s * EYE_DX + 0.8, EYE_Y - 1.1, 0.9)}" />\n')
    out.append(f'    <path android:fillColor="{dark}" android:pathData="{SMILE}" />\n')
    return vector("".join(out), extra_ns='\n    xmlns:aapt="http://schemas.android.com/aapt"')


def mono_icon(size_dp=108, all_lit=True):
    """Single-colour silhouette (themed icon, status-bar glyph): the face is a hole punched in a solid head."""
    out = []
    for i in range(N_TICKS):
        x0, y0, x1, y1 = tick(i)
        w = 2.8 if i % 3 == 0 else 2.2
        out.append(f'    <path android:strokeColor="#FFFFFFFF" android:strokeWidth="{w}" android:strokeLineCap="round" android:pathData="M{f(x0)},{f(y0)}L{f(x1)},{f(y1)}" />\n')
    for ex, ey in ears():
        out.append(f'    <path android:fillColor="#FFFFFFFF" android:pathData="{circle_path(ex, ey, EAR_R)}" />\n')
    face = circle_path(CX, CY, R_HEAD) + GEM + "".join(ellipse_path(CX + s * EYE_DX, EYE_Y, 2.3, 3.3) for s in (-1, 1)) + SMILE
    out.append(f'    <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="{face}" />\n')
    return vector("".join(out), size_dp)


# ------------------------------------------------------------------ preview (Pillow)

def render(mood="happy", px=512, lit=LIT, ink=INK_PHONE, transparent=False):
    from PIL import Image, ImageDraw
    S = 8
    n = px * S
    k = n / 108.0
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0) if transparent else ink)
    d = ImageDraw.Draw(img)

    def hexa(c, a=255):
        c = c.lstrip("#"); return (int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16), a)

    def line(x0, y0, x1, y1, w, col):
        d.line([(x0 * k, y0 * k), (x1 * k, y1 * k)], fill=col, width=int(w * k))
        for x, y in ((x0, y0), (x1, y1)):
            d.ellipse([(x - w / 2) * k, (y - w / 2) * k, (x + w / 2) * k, (y + w / 2) * k], fill=col)

    def disc(x, y, r, col):
        d.ellipse([(x - r) * k, (y - r) * k, (x + r) * k, (y + r) * k], fill=col)

    for i in range(N_TICKS):
        x0, y0, x1, y1 = tick(i)
        line(x0, y0, x1, y1, 2.8 if i % 3 == 0 else 2.2, hexa(CYAN if i < lit else DIM))
    for ex, ey in ears():
        disc(ex, ey, EAR_R, hexa(INDIGO)); disc(ex, ey, EAR_R * 0.46, hexa(CYAN))
    # head with a vertical-ish cyan to indigo gradient, masked to the disc
    head = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    hp = head.load()
    y0, y1 = (CY - R_HEAD) * k, (CY + R_HEAD) * k
    c0, c1 = hexa(CYAN), hexa(INDIGO)
    grad = Image.new("RGBA", (1, n))
    for y in range(n):
        t = min(1.0, max(0.0, (y - y0) / (y1 - y0)))
        grad.putpixel((0, y), tuple(int(c0[j] + (c1[j] - c0[j]) * t) for j in range(4)))
    head = grad.resize((n, n))
    mask = Image.new("L", (n, n), 0)
    ImageDraw.Draw(mask).ellipse([(CX - R_HEAD) * k, (CY - R_HEAD) * k, (CX + R_HEAD) * k, (CY + R_HEAD) * k], fill=255)
    img.paste(head, (0, 0), mask)
    d = ImageDraw.Draw(img)
    dark = hexa(ink)
    white = hexa("#FFFFFF")

    def quad(p0, p1, p2, steps=24):
        pts = []
        for s in range(steps + 1):
            t = s / steps
            pts.append(((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0], (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]))
        return pts

    def poly(pts, col):
        d.polygon([(x * k, y * k) for x, y in pts], fill=col)

    poly([(54, 38.4), (56.3, 41), (54, 43.6), (51.7, 41)], hexa("#FFFFFF", 235))
    eyes = [(CX - EYE_DX, EYE_Y), (CX + EYE_DX, EYE_Y)]
    if mood in ("happy", "calm"):
        for ex, ey in eyes:
            d.ellipse([(ex - 2.3) * k, (ey - 3.3) * k, (ex + 2.3) * k, (ey + 3.3) * k], fill=dark)
            disc(ex + 0.8, ey - 1.1, 0.9, white)
    elif mood == "tired":
        for (ex, ey), s_ in zip(eyes, (-1, 1)):
            # half-closed eyes; the lid slopes down towards the outside, which reads as sleepy, not cross
            poly([(ex - 2.6, ey - 0.4 + (0.9 if s_ < 0 else -0.5)), (ex + 2.6, ey - 0.4 + (-0.5 if s_ < 0 else 0.9)), (ex + 2.4, ey + 1.8), (ex, ey + 3.0), (ex - 2.4, ey + 1.8)], dark)
    elif mood == "sleeping":
        for ex, ey in eyes:
            pts = quad((ex - 2.8, ey + 0.3), (ex, ey + 3.4), (ex + 2.8, ey + 0.3))
            for a, b in zip(pts, pts[1:]):
                line(a[0], a[1], b[0], b[1], 1.5, dark)
    if mood == "happy":
        poly(quad((49, 58.4), (54, 64.2), (59, 58.4)) + quad((59, 58.4), (54, 60.7), (49, 58.4)), dark)
    elif mood == "calm":
        line(50.2, 59.6, 57.8, 59.6, 1.5, dark)
    elif mood == "tired":
        line(51.6, 60.2, 56.4, 60.2, 1.4, dark)
    elif mood == "sleeping":
        disc(54, 60.2, 1.2, dark)
        for j, (zx, zy, zs) in enumerate(((72, 36, 4.0), (78, 28, 3.0))):
            line(zx, zy, zx + zs, zy, 1.2, hexa(CYAN)); line(zx + zs, zy, zx, zy + zs, 1.2, hexa(CYAN)); line(zx, zy + zs, zx + zs, zy + zs, 1.2, hexa(CYAN))
    return img.resize((px, px), Image.LANCZOS)


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "w").write(text)


def main(root):
    for mod, ink in (("mobile", INK_PHONE), ("wear", INK_WATCH)):
        res = f"{root}/{mod}/src/main/res"
        write(f"{res}/drawable/ic_launcher_foreground.xml", color_icon())
        write(f"{res}/drawable/ic_launcher_monochrome.xml", mono_icon())
        write(f"{res}/drawable/splash_mark.xml", color_icon())
        write(f"{res}/drawable/ic_notification.xml", mono_icon(24))
    try:
        sheet = [("happy", "Good"), ("calm", "Moderate"), ("tired", "Low"), ("sleeping", "No score")]
        from PIL import Image
        tile = 320
        out = Image.new("RGBA", (tile * len(sheet), tile), INK_PHONE)
        for i, (m, _) in enumerate(sheet):
            out.paste(render(m, tile, lit={"happy": 17, "calm": 12, "tired": 6, "sleeping": 0}[m]), (i * tile, 0))
        os.makedirs(f"{root}/docs/images", exist_ok=True)
        out.convert("RGB").save(f"{root}/docs/images/hercules-mascot.png")
        render("happy", 1024, lit=LIT).convert("RGB").save(f"{root}/docs/images/hercules-icon.png")
    except ImportError:
        print("Pillow not installed: skipped PNG previews", file=sys.stderr)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", ".."))
