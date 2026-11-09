package dev.bedrockadder.common.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bedrockadder.common.Json;
import dev.bedrockadder.common.ResourceId;
import dev.bedrockadder.common.bedrock.GeometryConverter;
import dev.bedrockadder.common.bedrock.IconRenderer;
import dev.bedrockadder.common.bedrock.TextureAtlas;
import dev.bedrockadder.common.bedrock.Textures;
import dev.bedrockadder.common.input.IaItem;
import dev.bedrockadder.common.java.JavaModel;
import dev.bedrockadder.common.spec.ItemSpec;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

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
        // pose carried over by the frame difference of the vanilla shield attachable (its main and off hand
        // first person animations, pivot [1, 15.5, 3]), with the model mirrored by a negative x scale like
        // vanilla does; the Java left hand display is mirrored too (see mirrored()).
        POSES.put("firstperson_off_hand", new BasePose(new float[]{171.05f, 7.7f, 176.42f},
                new float[]{-6.75f, 18.47f, 23.37f}, new float[]{-1.5f, 1.5f, 1.5f}));
        POSES.put("head", new BasePose(new float[]{0, 0, 0}, new float[]{0, 19.9f, 0}, 0.625f));
        // Worn by BedrockAdder's furniture stand: the model centre sits half a block above the stand's feet,
        // like an item_display renders the model centred on its position.
        POSES.put("furniture", new BasePose(new float[]{0, 0, 0}, new float[]{0, 0, 0}, 1));
    }

    /** Bedrock attachable context -> Java display context (and the fallback used when it is absent). */
    private static final Map<String, String[]> JAVA_CONTEXT = Map.of(
            "thirdperson_main_hand", new String[]{"thirdperson_righthand"},
            "thirdperson_off_hand", new String[]{"thirdperson_lefthand", "thirdperson_righthand"},
            "firstperson_main_hand", new String[]{"firstperson_righthand"},
            "firstperson_off_hand", new String[]{"firstperson_lefthand", "firstperson_righthand"},
            "head", new String[]{"head"},
            "furniture", new String[]{"fixed"});

    private static final Map<String, String> CONDITIONS = Map.of(
            "thirdperson_main_hand", "v.main_hand && !c.is_first_person",
            "thirdperson_off_hand", "v.off_hand && !c.is_first_person",
            "firstperson_main_hand", "v.main_hand && c.is_first_person",
            "firstperson_off_hand", "v.off_hand && c.is_first_person",
            "head", "v.head && !v.on_stand",
            "furniture", "v.head && v.on_stand");

    private final ConversionContext ctx;
    private final ArmorConverter armor;

    public ItemConverter(ConversionContext ctx) {
        this.ctx = ctx;
        this.armor = new ArmorConverter(ctx);
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

        boolean handheld = false;
        BufferedImage iconImage;
        if (model != null && model.kind() == JavaModel.Kind.ELEMENTS) {
            iconImage = IconRenderer.render(model, ctx.textures()::load, ctx.options().iconSize());
            ResourceId usingId = ctx.models().usingItemModel(item.itemModel());
            JavaModel using = usingId == null ? null : ctx.models().model(usingId);
            attachable(bedrockId, flat, model, using, item.material().equals("minecraft:shield"));
        } else if (model != null && model.flat()) {
            iconImage = layered(model);
            handheld = model.kind() == JavaModel.Kind.HANDHELD;
        } else {
            String texture = model == null ? null : model.primaryTexture();
            iconImage = texture == null ? null : ctx.textures().load(texture);
        }
        if (empty) {
            iconImage = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB); // drawn by nothing on Java either
        } else if (iconImage == null) {
            iconImage = placeholderIcon();
        }
        String iconPath = "textures/" + ConversionContext.NAMESPACE + "/items/" + flat;
        ctx.bedrockPack().file(iconPath + ".png", Textures.png(iconImage));
        ctx.bedrockPack().itemTexture(icon, iconPath);

        if (item.equipSlot() != null && item.armorAsset() != null) {
            armor.attachable(bedrockId, flat, item.equipSlot(), item.armorAsset());
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
    private void attachable(String bedrockId, String flat, JavaModel model, JavaModel using, boolean shield) {
        Map<String, BufferedImage> textures = new LinkedHashMap<>();
        for (JavaModel.Element element : model.elements()) {
            for (JavaModel.Face face : element.faces().values()) {
                if (face.texture() != null) {
                    textures.computeIfAbsent(face.texture(), ctx.textures()::load);
                }
            }
        }
        TextureAtlas atlas = TextureAtlas.build(textures);
        String texturePath = "textures/" + ConversionContext.NAMESPACE + "/models/" + flat;
        ctx.bedrockPack().file(texturePath + ".png", Textures.png(atlas.image()));

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
            JsonObject display = context.equals("furniture") && "none".equals(ctx.options().furnitureDisplay())
                    ? new JsonObject() : display(model, context);
            animations.add(animationPrefix + context, animation(display, pose.getValue()));
            animationRefs.addProperty(context, animationPrefix + context);
            JsonObject entry = new JsonObject();
            entry.addProperty(context, CONDITIONS.get(context) + (using != null && hand ? " && !v.using" : ""));
            animate.add(entry);
            if (using != null && hand) {
                String usingContext = context + "_using";
                animations.add(animationPrefix + usingContext, animation(display(using, context), pose.getValue()));
                animationRefs.addProperty(usingContext, animationPrefix + usingContext);
                JsonObject usingEntry = new JsonObject();
                usingEntry.addProperty(usingContext, CONDITIONS.get(context) + " && v.using");
                animate.add(usingEntry);
            }
        }
        if (using != null && shield) {
            // Bedrock raises the arm for blocking only with the vanilla shield (animation.player.shield_block_*);
            // the same arm and hand motion is played on the item's own bones in third person.
            animations.add(animationPrefix + "block_main_hand", blockAnimation(GeometryConverter.ARM_RIGHT,
                    new float[]{-20, -30, -25}, new float[]{-1, -3, 0}, new float[]{0, -60, -45}));
            animations.add(animationPrefix + "block_off_hand", blockAnimation(GeometryConverter.ARM_LEFT,
                    new float[]{-20, 20, 20}, new float[]{1, -3, 0}, new float[]{0, 70, 65}));
            for (String hand : new String[]{"main_hand", "off_hand"}) {
                animationRefs.addProperty("block_" + hand, animationPrefix + "block_" + hand);
                JsonObject entry = new JsonObject();
                entry.addProperty("block_" + hand, "v." + hand + " && !c.is_first_person && v.using");
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
        scripts.add("pre_animation", preAnimation);
        scripts.add("animate", animate);
        description.add("scripts", scripts);
        JsonArray renderControllers = new JsonArray();
        renderControllers.add("controller.render.item_default");
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

    private static JsonObject blockAnimation(String armBone, float[] armRotation, float[] handPosition, float[] handRotation) {
        JsonObject bones = new JsonObject();
        JsonObject arm = new JsonObject();
        arm.add("rotation", Json.array(armRotation));
        bones.add(GeometryConverter.ITEM_ROOT + armBone, arm);
        JsonObject hand = new JsonObject();
        hand.add("position", Json.array(handPosition));
        hand.add("rotation", Json.array(handRotation));
        bones.add(GeometryConverter.ITEM_ROOT + GeometryConverter.HAND, hand);
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
        rootBone.add("scale", Json.array(scale[0] * pose.scale()[0], scale[1] * pose.scale()[1], scale[2] * pose.scale()[2]));
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
