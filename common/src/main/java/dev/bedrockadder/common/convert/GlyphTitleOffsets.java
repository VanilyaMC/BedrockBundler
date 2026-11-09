package dev.bedrockadder.common.convert;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds where menu titles place GUI glyphs. Nexo (and menu plugins using its tags) writes titles such as
 * {@code <shift:-37><glyph:menu_items>}: the shifts in front of a glyph move it left of the title start, and the
 * Bedrock chest screen has to draw the background at the same place (see {@link ChestUiWriter}).
 */
public final class GlyphTitleOffsets {

    /** One or more shift tags directly followed by a glyph tag ({@code <glyph:id>}, {@code <glyph:id:colorable>}). */
    private static final Pattern SHIFTED_GLYPH = Pattern.compile(
            "((?:<shift:-?\\d+>\\s*)+)<(?:glyph|g):([A-Za-z0-9_\\-./]+)[^>]*>");
    private static final Pattern SHIFT = Pattern.compile("<shift:(-?\\d+)>");

    private GlyphTitleOffsets() {
    }

    /**
     * @param texts     config texts that may hold menu titles
     * @param glyphChar glyph id -> its character (the first one), or null when unknown
     * @return code point -> shift in Java pixels; the first title found for a glyph wins
     */
    public static Map<Integer, Integer> find(Iterable<String> texts, Function<String, String> glyphChar) {
        Map<Integer, Integer> offsets = new TreeMap<>();
        for (String text : texts) {
            Matcher matcher = SHIFTED_GLYPH.matcher(text);
            while (matcher.find()) {
                String chars = glyphChar.apply(matcher.group(2));
                if (chars == null || chars.isEmpty()) {
                    continue;
                }
                int shift = 0;
                Matcher shifts = SHIFT.matcher(matcher.group(1));
                while (shifts.find()) {
                    shift += Integer.parseInt(shifts.group(1));
                }
                offsets.putIfAbsent(chars.codePointAt(0), shift);
            }
        }
        return offsets;
    }
}
