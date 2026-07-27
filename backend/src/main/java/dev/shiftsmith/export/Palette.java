package dev.shiftsmith.export;

/**
 * The categorical palette shared by employees and positions, in OKLCH components.
 *
 * <p>A port of {@code frontend/src/theme.js} {@code colorAt}: hues step by the golden
 * angle so successive indices land as far apart as possible on the wheel, and after
 * each full turn the lightness/chroma move to a new band. Keep the two in lock-step —
 * a person's colour on paper has to be the colour on screen.
 *
 * <p>The frontend emits {@code oklch(L C H)} as CSS; here we hand Typst the raw
 * components, which it rebuilds with its own {@code oklch()}.
 */
public final class Palette {

    private static final double GOLDEN_ANGLE = 137.508;

    private Palette() {}

    public static ExportDocument.Color colorAt(int index) {
        int i = Math.max(0, index);
        double turn = i * GOLDEN_ANGLE;
        int lap = (int) Math.floor(turn / 360);
        return new ExportDocument.Color(
                round(0.62 + ((lap % 3) - 1) * 0.06, 3), // 0.56 / 0.62 / 0.68 bands
                round(0.13 + (lap % 2) * 0.025, 3),      // alternating chroma per lap
                round(turn % 360, 1));
    }

    /** Match the frontend's `toFixed`, so both sides emit bit-identical components. */
    private static double round(double v, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }
}
