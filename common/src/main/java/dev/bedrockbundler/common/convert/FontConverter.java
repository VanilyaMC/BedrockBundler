package dev.bedrockbundler.common.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.bedrockbundler.common.Json;
import dev.bedrockbundler.common.ResourceId;
import dev.bedrockbundler.common.bedrock.Textures;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Font images (emojis, icons, offsets) of the default font. Java maps private-use characters to bitmaps through
 * font providers; Bedrock draws private-use characters from glyph sheets {@code font/glyph_XX.png}, one 16x16
 * grid per 256 code points ({@code XX} is the high byte).
 *
 * <p>Bedrock draws each grid cell at a fixed size, so a glyph's size and vertical position come from where it
 * sits inside its cell. One cell stands for {@link ConversionContext.Options#fontCellUnits()} Java font pixels:
 * a glyph is scaled by its Java {@code height} and placed by its {@code ascent}, which keeps relative sizes.
 *
 * <p>Offsets (shift characters) only move the following text. Positive offsets become invisible glyphs of the
 * same width; negative offsets have no Bedrock equivalent and become invisible glyphs without width.
 *
 * <p>Bedrock has a single font, so every font of the pack is read: {@code minecraft:default} first (it wins a
 * conflict), with the fonts it pulls in through {@code reference} providers, then the remaining fonts of other
 * namespaces (Nexo puts its glyphs in {@code nexo:default}).
 */
final class FontConverter {

    private static final String DEFAULT_FONT = "minecraft:default";
    private static final int MAX_CELL = 64;
    /** Glyphs drawn this far away only exist for their width: ItemsAdder's offset characters. */
    private static final int OFFSET_ASCENT = -100;
    /** Glyphs at least this tall are GUI backgrounds (ItemsAdder's textured inventories), not text icons. */
    private static final int GUI_MIN_HEIGHT = 64;

    /**
     * @param image   glyph pixels, or null for an offset (width only)
     * @param height  Java font height (pixels the glyph is scaled to)
     * @param ascent  Java ascent
     * @param advance width in Java font pixels, used for offsets
     */
    private record Glyph(int codePoint, BufferedImage image, double height, double ascent, double advance) {

        boolean background() {
            return image != null && height >= GUI_MIN_HEIGHT;
        }
    }

    private final ConversionContext ctx;
    private final List<Glyph> backgrounds = new ArrayList<>();
    private final Set<Integer> seen = new HashSet<>();
    private final Set<String> readFonts = new HashSet<>();
    private int negativeOffsets;

    FontConverter(ConversionContext ctx) {
        this.ctx = ctx;
    }

    /** @return number of glyphs written */
    int convert() {
        Map<Integer, List<Glyph>> pages = new TreeMap<>();
        readFont(DEFAULT_FONT, pages);
        List<String> others = new ArrayList<>();
        for (String path : ctx.javaPack().paths()) {
            String font = fontId(path);
            if (font != null && !font.startsWith("minecraft:")) {
                others.add(font);
            }
        }
        others.sort(null);
        for (String font : others) {
            readFont(font, pages);
        }
        if (negativeOffsets > 0) {
            ctx.log().warn(negativeOffsets + " negative font offsets are invisible without width on Bedrock: text cannot move backwards");
        }

        int count = 0;
        for (Map.Entry<Integer, List<Glyph>> page : pages.entrySet()) {
            if (page.getKey() == 0xE0 || page.getKey() == 0xE1) {
                ctx.log().warn(String.format("Font page U+%02X00 also holds Bedrock's own button icons; they are replaced on Bedrock", page.getKey()));
            } else if (!privateUse(page.getKey() << 8)) {
                ctx.log().warn(String.format("Font page U+%02X00 is not private use; its other characters are blank on Bedrock", page.getKey()));
            }
            writePage(page.getKey(), page.getValue());
            count += page.getValue().size();
        }
        if (!backgrounds.isEmpty()) {
            new ChestUiWriter(ctx).write(backgrounds.stream()
                    .map(g -> new ChestUiWriter.Background(g.codePoint(), g.image(), g.height(), g.ascent()))
                    .toList());
        }
        return count;
    }

    /** {@code assets/<ns>/font/<path>.json} -> {@code <ns>:<path>}, or null for other files. */
    private static String fontId(String path) {
        if (!path.startsWith("assets/") || !path.endsWith(".json")) {
            return null;
        }
        int slash = path.indexOf('/', 7);
        if (slash < 0 || !path.startsWith("/font/", slash)) {
            return null;
        }
        return path.substring(7, slash) + ":" + path.substring(slash + 6, path.length() - 5);
    }

    private void readFont(String font, Map<Integer, List<Glyph>> pages) {
        ResourceId id = ResourceId.parse(font);
        if (!readFonts.add(id.namespace() + ":" + id.path())) {
            return; // read already, or a reference cycle
        }
        JsonArray providers = Json.array(ctx.javaPack().json(id.asset("font", ".json")), "providers");
        if (providers == null) {
            return;
        }
        for (JsonElement element : providers) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject provider = element.getAsJsonObject();
            switch (Json.string(provider, "type", "").replace("minecraft:", "")) {
                case "bitmap" -> readBitmap(provider, pages);
                case "space" -> readSpace(provider, pages);
                case "reference" -> {
                    String referenced = Json.string(provider, "id", null);
                    if (referenced != null) {
                        readFont(referenced, pages);
                    }
                }
                default -> {
                }
            }
        }
    }

    private void readBitmap(JsonObject provider, Map<Integer, List<Glyph>> pages) {
        double height = Json.number(provider, "height", 8);
        double ascent = Json.number(provider, "ascent", 7);
        String file = Json.string(provider, "file", null);
        JsonArray rows = Json.array(provider, "chars");
        if (file == null || rows == null || rows.isEmpty()) {
            return;
        }

        List<int[]> codePointRows = new ArrayList<>();
        int columns = 0;
        for (JsonElement row : rows) {
            int[] codePoints = row.getAsString().codePoints().toArray();
            codePointRows.add(codePoints);
            columns = Math.max(columns, codePoints.length);
        }

        // Offset characters: a negative height moves text left, a far-away glyph only contributes its width.
        if (height <= 0 || ascent <= OFFSET_ASCENT) {
            for (int[] codePoints : codePointRows) {
                for (int codePoint : codePoints) {
                    if (!replaceable(codePoint)) {
                        continue;
                    }
                    if (height <= 0) {
                        negativeOffsets++;
                        add(pages, new Glyph(codePoint, null, 0, 0, 0));
                    } else {
                        // Java advance of a bitmap glyph: its scaled width plus one pixel of spacing.
                        add(pages, new Glyph(codePoint, null, height, ascent, height + 1));
                    }
                }
            }
            return;
        }

        ResourceId fileId = ResourceId.parse(file);
        // "file" includes the extension and is relative to textures/
        BufferedImage sheet = ctx.textures().load(fileId.namespace() + ":" + fileId.path().replaceFirst("\\.png$", ""));
        if (sheet == null) {
            return;
        }
        int cellWidth = sheet.getWidth() / columns;
        int cellHeight = sheet.getHeight() / codePointRows.size();
        if (cellWidth <= 0 || cellHeight <= 0) {
            return;
        }
        for (int r = 0; r < codePointRows.size(); r++) {
            int[] codePoints = codePointRows.get(r);
            for (int c = 0; c < codePoints.length; c++) {
                if (replaceable(codePoints[c])) {
                    BufferedImage glyph = sheet.getSubimage(c * cellWidth, r * cellHeight, cellWidth, cellHeight);
                    add(pages, new Glyph(codePoints[c], glyph, height, ascent, 0));
                }
            }
        }
    }

    /** {@code {"type": "space", "advances": {"": 4, "": -4}}} */
    private void readSpace(JsonObject provider, Map<Integer, List<Glyph>> pages) {
        JsonObject advances = Json.object(provider, "advances");
        if (advances == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : advances.entrySet()) {
            int codePoint = entry.getKey().codePointAt(0);
            double advance = entry.getValue().getAsDouble();
            if (!replaceable(codePoint)) {
                continue;
            }
            if (advance < 0) {
                negativeOffsets++;
            }
            add(pages, new Glyph(codePoint, null, 0, 0, Math.max(0, advance)));
        }
    }

    private static boolean privateUse(int codePoint) {
        return codePoint >= 0xE000 && codePoint <= 0xF8FF;
    }

    /**
     * Characters a glyph sheet may take over: private use, plus the rarely typed blocks content plugins also hand
     * out (Nexo starts at U+A410, Yi syllables). Never the scripts players write in: everything below U+1000
     * (Latin, Turkish, Greek, Cyrillic, Arabic, Hebrew, Indic, Thai), Hangul and CJK. Bedrock sheets only cover the
     * basic plane.
     */
    private static boolean replaceable(int codePoint) {
        if (privateUse(codePoint)) {
            return true;
        }
        if (codePoint < 0x1000 || codePoint > 0xFFFF) {
            return false;
        }
        boolean hangul = (codePoint >= 0x1100 && codePoint <= 0x11FF) || (codePoint >= 0xAC00 && codePoint <= 0xD7FF);
        boolean cjk = codePoint >= 0x2E80 && codePoint <= 0x9FFF;
        boolean compatibility = codePoint >= 0xF900; // CJK compatibility, presentation forms, fullwidth, specials
        return !hangul && !cjk && !compatibility;
    }

    private void add(Map<Integer, List<Glyph>> pages, Glyph glyph) {
        if (!seen.add(glyph.codePoint())) {
            return; // the first font that defines a character wins, like Java's provider order
        }
        pages.computeIfAbsent(glyph.codePoint() >> 8, k -> new ArrayList<>()).add(glyph);
        if (glyph.background()) {
            backgrounds.add(glyph);
        }
    }

    private void writePage(int page, List<Glyph> glyphs) {
        int units = ctx.options().fontCellUnits();
        // Enough resolution for the most detailed glyph at its displayed size.
        double density = 1;
        for (Glyph glyph : glyphs) {
            if (glyph.image() != null) {
                density = Math.max(density, glyph.image().getHeight() / glyph.height());
            }
        }
        int cell = Math.max(16, Math.min(MAX_CELL, Integer.highestOneBit((int) Math.ceil(units * density) - 1) << 1));
        double pixelsPerUnit = (double) cell / units;
        // Bedrock centres a cell on the text line; this baseline puts normal text (height 8, ascent 7) on it.
        double baseline = units / 2.0 + 3;

        BufferedImage sheet = new BufferedImage(cell * 16, cell * 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        for (Glyph glyph : glyphs) {
            int index = glyph.codePoint() & 0xFF;
            int cellX = (index & 0xF) * cell;
            int cellY = (index >> 4) * cell;

            if (glyph.image() == null) {
                // Bedrock measures a glyph's width up to its rightmost visible pixel; an almost transparent
                // pixel there makes an invisible glyph of the right width.
                int width = (int) Math.round(glyph.advance() * pixelsPerUnit);
                if (width > 0) {
                    sheet.setRGB(cellX + Math.min(cell, width) - 1, cellY + cell - 1, 0x01000000);
                }
                continue;
            }

            if (glyph.background()) {
                // Drawn by the chest UI instead (see ChestUiWriter); in text it stays invisible.
                continue;
            }
            BufferedImage image = glyph.image();
            double scale = glyph.height() / image.getHeight() * pixelsPerUnit;
            int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
            if (width > cell || height > cell) {
                // Taller than a cell (HUD art): shrink to fit, it cannot be shown at Java size anyway.
                double fit = Math.min((double) cell / width, (double) cell / height);
                width = Math.max(1, (int) (width * fit));
                height = Math.max(1, (int) (height * fit));
            }
            int top = (int) Math.round((baseline - glyph.ascent()) * pixelsPerUnit);
            top = Math.max(0, Math.min(cell - height, top));
            g.drawImage(image, cellX, cellY + top, width, height, null);
        }
        g.dispose();
        ctx.bedrockPack().file(String.format("font/glyph_%02X.png", page), Textures.png(sheet));
    }
}
