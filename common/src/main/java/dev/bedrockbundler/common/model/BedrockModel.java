package dev.bedrockbundler.common.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Set;

/**
 * A BetterModel model as Bedrock geometry, animations and textures: the intermediate stage between the
 * {@code .bbmodel} file and the client entity files.
 *
 * @param id         model id
 * @param geometry   {@code minecraft:geometry} document
 * @param animations Bedrock animations by name ({@code animation.} prefix not yet added), may be empty
 * @param textures   textures in file order
 */
record BedrockModel(String id, JsonObject geometry, JsonObject animations, List<Texture> textures) {

    /**
     * @param name         file name without extension
     * @param png          the PNG bytes as stored in the model
     * @param uvWidth      width of the UV grid the model was drawn on
     * @param uvHeight     height of the UV grid
     * @param bindingBones bones that use this texture, sorted
     */
    record Texture(String name, byte[] png, int uvWidth, int uvHeight, Set<String> bindingBones) {
    }

    /** The animation document, or {@code null} without animations. */
    JsonObject animationDocument() {
        if (animations.size() == 0) {
            return null;
        }
        JsonObject doc = new JsonObject();
        doc.addProperty("format_version", "1.8.0");
        doc.add("animations", animations);
        return doc;
    }

    /** The {@code config.json} that goes next to the geometry. */
    JsonObject configDocument() {
        JsonObject cfg = new JsonObject();
        cfg.addProperty("head_rotation", true);
        cfg.addProperty("material", "entity_alphatest_change_color_one_sided");
        cfg.addProperty("blend_transition", true);
        JsonObject uv = new JsonObject();
        JsonObject binding = new JsonObject();
        for (Texture t : textures) {
            JsonArray size = new JsonArray();
            size.add(t.uvWidth());
            size.add(t.uvHeight());
            uv.add(t.name(), size);
            JsonArray bones = new JsonArray();
            t.bindingBones().forEach(bones::add);
            binding.add(t.name(), bones);
        }
        cfg.add("per_texture_uv_size", uv);
        cfg.add("binding_bones", binding);
        cfg.add("anim_textures", new JsonObject());
        cfg.addProperty("disable_part_visibility", false);
        return cfg;
    }
}
