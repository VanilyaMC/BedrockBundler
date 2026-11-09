package dev.bedrockbundler.common.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bedrockbundler.common.Json;
import dev.bedrockbundler.common.spec.BlockSpec;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes blocks as Geyser JSON block mappings (format 1), for Geyser installs that load {@code custom_mappings}
 * instead of the BedrockBundler extension (for example Geyser-Standalone without a shared folder).
 *
 * <p>Geyser keys a block entry by the Java block id only (the key is parsed as a resource location, so a full
 * state string with brackets is rejected). All states of one Java block therefore become one Bedrock custom
 * block whose {@code state_overrides} hold the components of each overridden state; {@code only_override_states}
 * leaves every other state vanilla.
 */
public final class BlockMappingWriter {

    static final String FULL_BLOCK_GEOMETRY = "minecraft:geometry.full_block";

    private BlockMappingWriter() {
    }

    public static JsonObject write(List<BlockSpec> blocks) {
        Map<String, JsonObject> overridesByBlock = new TreeMap<>();
        for (BlockSpec block : blocks) {
            String state = block.javaState();
            int bracket = state.indexOf('[');
            if (bracket < 0) {
                // A block without properties has a single state; it gets its own entry.
                overridesByBlock.computeIfAbsent(state, k -> new JsonObject()).add("", components(block));
                continue;
            }
            String javaBlock = state.substring(0, bracket);
            String properties = state.substring(bracket + 1, state.length() - 1);
            overridesByBlock.computeIfAbsent(javaBlock, k -> new JsonObject()).add(properties, components(block));
        }

        JsonObject byBlock = new JsonObject();
        overridesByBlock.forEach((javaBlock, overrides) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", name(javaBlock));
            entry.addProperty("included_in_creative_inventory", false);
            if (overrides.has("")) {
                // Property-less block: the components sit on the entry itself.
                overrides.getAsJsonObject("").entrySet().forEach(e -> entry.add(e.getKey(), e.getValue()));
            } else {
                entry.addProperty("only_override_states", true);
                entry.add("state_overrides", overrides);
            }
            byBlock.add(javaBlock, entry);
        });

        JsonObject root = new JsonObject();
        root.addProperty("format_version", 1);
        root.add("blocks", byBlock);
        return root;
    }

    public static byte[] bytes(List<BlockSpec> blocks) {
        return Json.bytes(write(blocks));
    }

    /** One Bedrock block per Java block; Geyser adds the {@code geyser_custom:} namespace. */
    static String name(String javaBlock) {
        return ConversionContext.NAMESPACE + "_" + javaBlock.substring(javaBlock.indexOf(':') + 1);
    }

    private static JsonObject components(BlockSpec block) {
        JsonObject components = new JsonObject();
        if (block.displayName() != null) {
            components.addProperty("display_name", block.displayName());
        }
        components.addProperty("geometry", block.geometry() != null ? block.geometry() : FULL_BLOCK_GEOMETRY);
        components.addProperty("destructible_by_mining", block.destroyTime());
        if (block.lightEmission() > 0) {
            components.addProperty("light_emission", block.lightEmission());
        }
        if (block.rotationX() != 0 || block.rotationY() != 0) {
            // Java blockstate rotations are clockwise seen from above; Bedrock's Y rotation is the other way.
            JsonObject transformation = new JsonObject();
            JsonArray rotation = new JsonArray();
            rotation.add(block.rotationX());
            rotation.add(Math.floorMod(-block.rotationY(), 360));
            rotation.add(0);
            transformation.add("rotation", rotation);
            components.add("transformation", transformation);
        }
        JsonObject materials = new JsonObject();
        block.materials().forEach((key, material) -> {
            JsonObject instance = new JsonObject();
            instance.addProperty("texture", material.texture());
            instance.addProperty("render_method", material.renderMethod());
            instance.addProperty("face_dimming", true);
            instance.addProperty("ambient_occlusion", true);
            materials.add(key, instance);
        });
        components.add("material_instances", materials);
        return components;
    }
}
