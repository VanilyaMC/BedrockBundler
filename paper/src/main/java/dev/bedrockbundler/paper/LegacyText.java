package dev.bedrockbundler.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.CharacterAndFormat;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.List;

/**
 * Turns Java text components into the section-sign strings Bedrock name plates understand. Pure logic, no server
 * needed.
 *
 * <p>Bedrock differences that matter here:
 * <ul>
 *   <li>Only the 16 legacy colors exist, so hex colors are downsampled to the nearest one (Adventure picks it when
 *       hex output is off).</li>
 *   <li>Bold ({@code §l}), italic ({@code §o}), obfuscated ({@code §k}) and reset ({@code §r}) exist. Underline and
 *       strikethrough do not, and their Java codes {@code §n} and {@code §m} are material colors on Bedrock (copper
 *       and redstone), so they are dropped instead of written.</li>
 *   <li>Private-use glyphs (font images) pass through unchanged: the converter maps them to glyph sheets.</li>
 *   <li>Newlines stay newlines; Bedrock name plates draw multi-line text.</li>
 * </ul>
 */
final class LegacyText {

    private static final char SECTION = '§';

    /** The 16 colors plus bold, italic, obfuscated and reset; no underline or strikethrough (see above). */
    private static final List<CharacterAndFormat> FORMATS =
            CharacterAndFormat.defaults().stream()
                    .filter(f -> f.format() != TextDecoration.UNDERLINED && f.format() != TextDecoration.STRIKETHROUGH)
                    .toList();

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.builder()
            .character(SECTION)
            .formats(FORMATS)
            .build();

    /** Display opacities below this are practically invisible (6 percent); Java draws them anyway. */
    private static final int MIN_VISIBLE_OPACITY = 16;
    /** Vanilla draws text whose alpha is below 4 fully opaque (its "alpha 0 means unset" rule). */
    private static final int OPAQUE_BELOW = 4;

    private LegacyText() {
    }

    /** The component as a Bedrock-friendly legacy string. Never null. */
    static String toLegacy(Component component) {
        if (component == null) {
            return "";
        }
        Component clean = component;
        if (PlainTextComponentSerializer.plainText().serialize(component).indexOf(SECTION) >= 0) {
            // A literal section sign typed by a player would start a format code of its own.
            clean = component.replaceText(TextReplacementConfig.builder().matchLiteral(String.valueOf(SECTION)).replacement("").build());
        }
        return SERIALIZER.serialize(clean);
    }

    /** Whether nothing visible is left of the component (empty, spaces and line breaks only). */
    static boolean isBlank(Component component) {
        return component == null || PlainTextComponentSerializer.plainText().serialize(component).isBlank();
    }

    /** Number of lines of a text (at least 1); used to center a name plate the way Java centers its text. */
    static int lineCount(String text) {
        if (text == null || text.isEmpty()) {
            return 1;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /**
     * Whether a text display's text opacity (the raw signed byte of {@code TextDisplay#getTextOpacity}) draws
     * anything. 255 (stored as -1) is fully opaque; 0-3 count as opaque too, like in the vanilla renderer; the
     * values in between are so faint that a name plate would be wrong.
     */
    static boolean opacityVisible(byte rawOpacity) {
        int alpha = rawOpacity & 0xFF;
        return alpha < OPAQUE_BELOW || alpha >= MIN_VISIBLE_OPACITY;
    }
}
