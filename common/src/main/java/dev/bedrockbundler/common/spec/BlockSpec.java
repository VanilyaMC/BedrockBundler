package dev.bedrockbundler.common.spec;

import java.util.Map;

/**
 * One custom block for Geyser.
 *
 * @param name        Bedrock block name (Geyser registers it as {@code geyser_custom:<name>})
 * @param javaState   the Java block state that is replaced by this block for Bedrock players
 * @param geometry    Bedrock geometry identifier, or null for a plain full cube
 * @param materials   material instances keyed by face ({@code *}, {@code up}, ...) or by geometry material name
 * @param rotationX   Java blockstate variant rotation around X, in degrees (multiple of 90)
 * @param rotationY   Java blockstate variant rotation around Y, in degrees (multiple of 90)
 * @param destroyTime seconds to mine, as Bedrock's {@code destructible_by_mining}
 */
public record BlockSpec(
        String name,
        String javaState,
        String displayName,
        String geometry,
        Map<String, Material> materials,
        int rotationX,
        int rotationY,
        float destroyTime,
        int lightEmission
) {

    public record Material(String texture, String renderMethod) {
    }
}
