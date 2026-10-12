#!/usr/bin/env python3
"""Generate Telegram Bot icons for WM Keyboard.

Shares geometry, palette, and safe-zone scale with `gen_icon.py` and `gen_addon_icon.py`.
Supports multiple variations, vector SVG output, and Telegram circle-crop avatar simulation.

Usage:
    python3 tools/icon/gen_telegram_bot_icon.py --variant plane_brand --out icon_plane.png
    python3 tools/icon/gen_telegram_bot_icon.py --variant plane_brand --circle --out avatar.png
    python3 tools/icon/gen_telegram_bot_icon.py --list-variants
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import tempfile
from PIL import Image, ImageDraw

import gen_icon as base
from gen_icon import C, f

WM_PATH = (
    "M45.63,39.30 L40.06,35.51V26.13L42.81 28.36V34.05L45.51 35.86V28.50"
    "L48.33 26.51V35.89L51.13 34.02V28.28L53.95 26.09V35.45L48.26 39.30Z"
    "M54.79,27.09H66.77Q67.94 27.38 67.94 28.59V36.68L64.55 38.76V29.58"
    "H63.13V36.55L59.60 38.90V29.58H58.18V38.49L54.79 40.71Z"
)

# ---------------------------------------------------------------- Official Telegram Plane Paths
# p1: under-fuselage keel & lower wing
# p2: center fold shadow
# p3: main upper wing with smooth aerodynamic leading edge & intact curved nose
P1_KEEL = (
    "M81.229,128.772l14.237,39.406s1.78,3.687,3.686,3.687,30.255-29.492,30.255-29.492"
    "l31.525-60.89L81.737,118.6Z"
)
P2_FOLD = (
    "M100.106,138.878l-2.733,29.046s-1.144,8.9,7.754,0,17.415-15.763,17.415-15.763"
)
P3_WING = (
    "M81.486,130.178,52.2,120.636s-3.5-1.42-2.373-4.64c.232-.664.7-1.229,2.1-2.2,"
    "6.489-4.523,120.106-45.36,120.106-45.36s3.208-1.081,5.1-.362a2.766,2.766,0,0,1,1.885,2.055,"
    "9.357,9.357,0,0,1,.254,2.585c-.009.752-.1,1.449-.169,2.542-.692,11.165-21.4,94.493-21.4,94.493"
    "s-1.239,4.876-5.678,5.043A8.13,8.13,0,0,1,146.1,172.5c-8.711-7.493-38.819-27.727-45.472-32.177"
    "a1.27,1.27,0,0,1-.546-.9c-.093-.469.417-1.05.417-1.05s52.426-46.6,53.821-51.492"
    "c.108-.379-.3-.566-.848-.4-3.482,1.281-63.844,39.4-70.506,43.607A3.21,3.21,0,0,1,81.486,130.178Z"
)

PLANE_ORIG_W = 129.445
PLANE_ORIG_CX = 114.55
PLANE_ORIG_CY = 121.11

# ---------------------------------------------------------------- Insignias

def circle(cx: float, cy: float, r: float) -> str:
    return (f"M{f(cx - r)},{f(cy)} A{f(r)},{f(r)} 0 0 1 {f(cx + r)},{f(cy)} "
            f"A{f(r)},{f(r)} 0 0 1 {f(cx - r)},{f(cy)} Z")

def telegram_plane_badge(
    target_w: float = 21.0,
    cx: float = C,
    cy: float = base.PIN_CY,
    fill: str = "url(#wmg)",
    slit_stroke: str = base.PIN_FILL,
    slit_width: float = 2.4,
) -> str:
    """Sleek silhouette Telegram plane with aerodynamic straight leading edge and white slit divider."""
    scale = target_w / PLANE_ORIG_W
    tx = cx - PLANE_ORIG_CX * scale
    ty = cy - PLANE_ORIG_CY * scale
    return (
        f'<g transform="translate({tx:.4f},{ty:.4f}) scale({scale:.4f})">'
        f'<path d="{P1_KEEL}" fill="{fill}"/>'
        f'<path d="{P3_WING}" fill="{fill}" stroke="{slit_stroke}" stroke-width="{slit_width:.2f}" stroke-linejoin="round"/>'
        f'</g>'
    )

def telegram_plane_faceted(target_w: float = 21.0, cx: float = C, cy: float = base.PIN_CY, theme: str = "brand") -> str:
    """Official 3-facet origami folded Telegram plane with smooth leading edge."""
    scale = target_w / PLANE_ORIG_W
    tx = cx - PLANE_ORIG_CX * scale
    ty = cy - PLANE_ORIG_CY * scale

    if theme == "brand":
        c_keel = "url(#wmg)"
        c_fold = "#6D28D9"
        c_wing = "url(#wmg)"
        op_keel = ' opacity="0.6"'
        op_fold = ' opacity="0.85"'
    elif theme == "cyan":
        c_keel = "#0091EA"
        c_fold = "#01579B"
        c_wing = "#0288D1"
        op_keel = ' opacity="0.75"'
        op_fold = ' opacity="0.9"'
    else:
        c_keel, c_fold, c_wing = "#A78BFA", "#7C3AED", "url(#wmg)"
        op_keel, op_fold = "", ""

    return (
        f'<g transform="translate({tx:.4f},{ty:.4f}) scale({scale:.4f})">'
        f'<path d="{P1_KEEL}" fill="{c_keel}"{op_keel}/>'
        f'<path d="{P2_FOLD}" fill="{c_fold}"{op_fold}/>'
        f'<path d="{P3_WING}" fill="{c_wing}"/>'
        f'</g>'
    )

def robot_head_badge(cx: float = C, cy: float = base.PIN_CY) -> str:
    hw, hh, hr = 21.0, 14.5, 4.0
    hx, hy = cx - hw / 2, cy - hh / 2 + 1.2
    stalk = f"M{f(cx - 0.7)},{f(hy - 2.8)} h1.4 v2.8 h-1.4 Z"
    ant_ball = circle(cx, hy - 3.8, 1.4)
    ear_l = base.rrect(hx - 1.8, cy - 1.2, 1.8, 4.8, 0.9)
    ear_r = base.rrect(hx + hw, cy - 1.2, 1.8, 4.8, 0.9)
    head = base.rrect(hx, hy, hw, hh, hr)
    eye_r = 1.8
    eye_y = cy + 0.2
    eye_l = circle(cx - 4.8, eye_y, eye_r)
    eye_r_c = circle(cx + 4.8, eye_y, eye_r)
    mouth = f'<path d="M{f(cx - 3.2)},{f(cy + 4.2)} Q{f(cx)},{f(cy + 6.4)} {f(cx + 3.2)},{f(cy + 4.2)}" stroke="{base.PIN_FILL}" stroke-width="1.3" stroke-linecap="round" fill="none"/>'
    body_paths = f"{stalk} {ant_ball} {ear_l} {ear_r} {head}"
    return (f'<g>'
            f'<path d="{body_paths}" fill="url(#wmg)"/>'
            f'<path d="{eye_l} {eye_r_c}" fill="{base.PIN_FILL}"/>'
            f'{mouth}'
            f'</g>')

def terminal_prompt_badge(cx: float = C, cy: float = base.PIN_CY) -> str:
    slash = f'<path d="M{f(cx - 6.5)},{f(cy + 5.5)} L{f(cx - 1.5)},{f(cy - 6.0)}" stroke="url(#wmg)" stroke-width="2.6" stroke-linecap="round"/>'
    chevron = f'<path d="M{f(cx + 1.5)},{f(cy - 5.0)} L{f(cx + 7.0)},{f(cy)} L{f(cx + 1.5)},{f(cy + 5.0)}" stroke="url(#wmg)" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" fill="none"/>'
    return f'<g>{slash}{chevron}</g>'

def bot_antenna_pin_path(grow: float = 0.0) -> str:
    p_path = base.pin_path(grow)
    ant_w, ant_h = 2.4, 4.0
    ant_l_x = C - base.PIN_W / 2 + 4.0 - grow
    ant_r_x = C + base.PIN_W / 2 - 4.0 - ant_w + grow
    ant_y = base.PIN_TOP - ant_h - grow
    ant_l = base.rrect(ant_l_x, ant_y, ant_w, ant_h + 1, 1.2)
    ant_r = base.rrect(ant_r_x, ant_y, ant_w, ant_h + 1, 1.2)
    ant_ball_l = circle(ant_l_x + ant_w / 2, ant_y - 0.8, 1.6)
    ant_ball_r = circle(ant_r_x + ant_w / 2, ant_y - 0.8, 1.6)
    return f"{p_path} {ant_l} {ant_r} {ant_ball_l} {ant_ball_r}"

# ---------------------------------------------------------------- SVG Assembly

def build_foreground_svg(
    badge_markup: str,
    kb_top: str = base.KB_TOP,
    kb_bottom: str = base.KB_BOTTOM,
    brand_a: str = base.BRAND_A,
    brand_b: str = base.BRAND_B,
    custom_pin_path: str | None = None,
    custom_pin_halo: str | None = None,
    custom_pin_fill: str = base.PIN_FILL,
    extra_fg: str = "",
) -> str:
    s = base.lockup_scale()
    keys = "".join(f'<path d="{d}"/>' for d in base.key_paths())
    pin_p = custom_pin_path if custom_pin_path else base.pin_path()
    halo_p = custom_pin_halo if custom_pin_halo else base.pin_path(base.PIN_HALO)

    mask = (f'<mask id="kb"><rect width="108" height="108" fill="black"/>'
            f'<path d="{base.rrect(base.KB_X, base.KB_Y, base.KB_W, base.KB_H, base.KB_R)}" '
            f'fill="white"/>'
            f'<g fill="black" fill-opacity="0.5">{keys}</g>'
            f'<path d="{halo_p}" fill="black"/></mask>')

    inner = (f'<g filter="url(#sh)">'
             f'<rect width="108" height="108" fill="url(#kbg)" mask="url(#kb)"/>'
             f'<path d="{pin_p}" fill="{custom_pin_fill}"/></g>'
             f'{badge_markup}{extra_fg}')

    defs = (base.SHADOW_FILTER + mask + base.gradient("kbg", kb_top, kb_bottom)
            + base.gradient("wmg", brand_a, brand_b))

    return base.svg(
        f'<g transform="translate({C},{C}) scale({s:.4f}) translate({-C},{-C})">{inner}</g>', defs)

def build_background_svg(bg_top: str = base.BG_TOP, bg_bottom: str = base.BG_BOTTOM) -> str:
    return base.svg('<rect width="108" height="108" fill="url(#bg)"/>', base.gradient("bg", bg_top, bg_bottom))

VARIANTS = {
    "plane_brand": "Clean Telegram Paper Plane with divider slit in Brand Gradient (Default)",
    "faceted_brand": "Multi-Facet Origami Telegram Plane in Brand Gradient",
    "plane_cyan": "Telegram Cyan Paper Plane in Pin",
    "bot_robot_head": "Modern Friendly Robot/Bot Head in Pin",
    "wm_with_badge": "Original WM Monogram + Telegram Shoulder Badge",
    "wm_bot_antenna": "Original WM Monogram + Robot Antenna Pin",
    "terminal_bot": "Command Prompt (/>) Badge in Pin",
    "telegram_blue_theme": "Telegram Blue Background + Dark Indigo Keyboard",
    "telegram_light_glass": "Telegram Blue Background + Frosted White Keyboard",
}

def generate_variant(variant: str) -> tuple[str, str]:
    std_bg = build_background_svg()

    if variant == "plane_brand":
        return std_bg, build_foreground_svg(telegram_plane_badge(target_w=21.0))
    elif variant == "faceted_brand":
        return std_bg, build_foreground_svg(telegram_plane_faceted(target_w=21.0, theme="brand"))
    elif variant == "plane_cyan":
        return std_bg, build_foreground_svg(telegram_plane_faceted(target_w=21.0, theme="cyan"))
    elif variant == "bot_robot_head":
        return std_bg, build_foreground_svg(robot_head_badge())
    elif variant == "wm_with_badge":
        wm_badge = f'<path d="{WM_PATH}" fill="url(#wmg)"/>'
        badge_circle = circle(73, 22, 9.0)
        badge_halo = circle(73, 22, 10.6)
        scale_b = 10.5 / PLANE_ORIG_W
        tx = 73 - PLANE_ORIG_CX * scale_b
        ty = 22 - PLANE_ORIG_CY * scale_b
        float_badge = (
            f'<g filter="url(#sh)">'
            f'<path d="{badge_halo}" fill="#191C42"/>'
            f'<path d="{badge_circle}" fill="#24A1DE"/>'
            f'<g transform="translate({tx:.2f},{ty:.2f}) scale({scale_b:.4f})">'
            f'<path d="{P1_KEEL}" fill="#FFFFFF" opacity="0.85"/>'
            f'<path d="{P3_WING}" fill="#FFFFFF" stroke="#24A1DE" stroke-width="2.2" stroke-linejoin="round"/>'
            f'</g></g>'
        )
        return std_bg, build_foreground_svg(wm_badge, extra_fg=float_badge)
    elif variant == "wm_bot_antenna":
        wm_badge = f'<path d="{WM_PATH}" fill="url(#wmg)"/>'
        return std_bg, build_foreground_svg(
            badge_markup=wm_badge,
            custom_pin_path=bot_antenna_pin_path(),
            custom_pin_halo=bot_antenna_pin_path(base.PIN_HALO),
        )
    elif variant == "terminal_bot":
        return std_bg, build_foreground_svg(terminal_prompt_badge())
    elif variant == "telegram_blue_theme":
        bg = build_background_svg(bg_top="#24A1DE", bg_bottom="#0D78B2")
        fg = build_foreground_svg(
            badge_markup=telegram_plane_badge(target_w=21.0),
            kb_top="#2A3066",
            kb_bottom="#181B3E",
            brand_a="#24A1DE",
            brand_b="#0088CC",
        )
        return bg, fg
    elif variant == "telegram_light_glass":
        bg = build_background_svg(bg_top="#24A1DE", bg_bottom="#0D78B2")
        fg = build_foreground_svg(
            badge_markup=telegram_plane_badge(target_w=21.0, fill="#24A1DE"),
            kb_top="#FFFFFF",
            kb_bottom="#E1F0FA",
            brand_a="#24A1DE",
            brand_b="#0088CC",
        )
        return bg, fg
    else:
        raise ValueError(f"Unknown variant: {variant}")

def main() -> None:
    ap = argparse.ArgumentParser(description="Render Telegram Bot icon for WM Keyboard.")
    ap.add_argument("--variant", default="plane_brand", choices=list(VARIANTS.keys()))
    ap.add_argument("--out", help="Output PNG file path (default: wmkeyboard_tg_bot_{variant}.png)")
    ap.add_argument("--size", type=int, default=512, help="Icon size in pixels (default: 512)")
    ap.add_argument("--circle", action="store_true", help="Apply circular avatar mask (for Telegram avatar)")
    ap.add_argument("--svg-out", help="Optional output SVG file path")
    ap.add_argument("--list-variants", action="store_true", help="List all available variants")
    args = ap.parse_args()

    if args.list_variants:
        print("Available variations:")
        for k, v in VARIANTS.items():
            print(f"  {k:22}: {v}")
        return

    base.need("rsvg-convert")

    bg_svg, fg_svg = generate_variant(args.variant)

    if args.svg_out:
        with open(args.svg_out, "w") as fh:
            fh.write(f'<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">{bg_svg}{fg_svg}</svg>')
        print(f"wrote SVG: {args.svg_out}")

    out_file = args.out or f"wmkeyboard_tg_bot_{args.variant}.png"

    with tempfile.TemporaryDirectory() as tmp:
        bg_f = base.render(bg_svg, args.size, tmp, "bg")
        fg_f = base.render(fg_svg, args.size, tmp, "fg")
        im_bg = Image.open(bg_f).convert("RGBA")
        im_fg = Image.open(fg_f).convert("RGBA")
        composite = Image.alpha_composite(im_bg, im_fg)

        if args.circle:
            mask = Image.new("L", (args.size, args.size), 0)
            draw = ImageDraw.Draw(mask)
            draw.ellipse((0, 0, args.size - 1, args.size - 1), fill=255)
            out_img = Image.new("RGBA", (args.size, args.size), (0, 0, 0, 0))
            out_img.paste(composite, (0, 0), mask)
            draw_circle = ImageDraw.Draw(out_img)
            draw_circle.ellipse((1, 1, args.size - 2, args.size - 2), outline=(255, 255, 255, 60), width=2)
            out_img.save(out_file)
        else:
            composite.save(out_file)

    print(f"Wrote {out_file} ({args.size}x{args.size}px, variant='{args.variant}', circle={args.circle})")

if __name__ == "__main__":
    main()
