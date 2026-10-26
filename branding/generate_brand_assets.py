#!/usr/bin/env python3
"""Build the biliTv logo family from the selected transparent master artwork."""

from __future__ import annotations

from pathlib import Path
import zipfile

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "source" / "biliTv-mark-master.png"
EXPORTS = ROOT / "exports"
PROJECT = ROOT.parent
ANDROID_RES = PROJECT / "app" / "src" / "main" / "res"

BLUE = "#00A1D6"
PINK = "#FB7299"
CREAM = "#FFF8F4"
INK = "#173C50"
NAVY = "#102B38"
NAVY_HI = "#143C50"


def load_master() -> Image.Image:
    if not SOURCE.is_file():
        raise FileNotFoundError(f"Missing selected master artwork: {SOURCE}")
    return Image.open(SOURCE).convert("RGBA")


def transparent_mark(size: int, master: Image.Image) -> Image.Image:
    return master.resize((size, size), Image.Resampling.LANCZOS)


def launcher_icon(size: int, master: Image.Image) -> Image.Image:
    canvas = Image.new("RGBA", (size, size), CREAM)
    canvas.alpha_composite(transparent_mark(size, master))
    return canvas


def adaptive_foreground(size: int, master: Image.Image) -> Image.Image:
    """Keep the curled tail and paws inside the Android adaptive-icon safe area."""
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    mark_size = round(size * 0.80)
    mark = transparent_mark(mark_size, master)
    canvas.alpha_composite(mark, ((size - mark_size) // 2, (size - mark_size) // 2))
    return canvas


def font_for(size: int) -> ImageFont.FreeTypeFont:
    candidates = [
        "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
        "/System/Library/Fonts/Avenir Next.ttc",
        "/System/Library/Fonts/HelveticaNeue.ttc",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf",
    ]
    for candidate in candidates:
        if Path(candidate).is_file():
            try:
                return ImageFont.truetype(candidate, size=size, index=1 if candidate.endswith(".ttc") else 0)
            except (OSError, IndexError):
                try:
                    return ImageFont.truetype(candidate, size=size)
                except OSError:
                    pass
    return ImageFont.load_default()


def draw_wordmark(
    draw: ImageDraw.ImageDraw,
    x: int,
    baseline: int,
    font_size: int,
    scale: int,
    light: bool = False,
) -> None:
    font = font_for(font_size * scale)
    prefix_color = CREAM if light else BLUE
    x_px = x * scale
    baseline_px = baseline * scale
    draw.text((x_px, baseline_px), "bili", fill=prefix_color, font=font, anchor="ls")
    prefix_width = draw.textlength("bili", font=font)
    draw.text((round(x_px + prefix_width), baseline_px), "Tv", fill=PINK, font=font, anchor="ls")


def render_logo(size: tuple[int, int], master: Image.Image) -> Image.Image:
    width, height = size
    scale = 3
    image = Image.new("RGBA", (width * scale, height * scale), (0, 0, 0, 0))
    mark_size = round(height * 0.96)
    mark = transparent_mark(mark_size * scale, master)
    y = round((height - mark_size) * 0.5 * scale)
    image.alpha_composite(mark, (round(width * 0.015 * scale), y))
    draw = ImageDraw.Draw(image)
    draw_wordmark(
        draw,
        round(width * 0.34),
        round(height * 0.64),
        round(height * 0.47),
        scale,
    )
    return image.resize(size, Image.Resampling.LANCZOS)


def render_banner(size: tuple[int, int], master: Image.Image) -> Image.Image:
    width, height = size
    scale = 2
    out_size = (width * scale, height * scale)
    image = Image.new("RGB", out_size)
    pixels = image.load()
    start = tuple(int(NAVY[i : i + 2], 16) for i in (1, 3, 5))
    end = tuple(int(NAVY_HI[i : i + 2], 16) for i in (1, 3, 5))
    background_draw = ImageDraw.Draw(image)
    for x in range(out_size[0]):
        t = x / max(1, out_size[0] - 1)
        color = tuple(round(a + (b - a) * t) for a, b in zip(start, end))
        background_draw.line((x, 0, x, out_size[1]), fill=color)

    draw = ImageDraw.Draw(image)
    panel = round(height * 0.76 * scale)
    panel_x = round(width * 0.035 * scale)
    panel_y = (out_size[1] - panel) // 2
    draw.ellipse((panel_x, panel_y, panel_x + panel, panel_y + panel), fill="#173C50")

    mark_size = round(height * 0.82 * scale)
    mark = transparent_mark(mark_size, master)
    mark_x = round(width * 0.035 * scale)
    mark_y = (out_size[1] - mark_size) // 2
    image_rgba = image.convert("RGBA")
    image_rgba.alpha_composite(mark, (mark_x, mark_y))
    image = image_rgba.convert("RGB")

    draw = ImageDraw.Draw(image)
    draw_wordmark(
        draw,
        round(width * 0.51),
        round(height * 0.61),
        round(height * 0.255),
        scale,
        light=True,
    )
    return image.resize(size, Image.Resampling.LANCZOS)


def save(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, "PNG", optimize=True)


def write_android_resources(master: Image.Image) -> None:
    densities = {
        "ldpi": 36,
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192,
    }
    for density, size in densities.items():
        save(launcher_icon(size, master), ANDROID_RES / f"mipmap-{density}" / "ic_launcher.png")

    save(
        adaptive_foreground(432, master),
        ANDROID_RES / "drawable-xxxhdpi" / "ic_launcher_foreground.png",
    )
    save(render_banner((320, 180), master), ANDROID_RES / "drawable-nodpi" / "tv_banner.png")
    save(render_logo((600, 200), master), ANDROID_RES / "drawable-nodpi" / "bilitv_logo.png")

    adaptive_xml = ANDROID_RES / "mipmap-anydpi-v26" / "ic_launcher.xml"
    adaptive_xml.parent.mkdir(parents=True, exist_ok=True)
    adaptive_xml.write_text(
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/launcher_icon_background" />\n'
        '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
        '</adaptive-icon>\n',
        encoding="utf-8",
    )

    # Remove resources generated by the superseded icon concept.
    for path in (
        ANDROID_RES / "drawable" / "ic_launcher.xml",
        ANDROID_RES / "drawable" / "ic_launcher_foreground.xml",
    ):
        path.unlink(missing_ok=True)


def write_exports(master: Image.Image) -> None:
    EXPORTS.mkdir(parents=True, exist_ok=True)
    for path in EXPORTS.glob("*.png"):
        path.unlink()

    for size in (1024, 512, 256, 128):
        save(transparent_mark(size, master), EXPORTS / f"biliTv-mark-{size}.png")
    for size in (512, 192, 144, 96, 72, 48, 36):
        save(launcher_icon(size, master), EXPORTS / f"ic_launcher-{size}.png")
    for dims in ((1200, 400), (600, 200), (300, 100)):
        save(render_logo(dims, master), EXPORTS / f"biliTv-logo-{dims[0]}x{dims[1]}.png")
    for dims in ((1280, 720), (640, 360), (320, 180)):
        save(render_banner(dims, master), EXPORTS / f"biliTv-tv-banner-{dims[0]}x{dims[1]}.png")


def write_archive() -> None:
    archive = ROOT / "biliTv-brand-assets.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as bundle:
        for path in (ROOT / "README.md", ROOT / "source" / SOURCE.name, ROOT / "generate_brand_assets.py"):
            bundle.write(path, f"biliTv-brand-assets/{path.relative_to(ROOT)}")
        for path in sorted(EXPORTS.glob("*.png")):
            bundle.write(path, f"biliTv-brand-assets/exports/{path.name}")


def main() -> None:
    master = load_master()
    write_exports(master)
    write_android_resources(master)
    for path in ROOT.glob("*.svg"):
        path.unlink()
    write_archive()
    print(f"Generated {len(list(EXPORTS.glob('*.png')))} PNG exports and Android resources from {SOURCE.name}.")


if __name__ == "__main__":
    main()
