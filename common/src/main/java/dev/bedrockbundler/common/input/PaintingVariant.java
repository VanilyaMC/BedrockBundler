package dev.bedrockbundler.common.input;

/**
 * A painting variant of the server's registry ({@code painting_variant}), e.g. one ItemsAdderAdditions adds.
 *
 * @param id     variant id, e.g. {@code iaadditions:iaadditions}
 * @param asset  texture id under {@code textures/painting/}, e.g. {@code iaadditions:iaadditions}
 * @param width  width in blocks
 * @param height height in blocks
 */
public record PaintingVariant(String id, String asset, int width, int height) {
}
