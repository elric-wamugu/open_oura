# /// script
# requires-python = ">=3.10"
# dependencies = ["materialyoucolor==3.0.4"]
# ///
"""Generate the app's brand colour schemes from the teal seed.

Material 3 builds a whole scheme (primary/secondary/tertiary families, the five surface
containers, outlines, error, the fixed roles) from one seed colour through its HCT tonal
palettes. Running that here, once, and committing the result keeps the app free of a
runtime colour library and makes every shipped colour reviewable in a diff.

    uv run apps/android/gen-theme.py              # from the repo root
    uv run apps/android/gen-theme.py --variant tonal_spot

Writes:
  app/src/main/java/org/openoura/android/ui/theme/BrandSchemes.kt   BrandLight / BrandDark
  app/src/main/res/values/brand_colors.xml, values-night/...        window + widget XML

The variant defaults to Fidelity, which keeps the seed's own saturation, so the app still
reads as the dashboard's teal. Material You (the wallpaper toggle) uses the system's
TonalSpot scheme, so switching between the two stays a visible choice.
"""

import argparse
from pathlib import Path

from materialyoucolor.dynamiccolor.material_dynamic_colors import MaterialDynamicColors
from materialyoucolor.hct import Hct
from materialyoucolor.palettes.tonal_palette import TonalPalette
from materialyoucolor.scheme.scheme_fidelity import SchemeFidelity
from materialyoucolor.scheme.scheme_tonal_spot import SchemeTonalSpot
from materialyoucolor.scheme.scheme_vibrant import SchemeVibrant

SEED = "#0D9488"  # the dashboard's light-mode accent (styles.css --accent)
# Data colour families, kept outside the scheme so Material You never repaints them.
# warn is the dashboard's amber. High / Low / Normal follow the lab-report convention for
# a reading against its reference: red-ish high, blue low, green in range.
FAMILIES = {
    "Warn": "#C9842B",
    "High": "#E5533C",
    "Low": "#3D7EDB",
    "Normal": "#2F9E5A",
}
SPEC = "2025"  # the Material 3 Expressive colour spec

VARIANTS = {
    "fidelity": SchemeFidelity,
    "tonal_spot": SchemeTonalSpot,
    "vibrant": SchemeVibrant,
}

# Every role Compose's lightColorScheme()/darkColorScheme() takes, in its parameter order.
ROLES = [
    "primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "inversePrimary",
    "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
    "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
    "background", "onBackground", "surface", "onSurface", "surfaceVariant",
    "onSurfaceVariant", "surfaceTint", "inverseSurface", "inverseOnSurface",
    "error", "onError", "errorContainer", "onErrorContainer",
    "outline", "outlineVariant", "scrim",
    "surfaceBright", "surfaceContainer", "surfaceContainerHigh",
    "surfaceContainerHighest", "surfaceContainerLow", "surfaceContainerLowest", "surfaceDim",
    "primaryFixed", "primaryFixedDim", "onPrimaryFixed", "onPrimaryFixedVariant",
    "secondaryFixed", "secondaryFixedDim", "onSecondaryFixed", "onSecondaryFixedVariant",
    "tertiaryFixed", "tertiaryFixedDim", "onTertiaryFixed", "onTertiaryFixedVariant",
]

# Only what the XML side uses: the launch window background (surface) and the widget
# placeholder and picker previews, which are drawn before or outside any Compose theme.
# Glance's Material 3 widget background is secondaryContainer with its tone nudged; the
# previews approximate it with the role itself, and their headline pill is primaryContainer.
XML_ROLES = [
    "surface",
    "secondaryContainer", "onSecondaryContainer",
    "primaryContainer", "onPrimaryContainer",
]


def snake(name: str) -> str:
    return "".join("_" + ch.lower() if ch.isupper() else ch for ch in name)


def scheme_colors(seed: str, variant: str, dark: bool) -> dict[str, int]:
    hct = Hct.from_int(int("FF" + seed.lstrip("#"), 16))
    scheme = VARIANTS[variant](hct, dark, 0.0, spec_version=SPEC)
    roles = MaterialDynamicColors(spec=SPEC)
    out = {}
    for role in ROLES:
        dc = getattr(roles, role, None)
        # The 2025 spec folds background into surface; fall back so every Compose role
        # is still set explicitly rather than left to the library's baseline purple.
        if dc is None:
            dc = getattr(roles, {"background": "surface", "onBackground": "onSurface"}[role])
        out[role] = dc.get_argb(scheme) & 0xFFFFFFFF
    return out


# Material's tone pairing for a custom colour family: the colour, what sits on it, a
# container, and what sits on the container — the same pairing as the scheme's own roles.
FAMILY_TONES = {
    "light": {"color": 40, "onColor": 100, "container": 90, "onContainer": 10},
    "dark": {"color": 80, "onColor": 20, "container": 30, "onContainer": 90},
}


# Chroma cap for the families. At full saturation a tone-90 green container is close to
# neon next to the teal scheme; capped, the pills read as calm, distinct tints.
FAMILY_MAX_CHROMA = 36.0


def family_colors(hexcode: str, mode: str) -> dict[str, int]:
    hct = Hct.from_int(int("FF" + hexcode.lstrip("#"), 16))
    palette = TonalPalette.from_hue_and_chroma(hct.hue, min(hct.chroma, FAMILY_MAX_CHROMA))
    return {k: palette.tone(t) & 0xFFFFFFFF for k, t in FAMILY_TONES[mode].items()}


def kotlin(light: dict, dark: dict, seed: str, variant: str) -> str:
    def block(name: str, fn: str, colors: dict) -> str:
        body = "\n".join(f"    {r} = Color(0x{colors[r]:08X})," for r in ROLES)
        return f"val {name} = {fn}(\n{body}\n)\n"

    def family(name: str, seed_hex: str, mode: str) -> str:
        body = "\n".join(f"    {k} = Color(0x{v:08X})," for k, v in family_colors(seed_hex, mode).items())
        return f"val {name} = ToneFamily(\n{body}\n)\n"

    families = "".join(
        f"\n// {fam} family from {hexcode}.\n"
        + family(f"{fam}Light", hexcode, "light")
        + "\n"
        + family(f"{fam}Dark", hexcode, "dark")
        for fam, hexcode in FAMILIES.items()
    )

    return (
        "// Generated by apps/android/gen-theme.py — do not edit by hand; re-run it.\n"
        f"// Seed {seed}, {variant} variant, Material colour spec {SPEC}.\n"
        "package org.openoura.android.ui.theme\n\n"
        "import androidx.compose.material3.darkColorScheme\n"
        "import androidx.compose.material3.lightColorScheme\n"
        "import androidx.compose.ui.graphics.Color\n\n"
        + block("BrandLight", "lightColorScheme", light)
        + "\n"
        + block("BrandDark", "darkColorScheme", dark)
        + families
    )


def xml(colors: dict, seed: str, variant: str) -> str:
    rows = "\n".join(
        f'    <color name="brand_{snake(r)}">#{colors[r] & 0xFFFFFF:06X}</color>' for r in XML_ROLES
    )
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!-- Generated by apps/android/gen-theme.py (seed {seed}, {variant}). Re-run it. -->\n"
        f"<resources>\n{rows}\n</resources>\n"
    )


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--seed", default=SEED)
    ap.add_argument("--variant", default="fidelity", choices=sorted(VARIANTS))
    args = ap.parse_args()

    app = Path(__file__).resolve().parent / "app" / "src" / "main"
    light = scheme_colors(args.seed, args.variant, dark=False)
    dark = scheme_colors(args.seed, args.variant, dark=True)

    kt = app / "java/org/openoura/android/ui/theme/BrandSchemes.kt"
    kt.write_text(kotlin(light, dark, args.seed, args.variant))
    for folder, colors in (("values", light), ("values-night", dark)):
        out = app / "res" / folder / "brand_colors.xml"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(xml(colors, args.seed, args.variant))
    print(f"wrote {kt.name} and brand_colors.xml (seed {args.seed}, {args.variant})")


if __name__ == "__main__":
    main()
