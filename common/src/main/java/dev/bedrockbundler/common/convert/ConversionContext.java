package dev.bedrockbundler.common.convert;

import dev.bedrockbundler.common.ConversionLog;
import dev.bedrockbundler.common.bedrock.BedrockPack;
import dev.bedrockbundler.common.bedrock.Textures;
import dev.bedrockbundler.common.java.JavaPack;
import dev.bedrockbundler.common.java.ModelResolver;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Shared state of one conversion run. */
public record ConversionContext(
        JavaPack javaPack,
        ModelResolver models,
        Textures textures,
        BedrockPack bedrockPack,
        ConversionLog log,
        Options options
) {

    /**
     * @param iconSize size in pixels of inventory icons rendered for 3D items
     * @param fonts    convert font images (emojis/icons) to Bedrock glyph sheets
     * @param fontCellUnits Java font pixels that one Bedrock glyph cell stands for; higher makes glyphs smaller
     * @param guiOffsetX    horizontal position of GUI background glyphs relative to the title, in Java pixels
     *                      (ItemsAdder's {@code x_position_pixels}, -16 by default)
     * @param furnitureDisplay Java display context furniture models are drawn with: {@code fixed} for ItemsAdder,
     *                         {@code none} for plugins whose item displays use no display transform (Nexo's default)
     * @param guiOffsets    per GUI glyph (code point) horizontal position, overriding {@code guiOffsetX}: the
     *                      shift a content plugin's menu titles put in front of that glyph
     */
    public record Options(int iconSize, boolean fonts, int fontCellUnits, int guiOffsetX, String furnitureDisplay,
                          Map<Integer, Integer> guiOffsets) {

        public static final Options DEFAULT = new Options(64, true, 32, -16, "fixed", Map.of());

        public Options {
            // Sorted, so the options (part of the bundle hash) print the same every time.
            guiOffsets = Collections.unmodifiableMap(new TreeMap<>(guiOffsets == null ? Map.of() : guiOffsets));
        }

        public Options(int iconSize, boolean fonts, int fontCellUnits, int guiOffsetX) {
            this(iconSize, fonts, fontCellUnits, guiOffsetX, "fixed", Map.of());
        }

        public Options withFurnitureDisplay(String context) {
            return new Options(iconSize, fonts, fontCellUnits, guiOffsetX, context, guiOffsets);
        }

        public Options withGuiOffsets(int defaultOffsetX, Map<Integer, Integer> offsets) {
            return new Options(iconSize, fonts, fontCellUnits, defaultOffsetX, furnitureDisplay, offsets);
        }

        /** Horizontal position of one GUI glyph relative to the title, in Java pixels. */
        public int guiOffsetX(int codePoint) {
            return guiOffsets.getOrDefault(codePoint, guiOffsetX);
        }
    }

    public static final String NAMESPACE = "bedrockbundler";
}
