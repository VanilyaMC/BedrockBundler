package dev.bedrockbundler.common.input;

/**
 * An ItemsAdder player animation file ({@code .player_advanced_animations} in a content pack's model folder),
 * which holds the emotes' baked keyframes.
 *
 * @param source where it was read from, for warnings
 * @param json   the file's content
 */
public record EmoteAnimation(String source, String json) {
}
