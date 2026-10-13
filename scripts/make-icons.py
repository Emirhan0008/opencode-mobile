# -*- coding: utf-8 -*-
"""OpenCode Mobile logo uretimi.

Tasarim: koyu kare zemin, ortada telefon cizimi — ustte beyaz / altta gri ekran,
ust bolgede hoparlor pilli + kamera noktasi, kalin siyah cerceve.

Ciktilar:
  app/src/main/res/drawable/ic_launcher_foreground.png   (adaptive icon on plani, saydam)
  app/src/main/res/drawable/ic_logo.png                  (uygulama ici basliklar)
  app/src/main/res/mipmap-*/ic_launcher.png              (eski cihaz yedegi)
  app/src/main/res/mipmap-*/ic_launcher_round.png
  docs/icon-512.png                                      (README / magaza)

Kullanim: python scripts/make-icons.py
"""

import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

DARK = (45, 45, 45, 255)      # kare zemin
BLACK = (17, 17, 17, 255)     # cerceve + notch
GRAY = (199, 199, 199, 255)   # ekran alt bolge
WHITE = (255, 255, 255, 255)  # ekran ust bolge


def draw_device(size: int, background: bool, box) -> Image.Image:
    """Telefon cizimini uretir.

    box: telefon kutusunun kesirsel koordinatlari (x0, y0, x1, y1).
    """
    img = Image.new("RGBA", (size, size), DARK if background else (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    x0 = int(size * box[0])
    y0 = int(size * box[1])
    x1 = int(size * box[2])
    y1 = int(size * box[3])
    w = x1 - x0
    h = y1 - y0

    radius = int(w * 0.14)
    stroke = max(2, int(size * 0.024))

    # cerceve (siyah dolu yuvarlatilmis dikdortgen)
    d.rounded_rectangle((x0, y0, x1, y1), radius=radius, fill=BLACK)

    # ekran: ustte beyaz, altta gri — alt kose yuvarlamasi icin maske ile
    inner = (x0 + stroke, y0 + stroke, x1 - stroke, y1 - stroke)
    r_in = max(2, radius - stroke // 2)
    split = inner[1] + int((inner[3] - inner[1]) * 0.37)

    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(inner, radius=r_in, fill=255)
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    ld.rectangle((inner[0], inner[1], inner[2], split), fill=WHITE)
    ld.rectangle((inner[0], split, inner[2], inner[3]), fill=GRAY)
    img = Image.composite(Image.alpha_composite(img, layer), img, mask)

    d = ImageDraw.Draw(img)
    # hoparlor pilli (beyaz bolgenin ustunde, biraz sola)
    pill_w = int(w * 0.30)
    pill_h = max(2, int(h * 0.022))
    px = x0 + int(w * 0.30)
    py = y0 + int(h * 0.082)
    d.rounded_rectangle(
        (px, py, px + pill_w, py + pill_h), radius=pill_h // 2, fill=BLACK
    )
    # kamera noktasi (pillin saginda)
    dot_r = max(1, int(w * 0.032))
    cx = px + pill_w + int(w * 0.11)
    cy = py + pill_h // 2
    d.ellipse((cx - dot_r, cy - dot_r, cx + dot_r, cy + dot_r), fill=BLACK)

    return img


def save(img: Image.Image, *parts: str):
    path = os.path.join(ROOT, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print("yazildi:", os.path.relpath(path, ROOT))


def main():
    # uygulama ici logo (tam kare)
    save(draw_device(192, background=True, box=(0.33, 0.14, 0.68, 0.90)),
         "app", "src", "main", "res", "drawable", "ic_logo.png")

    # adaptive icon on plani: saydam zemin, cihaz guvenli bolgede (merkez ~66%)
    fg = draw_device(1024, background=False, box=(0.365, 0.20, 0.635, 0.80))
    save(fg, "app", "src", "main", "res", "drawable", "ic_launcher_foreground.png")

    # eski cihazlar / yedek: tam kare ikonlar
    for density, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        icon = draw_device(px, background=True, box=(0.33, 0.14, 0.68, 0.90))
        save(icon, "app", "src", "main", "res", f"mipmap-{density}", "ic_launcher.png")
        save(icon, "app", "src", "main", "res", f"mipmap-{density}", "ic_launcher_round.png")

    # README / magaza
    save(draw_device(512, background=True, box=(0.33, 0.14, 0.68, 0.90)), "docs", "icon-512.png")


if __name__ == "__main__":
    main()