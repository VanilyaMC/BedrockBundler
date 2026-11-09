package dev.bedrockadder.common.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.bedrockadder.common.Json;
import dev.bedrockadder.common.ResourceId;
import dev.bedrockadder.common.bedrock.Textures;

import java.awt.image.BufferedImage;
import java.util.Map;

/**
 * Worn armor. Java 1.21.4+ describes armor textures in {@code equipment} assets; Bedrock draws them with
 * an attachable on the vanilla player armor geometry. Both use the same 64x32 armor layer layout.
 */
final class ArmorConverter {

    private record Slot(String geometry, String layerVariable, String javaLayer) {
    }

    private static final Map<String, Slot> SLOTS = Map.of(
            "head", new Slot("geometry.player.armor.helmet", "helmet_layer_visible", "humanoid"),
            "chest", new Slot("geometry.player.armor.chestplate", "chest_layer_visible", "humanoid"),
            "legs", new Slot("geometry.player.armor.leggings", "leg_layer_visible", "humanoid_leggings"),
            "feet", new Slot("geometry.player.armor.boots", "boot_layer_visible", "humanoid"));

    private final ConversionContext ctx;

    ArmorConverter(ConversionContext ctx) {
        this.ctx = ctx;
    }

    void attachable(String bedrockId, String flat, String equipSlot, String assetId) {
        Slot slot = SLOTS.get(equipSlot);
        if (slot == null) {
            return;
        }
        ResourceId asset = ResourceId.parse(assetId);
        JsonObject equipment = ctx.javaPack().json(asset.asset("equipment", ".json"));
        if (equipSlot.equals("chest") && layerTexture(equipment, slot.javaLayer()) == null
                && layerTexture(equipment, "wings") != null) {
            elytra(bedrockId, flat, layerTexture(equipment, "wings"));
            return;
        }
        String textureId = layerTexture(equipment, slot.javaLayer());
        if (textureId == null) {
            ctx.log().warn("Armor " + bedrockId + ": equipment asset " + assetId + " has no '" + slot.javaLayer() + "' layer");
            return;
        }
        ResourceId texture = ResourceId.parse(textureId);
        String texturePath = "entity/equipment/" + slot.javaLayer() + "/" + texture.path();
        BufferedImage image = ctx.textures().load(texture.namespace() + ":" + texturePath);
        if (image == null) {
            return;
        }
        String bedrockTexture = "textures/" + ConversionContext.NAMESPACE + "/armor/" + flat;
        ctx.bedrockPack().file(bedrockTexture + ".png", Textures.png(image));

        JsonObject description = new JsonObject();
        description.addProperty("identifier", bedrockId);
        description.add("materials", ItemConverter.stringMap("default", "armor", "enchanted", "armor_enchanted"));
        description.add("textures", ItemConverter.stringMap("default", bedrockTexture, "enchanted", "textures/misc/enchanted_actor_glint"));
        description.add("geometry", ItemConverter.stringMap("default", slot.geometry()));
        description.add("scripts", ItemConverter.stringMap("parent_setup", "v." + slot.layerVariable() + " = 0.0;"));
        JsonArray renderControllers = new JsonArray();
        renderControllers.add("controller.render.armor");
        description.add("render_controllers", renderControllers);

        JsonObject attachable = new JsonObject();
        attachable.add("description", description);
        JsonObject file = new JsonObject();
        file.addProperty("format_version", "1.10.0");
        file.add("minecraft:attachable", attachable);
        ctx.bedrockPack().json("attachables/" + ConversionContext.NAMESPACE + "/" + flat + ".json", file);
    }

    /**
     * Wings: the vanilla elytra attachable (geometry, animations and render controller referenced by id) with the
     * item's texture. Java's {@code entity/equipment/wings} textures use the same 64x32 layout as Bedrock's.
     */
    private void elytra(String bedrockId, String flat, String textureId) {
        ResourceId texture = ResourceId.parse(textureId);
        BufferedImage image = ctx.textures().load(texture.namespace() + ":entity/equipment/wings/" + texture.path());
        if (image == null) {
            ctx.log().warn("Elytra " + bedrockId + ": wings texture " + textureId + " not found");
            return;
        }
        String bedrockTexture = "textures/" + ConversionContext.NAMESPACE + "/armor/" + flat;
        ctx.bedrockPack().file(bedrockTexture + ".png", Textures.png(image));

        JsonObject description = new JsonObject();
        description.addProperty("identifier", bedrockId);
        description.add("materials", ItemConverter.stringMap("default", "elytra", "enchanted", "elytra_glint"));
        description.add("textures", ItemConverter.stringMap("default", bedrockTexture, "enchanted", "textures/misc/enchanted_actor_glint"));
        description.add("geometry", ItemConverter.stringMap("default", "geometry.elytra"));
        description.add("animations", ItemConverter.stringMap(
                "default_controller", "controller.animation.elytra.default",
                "default", "animation.elytra.default",
                "gliding", "animation.elytra.gliding",
                "sneaking", "animation.elytra.sneaking",
                "sleeping", "animation.elytra.sleeping",
                "swimming", "animation.elytra.swimming"));
        JsonObject scripts = new JsonObject();
        scripts.addProperty("parent_setup", "variable.chest_layer_visible = 0.0;");
        JsonArray animate = new JsonArray();
        animate.add("default_controller");
        scripts.add("animate", animate);
        description.add("scripts", scripts);
        JsonArray renderControllers = new JsonArray();
        renderControllers.add("controller.render.armor.player");
        description.add("render_controllers", renderControllers);

        JsonObject attachable = new JsonObject();
        attachable.add("description", description);
        JsonObject file = new JsonObject();
        file.addProperty("format_version", "1.10.0");
        file.add("minecraft:attachable", attachable);
        ctx.bedrockPack().json("attachables/" + ConversionContext.NAMESPACE + "/" + flat + ".json", file);
    }

    /**
     * Bedrock item tags for animal armor. Bedrock decides on the client whether a horse takes an item; for custom
     * items that is the {@code minecraft:horse_armor} tag. Java tells horse armor by the equipment asset's
     * {@code horse_body} layer (or the vanilla base item).
     */
    java.util.List<String> tags(dev.bedrockadder.common.input.IaItem item) {
        if (!"body".equals(item.equipSlot())) {
            return java.util.List.of();
        }
        JsonObject equipment = item.armorAsset() == null ? null
                : ctx.javaPack().json(ResourceId.parse(item.armorAsset()).asset("equipment", ".json"));
        boolean horse = layerTexture(equipment, "horse_body") != null || item.material().endsWith("_horse_armor");
        return horse ? java.util.List.of("minecraft:horse_armor") : java.util.List.of();
    }

    private static String layerTexture(JsonObject equipment, String layer) {
        JsonObject layers = Json.object(equipment, "layers");
        JsonArray entries = Json.array(layers, layer);
        if (entries == null) {
            return null;
        }
        for (JsonElement entry : entries) {
            if (entry.isJsonObject()) {
                String texture = Json.string(entry.getAsJsonObject(), "texture", null);
                if (texture != null) {
                    return texture;
                }
            }
        }
        return null;
    }
}
