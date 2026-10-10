package com.lumira.plugins.diffuseavatar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DiffuseAvatarColorSpaceTest {
    @Test
    void convertsReferenceRedAndNeutralGrayIntoLinearSrgb() {
        double[] red =
                DiffuseAvatarGenerator.oklchToLinearSrgb(0.6279553606, 0.2576833077, 29.2338851923);

        assertEquals(1.0, red[0], 1e-6);
        assertEquals(0.0, red[1], 1e-6);
        assertEquals(0.0, red[2], 1e-6);
        assertTrue(DiffuseAvatarGenerator.isInSrgbGamut(red));

        double[] gray = DiffuseAvatarGenerator.oklchToLinearSrgb(0.5, 0, 213);
        assertEquals(0.125, gray[0], 1e-8);
        assertEquals(0.125, gray[1], 1e-8);
        assertEquals(0.125, gray[2], 1e-8);
    }

    @Test
    void mapsOutOfGamutColorsByReducingChromaUntilInGamut() {
        double lightness = 0.72;
        double chroma = 0.4;
        double hue = 250;
        double[] raw = DiffuseAvatarGenerator.oklchToLinearSrgbUnchecked(lightness, chroma, hue);

        assertFalse(DiffuseAvatarGenerator.isInSrgbGamut(raw));
        double mappedChroma =
                DiffuseAvatarGenerator.reduceChromaToSrgbGamut(lightness, chroma, hue);
        double[] mapped = DiffuseAvatarGenerator.oklchToLinearSrgb(lightness, chroma, hue);

        assertTrue(mappedChroma > 0 && mappedChroma < chroma);
        assertTrue(DiffuseAvatarGenerator.isInSrgbGamut(mapped));
    }

    @Test
    void samplesContinuousHuesAcrossTheFullCircleAndVariesLightnessAndChroma() {
        int[] hueBins = new int[36];
        Set<Long> distinctHueValues = new HashSet<>();
        double minLightness = Double.POSITIVE_INFINITY;
        double maxLightness = Double.NEGATIVE_INFINITY;
        double minChroma = Double.POSITIVE_INFINITY;
        double maxChroma = Double.NEGATIVE_INFINITY;

        for (long seed = 0; seed < 128; seed++) {
            double[][] palette = DiffuseAvatarGenerator.generateOklchPalette(seed);
            assertEquals(7, palette.length);
            for (int color = 1; color < palette.length; color++) {
                double lightness = palette[color][0];
                double chroma = palette[color][1];
                double hue = palette[color][2];
                assertTrue(hue >= 0 && hue < 360);
                hueBins[(int) hue / 10]++;
                distinctHueValues.add(Math.round(hue * 1000));
                minLightness = Math.min(minLightness, lightness);
                maxLightness = Math.max(maxLightness, lightness);
                minChroma = Math.min(minChroma, chroma);
                maxChroma = Math.max(maxChroma, chroma);
                assertTrue(
                        DiffuseAvatarGenerator.isInSrgbGamut(
                                DiffuseAvatarGenerator.oklchToLinearSrgb(lightness, chroma, hue)));
            }
        }

        long coveredHueBins = java.util.Arrays.stream(hueBins).filter(count -> count > 0).count();
        assertTrue(coveredHueBins >= 34);
        assertTrue(distinctHueValues.size() > 700);
        assertTrue(minLightness < 0.70 && maxLightness > 0.81);
        assertTrue(minChroma < 0.15 && maxChroma > 0.23);
    }
}
