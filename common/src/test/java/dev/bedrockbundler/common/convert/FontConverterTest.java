package dev.bedrockbundler.common.convert;

import dev.bedrockbundler.common.bedrock.Textures;
import dev.bedrockbundler.common.input.ConversionInput;
import dev.bedrockbundler.common.java.JavaPack;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FontConverterTest {

    private static Map<String, byte[]> convert(String fontJson, BufferedImage icons) throws IOException {
        Map<String, byte[]> pack = new HashMap<>();
        pack.put("assets/test/textures/font/icons.png", Textures.png(icons));
        pack.put("assets/minecraft/font/default.json", fontJson.getBytes(StandardCharsets.UTF_8));
        Converter.Result result = Converter.convert(new ConversionInput(pack, List.of(), List.of()),
                ConversionContext.Options.DEFAULT, "Test");
        return JavaPack.readZip(new ByteArrayInputStream(result.mcpack()));
    }

    private static BufferedImage solid(int width, int height, int... colors) {
        BufferedImage image = new BufferedImage(width * colors.length, height, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < colors.length; i++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    image.setRGB(i * width + x, y, colors[i]);
                }
            }
        }
        return image;
    }

    /** Bounding box {minX, minY, maxX, maxY} of visible pixels inside one cell, or null when empty. */
    private static int[] bounds(BufferedImage sheet, int index) {
        int cell = sheet.getWidth() / 16;
        int cx = (index & 0xF) * cell;
        int cy = (index >> 4) * cell;
        int[] box = null;
        for (int y = 0; y < cell; y++) {
            for (int x = 0; x < cell; x++) {
                if ((sheet.getRGB(cx + x, cy + y) >>> 24) > 1) {
                    box = box == null ? new int[]{x, y, x, y}
                            : new int[]{Math.min(box[0], x), Math.min(box[1], y), Math.max(box[2], x), Math.max(box[3], y)};
                }
            }
        }
        return box;
    }

    @Test
    void keepsRelativeSizesAndSkipsRealLetters() throws IOException {
        Map<String, byte[]> mcpack = convert("""
                {"providers": [
                  {"type": "bitmap", "file": "test:font/icons.png", "height": 6, "ascent": 6, "chars": ["\\uE201\\uE202A"]},
                  {"type": "bitmap", "file": "test:font/icons.png", "height": 12, "ascent": 11, "chars": ["\\uE203\\uE204B"]}
                ]}""", solid(16, 16, 0xFFFF0000, 0xFF0000FF, 0xFF00FF00));

        assertTrue(mcpack.containsKey("font/glyph_E2.png"));
        assertFalse(mcpack.containsKey("font/glyph_00.png"), "real letters must never be replaced");
        BufferedImage sheet = ImageIO.read(new ByteArrayInputStream(mcpack.get("font/glyph_E2.png")));

        int[] small = bounds(sheet, 0x01);
        int[] large = bounds(sheet, 0x03);
        assertNotNull(small);
        assertNotNull(large);
        int smallHeight = small[3] - small[1] + 1;
        int largeHeight = large[3] - large[1] + 1;
        // A glyph twice as tall in Java is twice as tall on Bedrock, and neither fills the whole cell.
        assertEquals(2.0, (double) largeHeight / smallHeight, 0.15);
        assertTrue(smallHeight < sheet.getWidth() / 16);
        assertEquals(0xFF0000FF, sheet.getRGB((0x02 & 0xF) * (sheet.getWidth() / 16) + 2, small[1] + 2));
    }

    @Test
    void guiBackgroundsAreDrawnByTheChestScreen() throws IOException {
        // Like ItemsAdder's blank_menu: 192x170, height 170, ascent 47, used as a textured inventory title
        Map<String, byte[]> mcpack = convert("""
                {"providers": [
                  {"type": "bitmap", "file": "test:font/icons.png", "height": 170, "ascent": 47, "chars": ["\\uE018"]}
                ]}""", solid(192, 170, 0xFFF0E0C0));

        // invisible in text
        BufferedImage sheet = ImageIO.read(new ByteArrayInputStream(mcpack.get("font/glyph_E0.png")));
        assertNull(bounds(sheet, 0x18));

        assertTrue(mcpack.containsKey("textures/bedrockbundler/gui/ue018.png"));
        com.google.gson.JsonObject ui = dev.bedrockbundler.common.Json.parse(mcpack.get("ui/chest_screen.json")).getAsJsonObject();
        assertEquals("chest", ui.get("namespace").getAsString());
        for (String panel : List.of("small_chest_panel", "large_chest_panel")) {
            com.google.gson.JsonArray rootControls = null;
            for (var control : ui.getAsJsonObject(panel).getAsJsonArray("controls")) {
                var entry = control.getAsJsonObject().entrySet().iterator().next();
                if (entry.getKey().startsWith("root_panel@")) {
                    rootControls = entry.getValue().getAsJsonObject().getAsJsonArray("controls");
                }
            }
            assertNotNull(rootControls, panel);
            com.google.gson.JsonObject image = rootControls.get(rootControls.size() - 2).getAsJsonObject()
                    .getAsJsonObject("bedrockbundler_gui_e018");
            com.google.gson.JsonObject title = rootControls.get(rootControls.size() - 1).getAsJsonObject()
                    .getAsJsonObject("bedrockbundler_gui_e018_title");
            assertNotNull(title, panel + " redraws the title above the background");
            assertTrue(title.get("layer").getAsInt() > image.get("layer").getAsInt());
            assertEquals(image.get("visible"), title.get("visible"));
            assertNotNull(image, panel + " has the overlay");
            assertEquals("(not ($atext - '' = $atext))", image.get("visible").getAsString());
            assertEquals("$container_title", image.get("$atext").getAsString());
            assertEquals("[192,170]", image.get("size").toString());
            // Java: x = 8 - 16 = -8, top = 6 + 7 - 47 = -34; Bedrock slots sit 4 px lower
            assertEquals("[-8,-30]", image.get("offset").toString());
        }
    }

    @Test
    void positiveOffsetsBecomeInvisibleSpacesAndNegativeOnesAreSkipped() throws IOException {
        Map<String, byte[]> mcpack = convert("""
                {"providers": [
                  {"type": "bitmap", "file": "test:font/icons.png", "height": 9, "ascent": 8, "chars": ["\\uF801"]},
                  {"type": "bitmap", "file": "test:font/icons.png", "height": 7, "ascent": -5000, "chars": ["\\uF828"]},
                  {"type": "bitmap", "file": "test:font/icons.png", "height": -6, "ascent": -5000, "chars": ["\\uF809"]},
                  {"type": "space", "advances": {"\\uF830": 4, "\\uF831": -4}}
                ]}""", solid(16, 16, 0xFF000000));

        BufferedImage sheet = ImageIO.read(new ByteArrayInputStream(mcpack.get("font/glyph_F8.png")));
        int cell = sheet.getWidth() / 16;
        double pixelsPerUnit = cell / (double) ConversionContext.Options.DEFAULT.fontCellUnits();

        // U+F828: invisible, but its rightmost (almost transparent) pixel marks a width of 8 font pixels
        assertNull(bounds(sheet, 0x28), "offsets must not draw anything visible");
        int offsetX = (0x28 & 0xF) * cell + (int) Math.round(8 * pixelsPerUnit) - 1;
        assertEquals(0x01, sheet.getRGB(offsetX, (0x28 >> 4) * cell + cell - 1) >>> 24);
        // space provider with advance 4
        int spaceX = (0x30 & 0xF) * cell + (int) Math.round(4 * pixelsPerUnit) - 1;
        assertEquals(0x01, sheet.getRGB(spaceX, (0x30 >> 4) * cell + cell - 1) >>> 24);
        // negative offsets leave their cells empty
        assertEquals(0, sheet.getRGB((0x09 & 0xF) * cell + cell - 1, cell - 1) >>> 24);
        assertEquals(0, sheet.getRGB((0x31 & 0xF) * cell, (0x31 >> 4) * cell + cell - 1) >>> 24);
    }

    @Test
    void nexoFontsReferencesAndShiftedGuiGlyphs() throws IOException {
        // Nexo's layout: glyphs in nexo:default on U+A4xx, shifts in nexo:shift on U+E1xx, both fonts reference it.
        Map<String, byte[]> pack = new HashMap<>();
        pack.put("assets/test/textures/menu.png", Textures.png(solid(256, 256, 0xFF203040)));
        pack.put("assets/test/textures/icon.png", Textures.png(solid(16, 16, 0xFFFF0000)));
        pack.put("assets/minecraft/font/default.json", """
                {"providers": [{"type": "bitmap", "file": "test:icon.png", "ascent": 8, "height": 8, "chars": ["ꐏ"]},
                  {"type": "reference", "id": "nexo:shift"}]}""".getBytes(StandardCharsets.UTF_8));
        pack.put("assets/nexo/font/default.json", """
                {"providers": [{"type": "bitmap", "file": "test:menu.png", "ascent": 37, "height": 256, "chars": ["ꐑ"]},
                  {"type": "reference", "id": "nexo:shift"}]}""".getBytes(StandardCharsets.UTF_8));
        pack.put("assets/nexo/font/shift.json", """
                {"providers": [{"type": "space", "advances": {"": -1, "": -32, "": 1}}]}"""
                .getBytes(StandardCharsets.UTF_8));
        ConversionContext.Options options = ConversionContext.Options.DEFAULT
                .withGuiOffsets(0, GlyphTitleOffsets.find(
                        List.of("menu_title: <shift:-32><shift:-5><glyph:menu_items>"),
                        id -> id.equals("menu_items") ? "ꐑ" : null));
        Converter.Result result = Converter.convert(new ConversionInput(pack, List.of(), List.of()), options, "Test");
        Map<String, byte[]> mcpack = JavaPack.readZip(new ByteArrayInputStream(result.mcpack()));

        // The shift page is written, so Bedrock's own button icons on U+E1xx never show up in titles.
        BufferedImage shifts = ImageIO.read(new ByteArrayInputStream(mcpack.get("font/glyph_E1.png")));
        assertNull(bounds(shifts, 0x01));
        assertNull(bounds(shifts, 0x06));
        BufferedImage yi = ImageIO.read(new ByteArrayInputStream(mcpack.get("font/glyph_A4.png")));
        assertNotNull(bounds(yi, 0x0F), "the icon from minecraft:default");
        assertNull(bounds(yi, 0x11), "the GUI background is drawn by the chest screen, not in text");

        String ui = new String(mcpack.get("ui/chest_screen.json"), StandardCharsets.UTF_8);
        // Java: x = 8 - 37 = -29, top = 6 + 7 - 37 = -24; Bedrock slots sit 4 px lower
        assertTrue(ui.replaceAll("\\s", "").contains("\"offset\":[-29,-20]"), ui);
    }

    @Test
    void neverTakesOverScriptsPlayersWrite() throws IOException {
        Map<String, byte[]> mcpack = convert("""
                {"providers": [
                  {"type": "bitmap", "file": "test:font/icons.png", "height": 8, "ascent": 7, "chars": ["ı가一ꐐ"]}
                ]}""", solid(16, 16, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00));
        assertFalse(mcpack.containsKey("font/glyph_01.png"), "Turkish dotless i");
        assertFalse(mcpack.containsKey("font/glyph_AC.png"), "Hangul");
        assertFalse(mcpack.containsKey("font/glyph_4E.png"), "CJK");
        assertTrue(mcpack.containsKey("font/glyph_A4.png"), "Yi syllables, which Nexo hands out");
    }
}
