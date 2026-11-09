package dev.bedrockbundler.common.convert;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.bedrockbundler.common.java.JavaModel;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A flat item sprite as a cuboid model, the way Java builds {@code item/generated} models
 * ({@code ItemModelGenerator}): a one pixel thick front and back, plus an edge face wherever an opaque pixel
 * borders a transparent one. Used where Bedrock needs a model for a flat item: an attachable replaces the
 * flat icon Bedrock would otherwise draw in a hand.
 */
final class GeneratedItemModel {

    /** Texture id the generated faces use; the caller supplies the sprite under it. */
    static final String TEXTURE = "bedrockbundler:generated_sprite";

    /** Display transforms of Java's {@code item/generated} and {@code item/handheld}. */
    private static final JsonObject GENERATED_DISPLAY = JsonParser.parseString("""
            {"ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
             "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]},
             "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 3, 1], "scale": [0.55, 0.55, 0.55]},
             "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
             "fixed": {"rotation": [0, 180, 0], "scale": [1, 1, 1]}}""").getAsJsonObject();
    private static final JsonObject HANDHELD_DISPLAY = JsonParser.parseString("""
            {"thirdperson_righthand": {"rotation": [0, -90, 55], "translation": [0, 4, 0.5], "scale": [0.85, 0.85, 0.85]},
             "thirdperson_lefthand": {"rotation": [0, 90, -55], "translation": [0, 4, 0.5], "scale": [0.85, 0.85, 0.85]},
             "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
             "firstperson_lefthand": {"rotation": [0, 90, -25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]}}""")
            .getAsJsonObject();

    private GeneratedItemModel() {
    }

    /**
     * @param flat   the flat model whose display transforms are kept (vanilla defaults fill the rest), or null
     * @param sprite the item's sprite, all layers drawn
     */
    static JavaModel of(JavaModel flat, BufferedImage sprite, String texture) {
        int width = sprite.getWidth();
        int height = sprite.getHeight();
        float pixelX = 16f / width;
        float pixelY = 16f / height;
        List<JavaModel.Element> elements = new ArrayList<>();
        Map<String, JavaModel.Face> sides = new LinkedHashMap<>();
        sides.put("south", face(new float[]{0, 0, 16, 16}, texture));
        sides.put("north", face(new float[]{16, 0, 0, 16}, texture));
        elements.add(new JavaModel.Element(new float[]{0, 0, 7.5f}, new float[]{16, 16, 8.5f}, null, sides));
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!opaque(sprite, x, y)) {
                    continue;
                }
                float[] uv = {x * pixelX, y * pixelY, (x + 1) * pixelX, (y + 1) * pixelY};
                Map<String, JavaModel.Face> faces = new LinkedHashMap<>();
                if (!opaque(sprite, x - 1, y)) {
                    faces.put("west", face(uv, texture));
                }
                if (!opaque(sprite, x + 1, y)) {
                    faces.put("east", face(uv, texture));
                }
                if (!opaque(sprite, x, y - 1)) {
                    faces.put("up", face(uv, texture));
                }
                if (!opaque(sprite, x, y + 1)) {
                    faces.put("down", face(uv, texture));
                }
                if (!faces.isEmpty()) {
                    // Image rows run downwards, model y upwards.
                    elements.add(new JavaModel.Element(
                            new float[]{x * pixelX, 16 - (y + 1) * pixelY, 7.5f},
                            new float[]{(x + 1) * pixelX, 16 - y * pixelY, 8.5f}, null, faces));
                }
            }
        }

        boolean handheld = flat != null && flat.kind() == JavaModel.Kind.HANDHELD;
        Map<String, JsonObject> display = new LinkedHashMap<>();
        (handheld ? HANDHELD_DISPLAY : GENERATED_DISPLAY).entrySet()
                .forEach(e -> display.put(e.getKey(), e.getValue().getAsJsonObject()));
        if (handheld) {
            GENERATED_DISPLAY.entrySet().forEach(e -> display.putIfAbsent(e.getKey(), e.getValue().getAsJsonObject()));
        }
        if (flat != null) {
            display.putAll(flat.display());
        }
        return new JavaModel(JavaModel.Kind.ELEMENTS, Map.of("layer0", texture), elements, display);
    }

    private static JavaModel.Face face(float[] uv, String texture) {
        return new JavaModel.Face(uv, texture, "layer0", 0, -1);
    }

    private static boolean opaque(BufferedImage image, int x, int y) {
        return x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight() && (image.getRGB(x, y) >>> 24) != 0;
    }
}
