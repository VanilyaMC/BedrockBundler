package dev.bedrockbundler.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyTextTest {

    @Test
    void namedColorsBecomeLegacyCodes() {
        assertEquals("§cHello", LegacyText.toLegacy(Component.text("Hello", NamedTextColor.RED)));
        assertEquals("§aOk", LegacyText.toLegacy(Component.text("Ok", NamedTextColor.GREEN)));
    }

    @Test
    void hexColorsAreDownsampledToTheNearestOfSixteen() {
        // near pure red, near pure green, near black, near white
        assertEquals("§cx", LegacyText.toLegacy(Component.text("x", TextColor.color(0xFF3030))));
        assertEquals("§ax", LegacyText.toLegacy(Component.text("x", TextColor.color(0x40F040))));
        assertEquals("§0x", LegacyText.toLegacy(Component.text("x", TextColor.color(0x050505))));
        assertEquals("§fx", LegacyText.toLegacy(Component.text("x", TextColor.color(0xFAFAFA))));
        // a hex code must never reach Bedrock (it would show as literal text)
        assertFalse(LegacyText.toLegacy(Component.text("x", TextColor.color(0x12AB34))).contains("#"));
        assertFalse(LegacyText.toLegacy(Component.text("x", TextColor.color(0x12AB34))).contains("§x"));
    }

    @Test
    void boldItalicAndObfuscatedAreKept() {
        assertTrue(LegacyText.toLegacy(Component.text("b").decorate(TextDecoration.BOLD)).contains("§l"));
        assertTrue(LegacyText.toLegacy(Component.text("i").decorate(TextDecoration.ITALIC)).contains("§o"));
        assertTrue(LegacyText.toLegacy(Component.text("k").decorate(TextDecoration.OBFUSCATED)).contains("§k"));
    }

    @Test
    void underlineAndStrikethroughAreDroppedBecauseTheirCodesAreMaterialColorsOnBedrock() {
        assertEquals("under", LegacyText.toLegacy(Component.text("under").decorate(TextDecoration.UNDERLINED)));
        assertEquals("strike", LegacyText.toLegacy(Component.text("strike").decorate(TextDecoration.STRIKETHROUGH)));
        String mixed = LegacyText.toLegacy(Component.text("a", NamedTextColor.RED)
                .decorate(TextDecoration.UNDERLINED, TextDecoration.STRIKETHROUGH));
        assertFalse(mixed.contains("§n"));
        assertFalse(mixed.contains("§m"));
    }

    @Test
    void newlinesStayInTheText() {
        String text = LegacyText.toLegacy(Component.text("one\ntwo\nthree"));
        assertEquals("one\ntwo\nthree", text);
        assertEquals(3, LegacyText.lineCount(text));
    }

    @Test
    void privateUseGlyphsPassThroughUnchanged() {
        String glyph = "ꐀ";
        assertEquals(glyph, LegacyText.toLegacy(Component.text(glyph)));
        assertEquals("§6" + glyph + "!", LegacyText.toLegacy(Component.text(glyph + "!", NamedTextColor.GOLD)));
    }

    @Test
    void childStylesInheritAndCanChangeTheColor() {
        Component text = Component.text("A", NamedTextColor.RED)
                .append(Component.text("B", NamedTextColor.BLUE))
                .append(Component.text("C"));
        String legacy = LegacyText.toLegacy(text);
        assertTrue(legacy.startsWith("§cA§9B"), legacy);
        assertTrue(legacy.endsWith("C"), legacy);
    }

    @Test
    void aLiteralSectionSignInTheTextCannotStartACode() {
        String legacy = LegacyText.toLegacy(Component.text("a§cb"));
        assertEquals("acb", legacy); // the sign goes, the letters stay
    }

    @Test
    void blankTextIsDetected() {
        assertTrue(LegacyText.isBlank(null));
        assertTrue(LegacyText.isBlank(Component.empty()));
        assertTrue(LegacyText.isBlank(Component.text("  \n ")));
        assertFalse(LegacyText.isBlank(Component.text("")));
        assertFalse(LegacyText.isBlank(Component.text("x")));
    }

    @Test
    void lineCountIsAtLeastOne() {
        assertEquals(1, LegacyText.lineCount(""));
        assertEquals(1, LegacyText.lineCount(null));
        assertEquals(2, LegacyText.lineCount("a\nb"));
        assertEquals(3, LegacyText.lineCount("a\n\nb"));
    }

    @Test
    void opacityHidesOnlyTheNearlyInvisible() {
        assertTrue(LegacyText.opacityVisible((byte) -1));   // 255, the default
        assertTrue(LegacyText.opacityVisible((byte) 127));
        assertTrue(LegacyText.opacityVisible((byte) 16));
        assertFalse(LegacyText.opacityVisible((byte) 15));
        assertFalse(LegacyText.opacityVisible((byte) 4));
        assertTrue(LegacyText.opacityVisible((byte) 3));    // vanilla draws alpha below 4 opaque
        assertTrue(LegacyText.opacityVisible((byte) 0));
    }
}
