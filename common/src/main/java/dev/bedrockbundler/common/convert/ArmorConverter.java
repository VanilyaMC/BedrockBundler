package dev.bedrockbundler.common.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.bedrockbundler.common.Json;
import dev.bedrockbundler.common.ResourceId;
import dev.bedrockbundler.common.bedrock.Textures;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Worn armor. Java 1.21.4+ describes armor textures in {@code equipment} assets; Bedrock draws them with
 * an attachable on the vanilla player armor geometry. Both use the same 64x32 armor layer layout. Animal armor
 * goes on the animal's geometry (see {@link Body}).
 *
 * <p>Bedrock allows one attachable per item, and it also draws the item held in a hand. This class only builds
 * the worn part ({@link Worn}); {@link ItemConverter} puts it into the item's attachable next to the held model.
 */
final class ArmorConverter {

    private record Slot(String geometry, String layerVariable, String javaLayer) {
    }

    private static final Map<String, Slot> SLOTS = Map.of(
            "head", new Slot("geometry.player.armor.helmet", "helmet_layer_visible", "humanoid"),
            "chest", new Slot("geometry.player.armor.chestplate", "chest_layer_visible", "humanoid"),
            "legs", new Slot("geometry.player.armor.leggings", "leg_layer_visible", "humanoid_leggings"),
            "feet", new Slot("geometry.player.armor.boots", "boot_layer_visible", "humanoid"));

    /**
     * Animal body armor. Bedrock draws vanilla horse armor and llama carpets as a fixed texture layer of the
     * animal itself, so custom ones are attachables. Attachable bones follow the owner's bones of the same name.
     *
     * @param geometry wolf: the vanilla wolf armor geometry; horse and llama: an inflated copy of the animal's
     *                 own geometry ({@code source}, see {@link #writeArmorGeometry})
     * @param source   resource {@code bedrock/models/<source>.geo.json} the inflated copy is made from, or null
     * @param width    texture size the geometry's UVs expect (width, height); a flatter Java texture (the 64x32
     *                 wolf armor) is padded at the bottom to that aspect
     * @param hidden   parts of the animal geometry that are not armor (saddle, bags, chests)
     */
    private record Body(String javaLayer, String[] owners, String geometry, String source, int width,
                        int height, String[] hidden) {
    }

    private static final List<Body> BODIES = List.of(
            new Body("horse_body", new String[]{"minecraft:horse"},
                    "geometry." + ConversionContext.NAMESPACE + ".horse_armor", "horse.v3", 64, 64,
                    new String[]{"Saddle", "Bit*", "Bridle", "Reins*", "Bag*", "MuleEar*"}),
            new Body("llama_body", new String[]{"minecraft:llama", "minecraft:trader_llama"},
                    "geometry." + ConversionContext.NAMESPACE + ".llama_armor", "llama.v1.8", 128, 64,
                    new String[]{"chest*"}),
            new Body("wolf_body", new String[]{"minecraft:wolf"}, "geometry.wolf.armor", null, 64, 64, new String[0]));

    /**
     * Pixels the copied animal geometry grows by on every side. At the animal's own size the armor fights with
     * the animal's surface (flickers); Java draws horse armor on the model inflated by 0.1 as well.
     */
    static final float ARMOR_INFLATE = 0.1f;

    /**
     * The worn part of an item's attachable, keyed apart from the held model's {@code default} and
     * {@code enchanted} entries. {@code controller} draws it whenever the item is not held in a hand.
     */
    record Worn(JsonObject materials, JsonObject textures, JsonObject geometry, JsonArray preAnimation,
                String parentSetup, JsonObject animations, JsonArray animate, String controller) {
    }

    /** Shared controller for player armor and wings: {@code controller.render.armor} on the worn keys. */
    static final String ARMOR_CONTROLLER = "controller.render." + ConversionContext.NAMESPACE + ".armor";
    private static final String ARMOR_GLINT = "textures/misc/enchanted_actor_glint";

    private final ConversionContext ctx;
    /** Vanilla entity (horse, llama, ...) -> the custom body armor items it can wear, for its client entity file. */
    private final Map<String, Set<String>> armorByEntity = new java.util.TreeMap<>();
    /** The custom elytra whose look the vanilla elytra takes, or null. */
    private String vanillaElytra;

    /** Vanilla textures replaced by the pack for {@link #vanillaElytra}: the worn wings and the item icon. */
    static final String VANILLA_ELYTRA_TEXTURE = "textures/models/armor/elytra";
    static final String VANILLA_ELYTRA_ICON = "textures/items/elytra";

    /** Whether the vanilla elytra takes the look of this item (then its icon replaces the vanilla one too). */
    boolean replacesVanillaElytra(String bedrockId) {
        return bedrockId.equals(vanillaElytra);
    }

    ArmorConverter(ConversionContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Bedrock draws attachables only on entities whose client entity sets {@code enable_attachables}; the vanilla
     * horse and llamas do not (they draw their armor as a texture layer), the wolf does. A pack cannot change one
     * field of a vanilla client entity, only replace the whole file, so the pack carries a copy of the vanilla
     * definition ({@code bedrock/entity/*.entity.json}, from Bedrock 1.26.10) with attachables enabled. The horse
     * also stops drawing its own armor layer (the base item's vanilla armor) while it wears a custom one.
     *
     * <p>Only entities that can wear converted armor are replaced. When Mojang changes these definitions the
     * copies need to be updated from the client's {@code vanilla_*} packs.
     */
    private void writeEntity(String entity) {
        String resource = "/bedrock/entity/" + entity + ".entity.json";
        JsonObject file;
        try (java.io.InputStream in = ArmorConverter.class.getResourceAsStream(resource)) {
            if (in == null) {
                return;
            }
            file = Json.parse(in.readAllBytes()).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        JsonObject description = file.getAsJsonObject("minecraft:client_entity").getAsJsonObject("description");
        description.addProperty("enable_attachables", true);
        JsonArray preAnimation = Json.array(Json.object(description, "scripts"), "pre_animation");
        if (preAnimation != null) {
            StringBuilder ids = new StringBuilder();
            for (String id : armorByEntity.get(entity)) {
                ids.append(", '").append(id).append("'");
            }
            for (int i = 0; i < preAnimation.size(); i++) {
                String line = preAnimation.get(i).getAsString();
                if (line.startsWith("variable.armor_texture_slot = query.armor_texture_slot(")) {
                    String vanilla = line.substring("variable.armor_texture_slot = ".length(), line.length() - 1);
                    preAnimation.set(i, new com.google.gson.JsonPrimitive("variable.armor_texture_slot = "
                            + "query.is_item_name_any('slot.armor.body', 0" + ids + ") ? 0 : " + vanilla + ";"));
                }
            }
        }
        ctx.bedrockPack().json("entity/" + entity + ".entity.json", file);
    }

    /** The worn part of an armor item, or null when its equipment asset has nothing Bedrock can draw. */
    Worn worn(String bedrockId, String flat, String equipSlot, String assetId) {
        ResourceId asset = ResourceId.parse(assetId);
        JsonObject equipment = ctx.javaPack().json(asset.asset("equipment", ".json"));
        if (equipSlot.equals("body")) {
            return body(bedrockId, flat, assetId, equipment);
        }
        Slot slot = SLOTS.get(equipSlot);
        if (slot == null) {
            return null;
        }
        if (equipSlot.equals("chest") && layerTexture(equipment, slot.javaLayer()) == null
                && layerTexture(equipment, "wings") != null) {
            return elytra(bedrockId, flat, layerTexture(equipment, "wings"));
        }
        String textureId = layerTexture(equipment, slot.javaLayer());
        if (textureId == null) {
            ctx.log().warn("Armor " + bedrockId + ": equipment asset " + assetId + " has no '" + slot.javaLayer() + "' layer");
            return null;
        }
        ResourceId texture = ResourceId.parse(textureId);
        BufferedImage image = ctx.textures().load(texture.namespace() + ":entity/equipment/" + slot.javaLayer() + "/" + texture.path());
        if (image == null) {
            return null;
        }
        String bedrockTexture = "textures/" + ConversionContext.NAMESPACE + "/armor/" + flat;
        ctx.bedrockPack().file(bedrockTexture + ".png", Textures.png(image));
        writeArmorController();
        return new Worn(ItemConverter.stringMap("armor", "armor", "armor_enchanted", "armor_enchanted"),
                ItemConverter.stringMap("armor", bedrockTexture, "armor_glint", ARMOR_GLINT),
                ItemConverter.stringMap("armor", slot.geometry()), new JsonArray(),
                "v." + slot.layerVariable() + " = 0.0;", new JsonObject(), new JsonArray(), ARMOR_CONTROLLER);
    }

    /**
     * Wings: the vanilla elytra geometry and animations (referenced by id) with the item's texture. Java's
     * {@code entity/equipment/wings} textures use the same 64x32 layout as Bedrock's.
     */
    private Worn elytra(String bedrockId, String flat, String textureId) {
        ResourceId texture = ResourceId.parse(textureId);
        BufferedImage image = ctx.textures().load(texture.namespace() + ":entity/equipment/wings/" + texture.path());
        if (image == null) {
            ctx.log().warn("Elytra " + bedrockId + ": wings texture " + textureId + " not found");
            return null;
        }
        String bedrockTexture = "textures/" + ConversionContext.NAMESPACE + "/armor/" + flat;
        ctx.bedrockPack().file(bedrockTexture + ".png", Textures.png(image));
        writeArmorController();
        if (vanillaElytra == null) {
            // Worn, Geyser hands Bedrock the vanilla elytra instead (GliderUtils: Bedrock glides with nothing else),
            // so the attachable below only draws it held. The vanilla elytra takes this one's look instead.
            vanillaElytra = bedrockId;
            ctx.bedrockPack().file(VANILLA_ELYTRA_TEXTURE + ".png", Textures.png(image));
        } else {
            ctx.log().warn("Elytra " + bedrockId + " looks like " + vanillaElytra + " when worn on Bedrock: Bedrock players "
                    + "glide only with the vanilla elytra, which can have one look");
        }
        // The vanilla controller refers to its animations as default, gliding, ...; those keys have to stay.
        JsonObject animations = ItemConverter.stringMap(
                "elytra_controller", "controller.animation.elytra.default",
                "default", "animation.elytra.default",
                "gliding", "animation.elytra.gliding",
                "sneaking", "animation.elytra.sneaking",
                "sleeping", "animation.elytra.sleeping",
                "swimming", "animation.elytra.swimming");
        JsonArray animate = new JsonArray();
        animate.add(ItemConverter.stringMap("elytra_controller", "!v.held"));
        return new Worn(ItemConverter.stringMap("armor", "elytra", "armor_enchanted", "elytra_glint"),
                ItemConverter.stringMap("armor", bedrockTexture, "armor_glint", ARMOR_GLINT),
                ItemConverter.stringMap("armor", "geometry.elytra"), new JsonArray(),
                "variable.chest_layer_visible = 0.0;", animations, animate, ARMOR_CONTROLLER);
    }

    /**
     * Animal body armor, one entry per animal layer of the equipment asset. A render controller of the item's
     * own picks geometry, material and texture by the wearer ({@code q.owner_identifier}) and hides the parts of
     * the animal geometry that are not armor.
     */
    private Worn body(String bedrockId, String flat, String assetId, JsonObject equipment) {
        JsonObject materials = new JsonObject();
        JsonObject textures = new JsonObject();
        JsonObject geometries = new JsonObject();
        JsonArray preAnimation = new JsonArray();
        JsonObject animationRefs = new JsonObject();
        JsonArray animate = new JsonArray();
        StringBuilder geometry = new StringBuilder();
        StringBuilder material = new StringBuilder();
        StringBuilder texture = new StringBuilder();
        StringBuilder visible = new StringBuilder();
        Set<String> hidden = new LinkedHashSet<>();
        for (Body body : BODIES) {
            String textureId = layerTexture(equipment, body.javaLayer());
            if (textureId == null) {
                continue;
            }
            ResourceId id = ResourceId.parse(textureId);
            BufferedImage image = ctx.textures().load(id.namespace() + ":entity/equipment/" + body.javaLayer() + "/" + id.path());
            if (image == null) {
                ctx.log().warn("Armor " + bedrockId + ": texture " + textureId + " of layer " + body.javaLayer() + " not found");
                continue;
            }
            if (image.getHeight() * body.width() < body.height() * image.getWidth()) {
                // Java's wolf armor texture is 64x32; Bedrock's wolf armor geometry maps UVs on a 64x64 texture.
                BufferedImage padded = new BufferedImage(image.getWidth(), body.height() * image.getWidth() / body.width(),
                        BufferedImage.TYPE_INT_ARGB);
                padded.getGraphics().drawImage(image, 0, 0, null);
                image = padded;
            }
            String name = body.javaLayer().replace("_body", "");
            String bedrockTexture = "textures/" + ConversionContext.NAMESPACE + "/armor/" + flat + "_" + name;
            ctx.bedrockPack().file(bedrockTexture + ".png", Textures.png(image));
            materials.addProperty(name, "armor");
            if (body.source() != null) {
                writeArmorGeometry(body);
            }
            textures.addProperty(name, bedrockTexture);
            geometries.addProperty(name, body.geometry());
            StringBuilder owner = new StringBuilder();
            for (String ownerId : body.owners()) {
                owner.append(owner.isEmpty() ? "" : " || ").append("q.owner_identifier == '").append(ownerId).append("'");
            }
            preAnimation.add("v." + name + " = " + owner + ";");
            geometry.append("v.").append(name).append(" ? Geometry.").append(name).append(" : ");
            material.append("v.").append(name).append(" ? Material.").append(name).append(" : ");
            texture.append("v.").append(name).append(" ? Texture.").append(name).append(" : ");
            visible.append(visible.isEmpty() ? "" : " || ").append("v.").append(name);
            hidden.addAll(List.of(body.hidden()));
            for (String ownerId : body.owners()) {
                String entity = ownerId.substring(ownerId.indexOf(':') + 1);
                armorByEntity.computeIfAbsent(entity, e -> new java.util.TreeSet<>()).add(bedrockId);
                writeEntity(entity);
            }
        }
        if (geometries.isEmpty()) {
            ctx.log().warn("Armor " + bedrockId + ": equipment asset " + assetId + " has no horse_body, llama_body or wolf_body layer");
            return null;
        }
        String fallback = geometries.keySet().iterator().next();
        String controllerId = "controller.render." + ConversionContext.NAMESPACE + "." + flat;
        JsonObject controller = new JsonObject();
        controller.addProperty("geometry", geometry + "Geometry." + fallback);
        JsonArray materialList = new JsonArray();
        materialList.add(ItemConverter.stringMap("*", material + "Material." + fallback));
        controller.add("materials", materialList);
        JsonArray textureList = new JsonArray();
        textureList.add(texture + "Texture." + fallback);
        controller.add("textures", textureList);
        JsonArray partVisibility = new JsonArray();
        partVisibility.add(ItemConverter.stringMap("*", visible.toString()));
        for (String part : hidden) {
            JsonObject entry = new JsonObject();
            entry.addProperty(part, false);
            partVisibility.add(entry);
        }
        controller.add("part_visibility", partVisibility);
        writeController("render_controllers/" + ConversionContext.NAMESPACE + "/" + flat + ".render_controllers.json",
                controllerId, controller);
        return new Worn(materials, textures, geometries, preAnimation, null, animationRefs, animate, controllerId);
    }

    /**
     * The armor geometry of a horse or llama: a copy of the animal's vanilla geometry ({@code bedrock/models/},
     * from Bedrock 1.21.90) under our own id, every cube inflated by {@link #ARMOR_INFLATE}, without the parts
     * that are not armor. Its bones keep the animal's names, so they follow the animal's animation. (Scaling the
     * animal's geometry by an animation does not work: attachable bones take the owner's transforms.)
     */
    private void writeArmorGeometry(Body body) {
        String path = "models/entity/" + ConversionContext.NAMESPACE + "/" + body.javaLayer() + ".geo.json";
        if (ctx.bedrockPack().has(path)) {
            return;
        }
        JsonObject file = resource("/bedrock/models/" + body.source() + ".geo.json");
        JsonObject geometry = file.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        geometry.getAsJsonObject("description").addProperty("identifier", body.geometry());
        Set<String> removed = new LinkedHashSet<>();
        JsonArray bones = new JsonArray();
        for (JsonElement element : geometry.getAsJsonArray("bones")) {
            JsonObject bone = element.getAsJsonObject();
            String name = Json.string(bone, "name", "");
            String parent = Json.string(bone, "parent", null);
            if (matches(name, body.hidden()) || (parent != null && removed.contains(parent))) {
                removed.add(name);
                continue;
            }
            JsonArray cubes = Json.array(bone, "cubes");
            if (cubes != null) {
                for (JsonElement cube : cubes) {
                    JsonObject c = cube.getAsJsonObject();
                    float inflate = c.has("inflate") ? c.get("inflate").getAsFloat() : 0;
                    c.addProperty("inflate", inflate + ARMOR_INFLATE);
                }
            }
            bones.add(bone);
        }
        geometry.add("bones", bones);
        ctx.bedrockPack().json(path, file);
    }

    /** Bone name patterns as in part_visibility: exact, or a prefix ending in {@code *}. */
    private static boolean matches(String name, String[] patterns) {
        for (String pattern : patterns) {
            if (pattern.endsWith("*") ? name.startsWith(pattern.substring(0, pattern.length() - 1)) : name.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject resource(String name) {
        try (java.io.InputStream in = ArmorConverter.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + name);
            }
            return Json.parse(in.readAllBytes()).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void writeArmorController() {
        String path = "render_controllers/" + ConversionContext.NAMESPACE + "/armor.render_controllers.json";
        if (ctx.bedrockPack().has(path)) {
            return;
        }
        JsonObject controller = new JsonObject();
        controller.addProperty("geometry", "Geometry.armor");
        JsonArray materials = new JsonArray();
        materials.add(ItemConverter.stringMap("*", "variable.is_enchanted ? Material.armor_enchanted : Material.armor"));
        controller.add("materials", materials);
        JsonArray textures = new JsonArray();
        textures.add("Texture.armor");
        textures.add("Texture.armor_glint");
        controller.add("textures", textures);
        writeController(path, ARMOR_CONTROLLER, controller);
    }

    private void writeController(String path, String id, JsonObject controller) {
        JsonObject controllers = new JsonObject();
        controllers.add(id, controller);
        JsonObject file = new JsonObject();
        file.addProperty("format_version", "1.8.0");
        file.add("render_controllers", controllers);
        ctx.bedrockPack().json(path, file);
    }

    /**
     * Bedrock item tags for animal armor. Bedrock decides on the client whether a horse takes an item; for custom
     * items that is the {@code minecraft:horse_armor} tag. Java tells horse armor by the equipment asset's
     * {@code horse_body} layer (or the vanilla base item).
     */
    List<String> tags(dev.bedrockbundler.common.input.IaItem item) {
        if (!"body".equals(item.equipSlot())) {
            return List.of();
        }
        JsonObject equipment = item.armorAsset() == null ? null
                : ctx.javaPack().json(ResourceId.parse(item.armorAsset()).asset("equipment", ".json"));
        boolean horse = layerTexture(equipment, "horse_body") != null || item.material().endsWith("_horse_armor");
        return horse ? List.of("minecraft:horse_armor") : List.of();
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
