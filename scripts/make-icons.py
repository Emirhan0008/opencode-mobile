# -*- coding: utf-8 -*-
"""OpenCode Mobile logo uretimi.

Tasarim: telefon cizimi + dikey "opencode" yazisi, ustte beyaz / altta gri bant.
Ciktilar:
  app/src/main/res/drawable/ic_launcher_foreground.png   (adaptive icon on plan, saydam)
  app/src/main/res/drawable/ic_logo.png                  (uygulama ici)
  app/src/main/res/mipmap-*/ic_launcher.png              (eski cihaz yedegi)
  app/src/main/res/mipmap-*/ic_launcher_round.png
  docs/icon-512.png                                      (README / magaza)

Kullanim: python scripts/make-icons.py
"""

import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

BLACK = (17, 17, 17, 255)
GRAY = (196, 196, 196, 255)
WHITE = (255, 255, 255, 255)


def load_font(size: int) -> ImageFont.FreeTypeFont:
    for name in ("consolab.ttf", "consola.ttf", "courbd.ttf", "DejaVuSansMono-Bold.ttf"):
        path = os.path.join(os.environ.get("WINDIR", "C:/Windows"), "Fonts", name)
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    raise SystemExit("Monospace font bulunamadi")


def rounded_rect(draw: ImageDraw.ImageDraw, box, radius, width, color):
    """Ici bos, kenarlari kalin yuvarlatilmis dikdortgen."""
    draw.rounded_rectangle(box, radius=radius, outline=color, width=width)


def draw_phone(d: ImageDraw.ImageDraw, x0, y0, x1, y1, stroke, dot_r, line_w, line_h, font):
    """Telefon + dikey opencode yazisi. (x0,y0,x1,y1) telefon kutusu."""
    radius = int((x1 - x0) * 0.13)
    rounded_rect(d, (x0, y0, x1, y1), radius, stroke, BLACK)

    cx = (x0 + x1) // 2
    # on kamera noktasi
    d.ellipse((cx - dot_r, y0 + int((y1 - y0) * 0.055) - dot_r,
               cx + dot_r, y0 + int((y1 - y0) * 0.055) + dot_r), fill=BLACK)
    # alt hoparlor cizgisi
    ly = y1 - int((y1 - y0) * 0.065)
    d.rounded_rectangle((cx - line_w // 2, ly - line_h // 2,
                         cx + line_w // 2, ly + line_h // 2), radius=line_h // 2, fill=BLACK)

    # dikey "opencode" yazisi (saat yonunde 90 derece: yukaridan asagi okunur)
    word = "opencode"
    target = int((y1 - y0) * 0.68)          # yazinin toplam uzunlugu
    size = int(target / (len(word) * 0.6))  # monospace: karakter genisligi ~0.6*boyut
    font = load_font(size)

    tmp = Image.new("RGBA", (target * 2, size * 3), (0, 0, 0, 0))
    td = ImageDraw.Draw(tmp)
    bbox = td.textbbox((0, 0), word, font=font)
    td.text((-bbox[0], -bbox[1]), word, font=font, fill=BLACK)
    text_img = tmp.crop((0, 0, bbox[2] - bbox[0], bbox[3] - bbox[1]))
    text_img = text_img.rotate(-90, expand=True)  # saat yonunde -> yukaridan asagi okunur

    tx = cx - text_img.width // 2
    ty = y0 + ((y1 - y0) - text_img.height) // 2
    return text_img, (tx, ty)


def compose(size: int, background: bool, band: bool, box=None):
    """Tam kompozisyonu uret.

    box: telefon kutusunun kesirsel koordinatlari (x0,y0,x1,y1).
    Varsayilan: logodaki tam kompozisyon. Adaptive on plani icin guvenli alan kullanilir.
    """
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0) if not background else WHITE)
    d = ImageDraw.Draw(img)

    if band:
        d.rectangle((0, int(size * 0.34), size, size), fill=GRAY)

    if box is None:
        box = (0.295, 0.17, 0.705, 0.90)

    x0 = int(size * box[0])
    y0 = int(size * box[1])
    x1 = int(size * box[2])
    y1 = int(size * box[3])

    stroke = max(2, int(size * 0.0135))
    dot_r = max(1, int(size * 0.0088))
    line_w = int(size * 0.075)
    line_h = max(2, int(size * 0.0085))

    text_img, (tx, ty) = draw_phone(d, x0, y0, x1, y1, stroke, dot_r, line_w, line_h, None)
    img.alpha_composite(text_img, (tx, ty))
    return img


def save(img: Image.Image, *parts: str):
    path = os.path.join(ROOT, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print("yazildi:", os.path.relpath(path, ROOT))


def main():
    # uygulama ici logo (tam kompozisyon)
    save(compose(192, background=True, band=True), "app", "src", "main", "res", "drawable", "ic_logo.png")

    # adaptive icon on plani: saydam zemin, telefon guvenli bolgede (merkez ~61%)
    fg = compose(1024, background=False, band=False, box=(0.33, 0.195, 0.67, 0.805))
    save(fg, "app", "src", "main", "res", "drawable", "ic_launcher_foreground.png")

    # eski cihazlar / yedek: tam kompozisyon kare ikonlar
    for density, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        icon = compose(px, background=True, band=True)
        save(icon, "app", "src", "main", "res", f"mipmap-{density}", "ic_launcher.png")
        save(icon, "app", "src", "main", "res", f"mipmap-{density}", "ic_launcher_round.png")

    # README / magaza
    save(compose(512, background=True, band=True), "docs", "icon-512.png")


if __name__ == "__main__":
    main()