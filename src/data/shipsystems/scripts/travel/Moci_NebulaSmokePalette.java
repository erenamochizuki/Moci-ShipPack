package data.shipsystems.scripts.travel;

import java.awt.Color;
import java.util.Arrays;
import java.util.Objects;

final class Moci_NebulaSmokePalette {
    private final Color[][] tones;

    Moci_NebulaSmokePalette(Color... bases) {
        tones = bases == null ? new Color[0][] : Arrays.stream(bases)
                .filter(Objects::nonNull)
                .map(base -> new Color[]{toward(base, 0, .30f, .88f), base, toward(base, 255, .20f, 1.10f)})
                .toArray(Color[][]::new);
    }

    boolean isEmpty() {
        return tones.length == 0;
    }

    Color sample(float colorBand, float angleFromSeed) {
        if (isEmpty()) return null;
        int band = Math.floorMod((int) Math.floor(colorBand * .25f * tones.length), tones.length);
        double halfTurns = angleFromSeed / Math.PI;
        double distanceFromShadow = Math.abs(halfTurns - Math.rint(halfTurns));
        int tone = distanceFromShadow < .15 ? 0 : distanceFromShadow < .35 ? 1 : 2;
        return tones[band][tone];
    }

    private static Color toward(Color base, int target, float mix, float alphaScale) {
        return new Color(mixChannel(base.getRed(), target, mix), mixChannel(base.getGreen(), target, mix),
                mixChannel(base.getBlue(), target, mix), channel(base.getAlpha() * alphaScale));
    }

    private static int mixChannel(int base, int target, float mix) {
        return channel(base + (target - base) * mix);
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }
}
