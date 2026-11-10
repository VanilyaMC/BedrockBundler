package dev.bedrockbundler.common.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bedrockbundler.common.Json;
import dev.bedrockbundler.common.ResourceId;
import dev.bedrockbundler.common.bedrock.GeometryConverter;
import dev.bedrockbundler.common.bedrock.IconRenderer;
import dev.bedrockbundler.common.bedrock.TextureAtlas;
import dev.bedrockbundler.common.bedrock.Textures;
import dev.bedrockbundler.common.input.IaItem;
import dev.bedrockbundler.common.java.JavaModel;
import dev.bedrockbundler.common.spec.ItemSpec;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Turns ItemsAdder items into Geyser item specs plus their textures, geometry and attachables. */
public final class ItemConverter {

    /**
     * Where Bedrock puts an item for each context before the Java display transform is applied.
     * Values follow the conventions of community Java-to-Bedrock converters; fine-tune here if held
     * 3D items look offset in game.
     */
    private record BasePose(float[] rotation, float[] position, float[] scale) {

        BasePose(float[] rotation, float[] position, float scale) {
            this(rotation, position, new float[]{scale, scale, scale});
        }
    }

    private static final Map<String, BasePose> POSES = new LinkedHashMap<>();

    static {
        POSES.put("thirdperson_main_hand", new BasePose(new float[]{90, 0, 0}, new float[]{0, 13, -3}, 1));
        POSES.put("thirdperson_off_hand", new BasePose(new float[]{90, 0, 0}, new float[]{0, 13, -3}, 1));
        POSES.put("firstperson_main_hand", new BasePose(new float[]{90, 60, -40}, new float[]{4, 10, 4}, 1.5f));
        // Bedrock's first person off-hand frame is not the mirror of the main hand one. This is the main hand
        // pose P carried over by the frame difference of the vanilla shield attachable, assuming its off hand
        // pose shows the mirror image of its main hand one: V_off * M_shield * V_main^-1 * P * M, with V the
        // first person animations of vanilla_1.20.0 (main [5.3, 26, 0.4] / [91, 65, -43], off [-13.5, -5.8, 5.1]
        // / [1, 176, -2.5], pivot [1, 15.5, 3]; the base vanilla pack's values are overridden), M_shield the
        // mirror about the shield's centre x = 1 and M the mirror about our model centre. Bone rotations are
        // Rz(-z) Ry(y) Rx(-x), as Blockbench reads them. Together with the mirrored Java left hand display
        // (mirrored()) this draws the screen mirror image of the main hand, as Java does.
        POSES.put("firstperson_off_hand", new BasePose(new float[]{177.75f, 5.48f, -177.51f},
                new float[]{-22.28f, 17.08f, 23.12f}, 1.5f));
        POSES.put("head", new BasePose(new float[]{0, 0, 0}, new float[]{0, 19.9f, 0}, 0.625f));
        // Worn by BedrockBundler's furniture stand: the model centre sits at the stand's feet, which stand where
        // the item display is, like an item_display renders the model centred on its position. Bedrock lights an
        // entity from the block at its feet; with the feet half a block lower (the model centre's former place),
        // furniture whose display sits on the floor (ItemsAdder HEAD displays) was lit from the ground: black.
        // Bedrock draws the head attachable one model pixel lower than computed (measured in game: furniture
        // sank 1 px into the block below), so the pose lifts it by one pixel; in model space, so it scales with
        // the stand.
        POSES.put("furniture", new BasePose(new float[]{0, 0, 0}, new float[]{0, 1 - 8, 0}, 1));
    }

    /** Bedrock attachable context -> Java display context (and the fallback used when it is absent). */
    private static final Map<String, String[]> JAVA_CONTEXT = Map.of(
            "thirdperson_main_hand", new String[]{"thirdperson_righthand"},
            "thirdperson_off_hand", new String[]{"thirdperson_lefthand", "thirdperson_righthand"},
            "firstperson_main_hand", new String[]{"firstperson_righthand"},
            "firstperson_off_hand", new String[]{"firstperson_lefthand", "firstperson_righthand"},
            "head", new String[]{"head"},
            "furniture", new String[]{"fixed"});

    /**
     * Items Java throws as a projectile that draws the item ({@code ThrownItemRenderer}, ground context).
     * FurnitureMirror makes Bedrock players see a stand wearing the custom item instead of the vanilla projectile.
     */
    static final Set<String> THROWN = Set.of("minecraft:snowball", "minecraft:egg",
            "minecraft:blue_egg", "minecraft:brown_egg", "minecraft:ender_pearl", "minecraft:experience_bottle",
            "minecraft:splash_potion", "minecraft:lingering_potion");

    private static final Map<String, String> CONDITIONS = Map.of(
            "thirdperson_main_hand", "v.main_hand && !c.is_first_person",
            "thirdperson_off_hand", "v.off_hand && !c.is_first_person",
            "firstperson_main_hand", "v.main_hand && c.is_first_person",
            "firstperson_off_hand", "v.off_hand && c.is_first_person",
            "head", "v.head && !v.on_stand",
            "furniture", "v.head && v.on_stand");

    private final ConversionContext ctx;
    private final ArmorConverter armor;

    /**
     * A texture of the Bedrock client a model's faces use instead of a pack texture (vanilla painting motives):
     * its Bedrock path and size. Such a model must use only that one texture.
     */
    record ExternalTexture(String bedrockPath, int width, int height) {
    }

    private final Map<String, ExternalTexture> external;

    public ItemConverter(ConversionContext ctx) {
        this(ctx, Map.of());
    }

    ItemConverter(ConversionContext ctx, Map<String, ExternalTexture> external) {
        this.ctx = ctx;
        this.armor = new ArmorConverter(ctx);
        this.external = external;
    }

    /** The external texture all faces of the model use, or null. */
    private Map.Entry<String, ExternalTexture> externalTexture(JavaModel model) {
        Map.Entry<String, ExternalTexture> found = null;
        for (JavaModel.Element element : model.elements()) {
            for (JavaModel.Face face : element.faces().values()) {
                ExternalTexture texture = face.texture() == null ? null : external.get(face.texture());
                if (texture == null) {
                    return null;
                }
                found = Map.entry(face.texture(), texture);
            }
        }
        return found;
    }

    public ItemSpec convert(IaItem item) {
        if (item.itemModel() == null && item.customModelData() == null) {
            ctx.log().warn("Item " + item.id() + " has neither item_model nor CustomModelData; skipped");
            return null;
        }
        String flat = ResourceId.parse(item.id()).flat();
        String bedrockId = ConversionContext.NAMESPACE + ":" + flat;
        String icon = ConversionContext.NAMESPACE + "." + flat;

        ResourceId modelId = ctx.models().itemModel(item.itemModel(), item.material(), item.customModelData());
        JavaModel model = modelId == null ? null : ctx.models().model(modelId);
        boolean empty = model == null && ctx.models().isEmpty(item.itemModel());
        if (model == null && !empty) {
            ctx.log().warn("No model found for item " + item.id() + "; it gets a placeholder icon");
        }

        // Armor: the worn part shares the item's one attachable with the model held in a hand.
        ArmorConverter.Worn worn = item.equipSlot() != null && item.armorAsset() != null
                ? armor.worn(bedrockId, flat, item.equipSlot(), item.armorAsset()) : null;

        boolean handheld = false;
        BufferedImage iconImage;
        boolean externalModel = model != null && model.kind() == JavaModel.Kind.ELEMENTS && externalTexture(model) != null;
        if (model != null && model.kind() == JavaModel.Kind.ELEMENTS) {
            // A model of a Bedrock client texture cannot be rendered here; it shows Bedrock's painting icon.
            iconImage = externalModel ? null : IconRenderer.render(model, ctx.textures()::load, ctx.options().iconSize());
            ResourceId usingId = ctx.models().usingItemModel(item.itemModel());
            JavaModel using = usingId == null ? null : ctx.models().model(usingId);
            attachable(bedrockId, flat, model, using, item, worn, Map.of());
        } else if (model != null && model.flat()) {
            iconImage = layered(model);
            handheld = model.kind() == JavaModel.Kind.HANDHELD;
            if (worn != null && iconImage != null) {
                // An attachable replaces the flat icon Bedrock draws in a hand, so the sprite is built as a model
                // the way Java builds item/generated ones.
                attachable(bedrockId, flat, GeneratedItemModel.of(model, iconImage, GeneratedItemModel.TEXTURE),
                        null, item, worn, Map.of(GeneratedItemModel.TEXTURE, iconImage));
                worn = null;
            }
        } else {
            String texture = model == null ? null : model.primaryTexture();
            iconImage = texture == null ? null : ctx.textures().load(texture);
        }
        String vanillaIcon = iconImage == null && !empty ? (externalModel ? "textures/items/painting" : vanillaIcon(model)) : null;
        if (empty) {
            iconImage = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB); // drawn by nothing on Java either
        } else if (iconImage == null && vanillaIcon == null) {
            iconImage = placeholderIcon();
        }
        String iconPath = "textures/" + ConversionContext.NAMESPACE + "/items/" + flat;
        if (vanillaIcon != null) {
            // A vanilla texture the content plugin's pack does not carry: Bedrock's own copy of it.
            ctx.bedrockPack().itemTexture(icon, vanillaIcon);
        } else {
            ctx.bedrockPack().file(iconPath + ".png", Textures.png(iconImage));
            ctx.bedrockPack().itemTexture(icon, iconPath);
        }
        if (iconImage != null && armor.replacesVanillaElytra(bedrockId)) {
            // Worn, the item is the vanilla elytra on Bedrock, also in the player's own armor slot.
            ctx.bedrockPack().file(ArmorConverter.VANILLA_ELYTRA_ICON + ".png", Textures.png(iconImage));
        }

        if (worn != null && iconImage != null && (model == null || model.kind() != JavaModel.Kind.ELEMENTS)) {
            // No model to hold (missing or empty): the worn part alone.
            attachable(bedrockId, flat, GeneratedItemModel.of(model, iconImage, GeneratedItemModel.TEXTURE),
                    null, item, worn, Map.of(GeneratedItemModel.TEXTURE, iconImage));
        }
        java.util.List<String> tags = armor.tags(item);

        String category = item.block() ? "construction" : item.equipSlot() != null ? "equipment" : "items";
        ItemSpec.Food food = item.food() == null ? null
                : new ItemSpec.Food(item.food().nutrition(), item.food().saturation(), item.food().canAlwaysEat());
        boolean legacy = item.itemModel() == null;
        return new ItemSpec(
                item.material(),
                legacy ? item.material() : item.itemModel(),
                legacy ? item.customModelData() : null,
                bedrockId,
                item.displayName(),
                icon,
                handheld,
                category,
                item.maxStackSize(),
                item.maxDamage(),
                item.equipSlot(),
                item.protection(),
                food,
                tags);
    }

    /** Flat items: layer0 with any higher layers drawn on top. */
    private BufferedImage layered(JavaModel model) {
        BufferedImage result = null;
        for (int layer = 0; layer < 8; layer++) {
            String texture = model.textures().get("layer" + layer);
            if (texture == null) {
                break;
            }
            BufferedImage image = ctx.textures().load(texture);
            if (image == null) {
                continue;
            }
            if (result == null) {
                result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            }
            result.getGraphics().drawImage(image, 0, 0, result.getWidth(), result.getHeight(), null);
        }
        return result;
    }

    private static BufferedImage placeholderIcon() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, ((x / 8 + y / 8) % 2 == 0) ? 0xFFF800F8 : 0xFF000000);
            }
        }
        return image;
    }

    /**
     * 3D items: geometry + atlas texture + attachable + display animations.
     *
     * @param using the model shown while the item is used (raised shield, charged trident), or null. Only its
     *              display transforms are used: Java's variants share their elements.
     */
    private void attachable(String bedrockId, String flat, JavaModel model, JavaModel using, IaItem item,
                            ArmorConverter.Worn worn, Map<String, BufferedImage> preloaded) {
        boolean shield = item.material().equals("minecraft:shield");
        boolean trident = item.material().equals("minecraft:trident");
        // The furniture pose draws what an entity shows (FurnitureMirror): a furniture item display, with the
        // display context the content plugin gives that furniture, or a thrown trident (Nexo's trident mechanic)
        // or other thrown item, which Java draws like a dropped item.
        String entityDisplay = item.entityDisplay();
        if (entityDisplay == null && THROWN.contains(item.material())) {
            entityDisplay = "ground";
        }
        TextureAtlas atlas;
        String texturePath;
        Map.Entry<String, ExternalTexture> externalTexture = externalTexture(model);
        if (externalTexture != null) {
            ExternalTexture texture = externalTexture.getValue();
            atlas = TextureAtlas.external(externalTexture.getKey(), texture.width(), texture.height());
            texturePath = texture.bedrockPath();
        } else {
            Map<String, BufferedImage> textures = new LinkedHashMap<>();
            for (JavaModel.Element element : model.elements()) {
                for (JavaModel.Face face : element.faces().values()) {
                    if (face.texture() != null) {
                        textures.computeIfAbsent(face.texture(),
                                id -> preloaded.containsKey(id) ? preloaded.get(id) : ctx.textures().load(id));
                    }
                }
            }
            atlas = TextureAtlas.build(textures);
            texturePath = "textures/" + ConversionContext.NAMESPACE + "/models/" + flat;
            ctx.bedrockPack().file(texturePath + ".png", Textures.png(atlas.image()));
        }

        String geometryId = "geometry." + ConversionContext.NAMESPACE + "." + flat;
        ctx.bedrockPack().json("models/entity/" + ConversionContext.NAMESPACE + "/" + flat + ".geo.json",
                GeometryConverter.item(geometryId, model, atlas));

        String animationPrefix = "animation." + ConversionContext.NAMESPACE + "." + flat + ".";
        JsonObject animations = new JsonObject();
        JsonObject animationRefs = new JsonObject();
        JsonArray animate = new JsonArray();
        for (Map.Entry<String, BasePose> pose : POSES.entrySet()) {
            String context = pose.getKey();
            boolean hand = context.contains("_hand");
            JsonObject animation = animation(context.equals("furniture") ? furnitureDisplay(model, entityDisplay)
                    : display(model, context), pose.getValue());
            if (trident && context.equals("furniture")) {
                // The only stand that wears a trident is the mirror of a thrown one, pitched like the trident.
                // Nexo turns its display by rotationYXZ(yaw, -pitch, 0) (the arrow's pitch, positive upwards);
                // between the stand's yaw and the item that is a turn of -pitch about Bedrock's x. Only tridents:
                // on every furniture pose this query broke all furniture in game (stands of pitch 0).
                JsonArray pitch = new JsonArray();
                pitch.add("-q.body_x_rotation");
                pitch.add(0);
                pitch.add(0);
                bone(animation, GeometryConverter.ITEM_ROOT).add("rotation", pitch);
            }
            animations.add(animationPrefix + context, animation);
            animationRefs.addProperty(context, animationPrefix + context);
            JsonObject entry = new JsonObject();
            entry.addProperty(context, CONDITIONS.get(context) + (using != null && hand ? " && !v.using" : ""));
            animate.add(entry);
            if (using != null && hand) {
                String usingContext = context + "_using";
                JsonObject usingAnimation = animation(display(using, context), pose.getValue());
                if (trident && context.startsWith("firstperson")) {
                    // Java's first person trident charging (ItemInHandRenderer, TRIDENT use animation, fully
                    // charged): translate(-0.5i, 0.7, 0.1) Rx(-55) Ry(35.3i) Rz(-9.785i) translate(0, 0, 0.2)
                    // Ry(-45i) between the arm and the display transform, i = 1 right / -1 left, in Bedrock axes.
                    // Its z stretch by 1.2 is left out.
                    float i = context.contains("main") ? 1 : -1;
                    JsonObject use = new JsonObject();
                    use.add("position", Json.array(6.15f * i, 13.34f, 3.1f));
                    use.add("rotation", Json.array(61.77f, 11.45f * i, 4.33f * i));
                    usingAnimation.getAsJsonObject("bones").add(GeometryConverter.ITEM_ROOT + GeometryConverter.USE, use);
                }
                animations.add(animationPrefix + usingContext, usingAnimation);
                animationRefs.addProperty(usingContext, animationPrefix + usingContext);
                JsonObject usingEntry = new JsonObject();
                usingEntry.addProperty(usingContext, CONDITIONS.get(context) + " && v.using");
                animate.add(usingEntry);
            }
        }
        // Bedrock moves the arm for these poses only with the vanilla items (animation.player.shield_block_*,
        // vanilla trident charging), and those poses also turn the vanilla models, so they do not fit Java models.
        // The Java arm pose (HumanoidModel) is played on the item's own arm bones in third person instead, minus
        // Bedrock's holding pose that already turns the arm by x -18. The item keeps its hand display, as on Java.
        String armPose = null;
        float[] rightArm = null;
        float[] leftArm = null;
        if (using != null && shield) {
            // BLOCK: x -54, y -30 (right) / +30 (left); Rx(-18)^-1 Ry(+-30) Rx(-54).
            armPose = "block_";
            rightArm = new float[]{-33.43f, -28.39f, -10.12f};
            leftArm = new float[]{-33.43f, 28.39f, 10.12f};
        } else if (using != null && trident) {
            // THROW_TRIDENT: the arm straight up, x -180.
            armPose = "throw_";
            rightArm = new float[]{-162, 0, 0};
            leftArm = new float[]{-162, 0, 0};
        }
        if (armPose != null) {
            animations.add(animationPrefix + armPose + "main_hand", armAnimation(GeometryConverter.ARM_RIGHT, rightArm));
            animations.add(animationPrefix + armPose + "off_hand", armAnimation(GeometryConverter.ARM_LEFT, leftArm));
            for (String hand : new String[]{"main_hand", "off_hand"}) {
                animationRefs.addProperty(armPose + hand, animationPrefix + armPose + hand);
                JsonObject entry = new JsonObject();
                entry.addProperty(armPose + hand, "v." + hand + " && !c.is_first_person && v.using");
                animate.add(entry);
            }
        }
        JsonObject animationFile = new JsonObject();
        animationFile.addProperty("format_version", "1.8.0");
        animationFile.add("animations", animations);
        ctx.bedrockPack().json("animations/" + ConversionContext.NAMESPACE + "/" + flat + ".animation.json", animationFile);

        JsonObject description = new JsonObject();
        description.addProperty("identifier", bedrockId);
        description.add("materials", stringMap("default", "entity_alphatest", "enchanted", "entity_alphatest_glint"));
        description.add("textures", stringMap("default", texturePath, "enchanted", "textures/misc/enchanted_item_glint"));
        description.add("geometry", stringMap("default", geometryId));
        description.add("animations", animationRefs);
        JsonObject scripts = new JsonObject();
        JsonArray preAnimation = new JsonArray();
        preAnimation.add("v.main_hand = c.item_slot == 'main_hand';");
        preAnimation.add("v.off_hand = c.item_slot == 'off_hand';");
        preAnimation.add("v.head = c.item_slot == 'head';");
        preAnimation.add("v.on_stand = q.owner_identifier == 'minecraft:armor_stand';");
        if (using != null) {
            // Geyser sets these on the owner while the Java player uses the item (blocking also for shields).
            preAnimation.add("v.using = q.is_using_item || q.blocking;");
        }
        JsonArray renderControllers = new JsonArray();
        if (worn == null) {
            renderControllers.add("controller.render.item_default");
        } else {
            // Held: the item model. Anywhere else (armor slots, an animal's body, a stand): the armor.
            preAnimation.add("v.held = c.item_slot == 'main_hand' || c.item_slot == 'off_hand';");
            worn.materials().entrySet().forEach(e -> description.getAsJsonObject("materials").add(e.getKey(), e.getValue()));
            worn.textures().entrySet().forEach(e -> description.getAsJsonObject("textures").add(e.getKey(), e.getValue()));
            worn.geometry().entrySet().forEach(e -> description.getAsJsonObject("geometry").add(e.getKey(), e.getValue()));
            worn.animations().entrySet().forEach(e -> animationRefs.add(e.getKey(), e.getValue()));
            preAnimation.addAll(worn.preAnimation());
            animate.addAll(worn.animate());
            if (worn.parentSetup() != null) {
                scripts.addProperty("parent_setup", worn.parentSetup());
            }
            renderControllers.add(stringMap("controller.render.item_default", "v.held"));
            renderControllers.add(stringMap(worn.controller(), "!v.held"));
        }
        scripts.add("pre_animation", preAnimation);
        scripts.add("animate", animate);
        description.add("scripts", scripts);
        description.add("render_controllers", renderControllers);

        JsonObject attachable = new JsonObject();
        attachable.add("description", description);
        JsonObject file = new JsonObject();
        file.addProperty("format_version", "1.10.0");
        file.add("minecraft:attachable", attachable);
        ctx.bedrockPack().json("attachables/" + ConversionContext.NAMESPACE + "/" + flat + ".json", file);
    }

    private static JsonObject display(JavaModel model, String bedrockContext) {
        for (String javaContext : JAVA_CONTEXT.get(bedrockContext)) {
            JsonObject display = model.display().get(javaContext);
            if (display != null) {
                return bedrockContext.contains("off_hand") ? mirrored(display) : display;
            }
        }
        return new JsonObject();
    }

    /**
     * Java draws the left hand with the display mirrored: translation x and the y and z rotations negated
     * ({@code ItemTransform.apply(leftHand)}). Bedrock's left item bone does not do that by itself.
     */
    private static JsonObject mirrored(JsonObject display) {
        JsonObject copy = display.deepCopy();
        float[] rotation = Json.floats(Json.array(display, "rotation"), 3, 0);
        float[] translation = Json.floats(Json.array(display, "translation"), 3, 0);
        copy.add("rotation", Json.array(rotation[0], -rotation[1], -rotation[2]));
        copy.add("translation", Json.array(-translation[0], translation[1], translation[2]));
        return copy;
    }

    /**
     * Display of the furniture pose: the display context the entity showing the item uses when known
     * ({@code none}: no transform), else the {@code fixed} one item displays use, unless turned off.
     */
    private JsonObject furnitureDisplay(JavaModel model, String entityDisplay) {
        if (entityDisplay != null) {
            JsonObject display = model.display().get(entityDisplay);
            return display == null ? new JsonObject() : display;
        }
        return "none".equals(ctx.options().furnitureDisplay()) ? new JsonObject() : display(model, "furniture");
    }

    /**
     * Bedrock's own texture for a flat model drawn with a vanilla item or block texture
     * ({@code minecraft:item/painting} is {@code textures/items/painting} on Bedrock), or null. Most names match;
     * a name Bedrock does not have shows Bedrock's missing texture, as the placeholder would.
     */
    static String vanillaIcon(JavaModel model) {
        String texture = model == null || !model.flat() ? null : model.primaryTexture();
        if (texture == null) {
            return null;
        }
        ResourceId id = ResourceId.parse(texture);
        if (!id.namespace().equals("minecraft")) {
            return null;
        }
        if (id.path().startsWith("item/")) {
            return "textures/items/" + id.path().substring(5);
        }
        if (id.path().startsWith("block/")) {
            return "textures/blocks/" + id.path().substring(6);
        }
        return null;
    }

    private static JsonObject bone(JsonObject animation, String name) {
        return animation.getAsJsonObject("bones").getAsJsonObject(name);
    }

    private static JsonObject armAnimation(String armBone, float[] armRotation) {
        JsonObject bones = new JsonObject();
        JsonObject arm = new JsonObject();
        arm.add("rotation", Json.array(armRotation));
        bones.add(GeometryConverter.ITEM_ROOT + armBone, arm);
        JsonObject animation = new JsonObject();
        animation.addProperty("loop", true);
        animation.add("bones", bones);
        return animation;
    }

    /** Java display transform -> Bedrock bone animation on the root/axis bones. */
    private static JsonObject animation(JsonObject display, BasePose pose) {
        float[] rotation = Json.floats(Json.array(display, "rotation"), 3, 0);
        float[] translation = Json.floats(Json.array(display, "translation"), 3, 0);
        float[] scale = Json.floats(Json.array(display, "scale"), 3, 1);

        String root = GeometryConverter.ITEM_ROOT;
        JsonObject bones = new JsonObject();
        JsonObject rootBone = new JsonObject();
        rootBone.add("rotation", Json.array(pose.rotation()));
        rootBone.add("position", Json.array(pose.position()));
        rootBone.add("scale", Json.array(pose.scale()));
        bones.add(root, rootBone);

        JsonObject x = new JsonObject();
        x.add("rotation", Json.array(-rotation[0], 0, 0));
        x.add("position", Json.array(-translation[0], translation[1], translation[2]));
        bones.add(root + "_x", x);
        JsonObject y = new JsonObject();
        y.add("rotation", Json.array(0, -rotation[1], 0));
        bones.add(root + "_y", y);
        JsonObject z = new JsonObject();
        z.add("rotation", Json.array(0, 0, rotation[2]));
        // Java applies a display as translate, rotate, scale: the scale must not stretch the translation, so it
        // sits on the innermost bone, not on the root (a painting's fixed display, scale 2 and z -18, floated
        // 9 px off the wall).
        z.add("scale", Json.array(scale));
        bones.add(root + "_z", z);

        JsonObject animation = new JsonObject();
        animation.addProperty("loop", true);
        animation.add("bones", bones);
        return animation;
    }

    static JsonObject stringMap(String... keyValues) {
        JsonObject object = new JsonObject();
        for (int i = 0; i < keyValues.length; i += 2) {
            object.addProperty(keyValues[i], keyValues[i + 1]);
        }
        return object;
    }
}
