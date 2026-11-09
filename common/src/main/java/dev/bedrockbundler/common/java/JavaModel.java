package dev.bedrockbundler.common.java;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * A Java block/item model with its parent chain fully resolved.
 *
 * @param kind     how the model renders
 * @param textures texture variables, already resolved to resource ids (no {@code #} references)
 * @param elements cuboids; empty for generated (flat) models
 * @param display  display transforms by context ({@code gui}, {@code thirdperson_righthand}, ...)
 */
public record JavaModel(Kind kind, Map<String, String> textures, List<Element> elements, Map<String, JsonObject> display) {

    public enum Kind {
        /** Flat item sprite built from {@code layer0..n} ({@code item/generated}). */
        GENERATED,
        /** Flat sprite held like a tool ({@code item/handheld}). */
        HANDHELD,
        /** Cuboid model. */
        ELEMENTS,
        /** Parent chain ended in something we cannot resolve. */
        MISSING
    }

    public boolean flat() {
        return kind == Kind.GENERATED || kind == Kind.HANDHELD;
    }

    /** The texture that best represents this model as a single image. */
    public String primaryTexture() {
        for (String key : new String[]{"layer0", "all", "side", "front", "texture", "particle"}) {
            if (textures.containsKey(key)) {
                return textures.get(key);
            }
        }
        for (Element element : elements) {
            for (Face face : element.faces().values()) {
                if (face.texture() != null) {
                    return face.texture();
                }
            }
        }
        return textures.isEmpty() ? null : textures.values().iterator().next();
    }

    public record Element(float[] from, float[] to, Rotation rotation, Map<String, Face> faces) {
    }

    /** {@code axis} is x, y or z; {@code angle} is in degrees. */
    public record Rotation(float[] origin, String axis, float angle, boolean rescale) {
    }

    /**
     * @param uv          u1, v1, u2, v2 in 0-16 space (never null; filled with Java's automatic UV when omitted)
     * @param texture     resolved texture id, or null when the variable is unresolved
     * @param textureVar  the variable name the face used, e.g. {@code side}
     * @param rotation    UV rotation in degrees (0, 90, 180, 270)
     */
    public record Face(float[] uv, String texture, String textureVar, int rotation, int tintIndex) {
    }
}
