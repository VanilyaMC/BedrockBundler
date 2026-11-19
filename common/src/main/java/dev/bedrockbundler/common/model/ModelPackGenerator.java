package dev.bedrockbundler.common.model;

/*
 * Ported from GeyserModelEngine (https://github.com/GeyserExtensionists/GeyserModelEngine), the Geyser extension's
 * resource pack generator (ResourcePackManager, Entity, Geometry, Animation, AnimationController, RenderController,
 * ManualAnimator, Material, ShortHashUtil, BooleanPacker).
 *
 * MIT License, Copyright (c) 2024 zimzaza4. See THIRD_PARTY_NOTICES.md.
 *
 * Changes: no Geyser or GeyserUtils calls, no files or config on disk (everything is built in memory and every
 * collection is ordered, so the same input gives the same bytes); bones and animations get one explicit bit order
 * (sorted names) that is published in ModelSpec; the uv_ bone index follows its parent's bone index; models
 * always carry per texture UV sizes (as the model reader writes them), so the single texture branches are gone.
 * New code by MrMirhan.
 */

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.bedrockbundler.common.Json;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Turns one {@link BedrockModel} into the client entity files of a Bedrock resource pack. */
final class ModelPackGenerator {

    /** Bedrock allows at most 32 entity properties. */
    static final int MAX_PROPERTIES = 32;

    private static final String HEAD_TEMPLATE = """
            {"relative_to":{"rotation":"entity"},
             "rotation":["query.target_x_rotation - this","query.target_y_rotation - this",0.0]}""";

    static final String MATERIAL = """
            {
                "materials":{
                    "version":"1.0.0",
                    "entity_alphatest_anim_change_color:entity_alphatest_change_color":{
                        "+defines":[
                            "USE_UV_ANIM"
                        ]
                    },
                    "entity_change_color_one_sided:entity": {
                        "+defines": [
                            "USE_OVERLAY",
                            "USE_COLOR_MASK"
                        ]
                    },
                    "entity_alphatest_change_color_one_sided:entity_change_color_one_sided": {
                        "+defines": [ "ALPHA_TEST" ],
                        "+samplerStates": [
                            {
                              "samplerIndex": 1,
                              "textureWrap": "Repeat"
                            }
                        ],
                        "msaaSupport": "Both"
                    },
                    "entity_alphatest_anim_change_color_one_sided:entity_alphatest_change_color_one_sided":{
                        "+defines":[
                            "USE_UV_ANIM"
                        ]
                    },
                    "entity_alphablend_change_color_one_sided:entity_alphablend": {
                        "+defines": [
                            "USE_OVERLAY",
                            "USE_COLOR_MASK"
                        ],
                        "+samplerStates": [
                            {
                              "samplerIndex": 1,
                              "textureWrap": "Repeat"
                            }
                        ],
                        "msaaSupport": "Both"
                    }
                }
            }
            """;

    private static final String TINT_SUFFIX = "_tint";
    private static final String TINT_TEXTURE_SUFFIX = "_tintlum";
    private static final String MANUAL_BONE = "root";
    private static final List<String> MANUAL_PROPERTIES =
            List.of("manual_on", "manual_rx", "manual_ry", "manual_rz", "manual_px", "manual_py", "manual_pz");
    private static final int BITS = ModelSpec.BITS_PER_PROPERTY;

    /** The files of a model (path to bytes, in a stable order) and its spec. */
    record Output(Map<String, byte[]> files, ModelSpec spec) {
    }

    private record Bone(String name, String parent) {
    }

    private final ModelOptions opt;
    private final BedrockModel src;
    private final String id;
    private final String animId;
    private final Consumer<String> warn;
    private final Map<String, byte[]> files = new LinkedHashMap<>();

    private JsonObject geometryDoc;
    private JsonObject animationDoc;
    private List<String> animationIds = List.of();
    private boolean hasHeadAnimation;
    private final Map<String, Bone> bones = new LinkedHashMap<>();
    private final Map<String, Set<String>> descendants = new HashMap<>();
    private List<String> boneOrder = List.of();
    private final Map<String, Integer> boneIndex = new HashMap<>();
    private final Map<String, BufferedImage> images = new HashMap<>();
    private final Map<String, List<String[]>> translucentSplits = new LinkedHashMap<>();
    private final Map<String, BedrockModel.Texture> textures = new TreeMap<>();
    private boolean tints;
    private boolean manual;

    private ModelPackGenerator(BedrockModel src, ModelOptions opt, Consumer<String> warn) {
        this.src = src;
        this.opt = opt;
        this.id = src.id();
        this.animId = opt.gmeLayout() ? id : "bb_" + id;
        this.warn = warn;
        src.textures().forEach(t -> textures.put(t.name(), t));
    }

    static Output generate(BedrockModel model, ModelOptions options, Consumer<String> warn) {
        return new ModelPackGenerator(model, options, warn).run();
    }

    private Output run() {
        geometryDoc = src.geometry().deepCopy();
        loadAnimations();
        modifyGeometry();
        decideFeatures();
        if (animationDoc != null) {
            addHeadBind();
            writeAnimations();
        }
        writeGeometries();
        writeTextures();
        writeEntity();
        writeRenderControllers();
        return new Output(files, spec());
    }

    // ---- paths and names -----------------------------------------------------------------------------------

    private String folder(String name) {
        return opt.gmeLayout() ? name + "/" : name + "/bedrockbundler/";
    }

    private String geometryName(BedrockModel.Texture t) {
        return ShortHash.modelId(id + "_" + t.uvWidth() + "_" + t.uvHeight());
    }

    private String textureName(BedrockModel.Texture t) {
        return ShortHash.textureName(id, opt.gmeLayout() ? t.name() : t.name().toLowerCase(Locale.ROOT));
    }

    private String texturePath(String textureName) {
        return (opt.gmeLayout() ? "textures/entity/" : "textures/bedrockbundler/entity/") + textureName;
    }

    private String renderControllerName(String texture) {
        String prefix = opt.gmeLayout() ? "controller.render.meg_" + id : "controller.render.bb_" + id;
        return texture.equals(id) ? prefix : prefix + "_" + (opt.gmeLayout() ? texture : texture.toLowerCase(Locale.ROOT));
    }

    private String animationName(String animation) {
        return "animation." + animId + "." + animation;
    }

    private String controllerName(String animation) {
        return "controller.animation." + animId + "." + animation;
    }

    private void put(String path, JsonElement json) {
        files.put(path, Json.GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
    }

    // ---- animations (Animation, AnimationController) ----------------------------------------------------------

    private void loadAnimations() {
        JsonObject animations = src.animations();
        if (animations.size() == 0) {
            return;
        }
        animationDoc = new JsonObject();
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : animations.deepCopy().entrySet()) {
            ids.add(entry.getKey());
            JsonObject animation = entry.getValue().getAsJsonObject();
            if (animation.has("override_previous_animation")) {
                if (animation.get("override_previous_animation").getAsBoolean() && !animation.has("loop")) {
                    // play once, but an override must hold its last frame to avoid a strange blend
                    animation.addProperty("loop", "hold_on_last_frame");
                }
                animation.remove("override_previous_animation");
            }
            if (animation.has("loop") && animation.get("loop").getAsJsonPrimitive().isString()
                    && animation.get("loop").getAsString().equals("hold_on_last_frame") && animation.has("bones")) {
                holdLastKeyframe(animation.getAsJsonObject("bones"));
            }
            animationDoc.add(animationName(entry.getKey()), animation);
        }
        Collections.sort(ids);
        animationIds = ids;
    }

    /** A held animation ends on its last keyframe, which must not curve into nothing: make it linear. */
    private static void holdLastKeyframe(JsonObject bones) {
        for (Map.Entry<String, JsonElement> bone : bones.entrySet()) {
            for (Map.Entry<String, JsonElement> channel : bone.getValue().getAsJsonObject().entrySet()) {
                if (!channel.getValue().isJsonObject()) {
                    continue;
                }
                float max = -1;
                JsonObject end = null;
                try {
                    for (Map.Entry<String, JsonElement> keyframe : channel.getValue().getAsJsonObject().entrySet()) {
                        float time = Float.parseFloat(keyframe.getKey());
                        if (time > max) {
                            max = time;
                            if (keyframe.getValue().isJsonObject()) {
                                end = keyframe.getValue().getAsJsonObject();
                            }
                        }
                    }
                } catch (NumberFormatException ignored) {
                    // keep what was found so far
                }
                if (end != null && end.has("lerp_mode") && end.get("lerp_mode").getAsString().equals("catmullrom")) {
                    end.addProperty("lerp_mode", "linear");
                }
            }
        }
    }

    /** Bones named {@code h_*} or {@code hi_*} follow the entity's head rotation. */
    private void addHeadBind() {
        JsonObject head = new JsonObject();
        head.addProperty("loop", true);
        JsonObject headBones = new JsonObject();
        int count = 0;
        for (JsonElement element : internal().getAsJsonArray("bones")) {
            JsonObject bone = element.getAsJsonObject();
            String name = bone.get("name").getAsString();
            String parent = bone.has("parent") ? bone.get("parent").getAsString() : "";
            if (parent.startsWith("h_") || parent.startsWith("hi_")) {
                continue;
            }
            if (name.startsWith("h_") || name.startsWith("hi_")) {
                headBones.add(name, JsonParser.parseString(HEAD_TEMPLATE));
                count++;
            }
        }
        if (count == 0) {
            return;
        }
        hasHeadAnimation = true;
        head.add("bones", headBones);
        animationDoc.add(animationName("look_at_target"), head);
    }

    private void writeAnimations() {
        JsonObject doc = new JsonObject();
        doc.addProperty("format_version", "1.8.0");
        doc.add("animations", animationDoc);
        put(folder("animations") + id + ".json", doc);

        JsonObject controllers = new JsonObject();
        for (int i = 0; i < animationIds.size(); i++) {
            String animation = animationIds.get(i);
            String query = "math.mod(math.floor(query.property('" + opt.namespace() + ":anim" + i / BITS + "') / "
                    + (1 << (i % BITS)) + "), 2)";
            JsonObject play = new JsonObject();
            play.add("animations", array(animation));
            play.addProperty("blend_transition", 0.1);
            play.add("transitions", array(single("stop", query + " == 0")));
            JsonObject stop = new JsonObject();
            stop.addProperty("blend_transition", 0.1);
            stop.add("transitions", array(single("play", query + " != 0")));
            JsonObject states = new JsonObject();
            states.add("play", play);
            states.add("stop", stop);
            JsonObject controller = new JsonObject();
            controller.addProperty("initial_state", "stop");
            controller.add("states", states);
            controllers.add(controllerName(animation), controller);
        }
        if (controllers.size() > 0) {
            JsonObject doc2 = new JsonObject();
            doc2.addProperty("format_version", "1.10.0");
            doc2.add("animation_controllers", controllers);
            put(folder("animation_controllers") + id + ".json", doc2);
        }
    }

    // ---- geometry (Geometry, TranslucencySplitter) ---------------------------------------------------------

    private JsonObject internal() {
        return geometryDoc.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
    }

    private static boolean removedBone(String name) {
        return name.equals("hitbox") || name.equals("shadow") || name.equals("mount") || name.startsWith("b_")
                || name.startsWith("ob_");
    }

    /** Lower cases bone names, drops the helper bones the runtime never draws and indexes the rest. */
    private void modifyGeometry() {
        JsonArray array = internal().getAsJsonArray("bones");
        JsonArray kept = new JsonArray();
        for (JsonElement element : array) {
            JsonObject bone = element.getAsJsonObject();
            String name = bone.get("name").getAsString().toLowerCase(Locale.ROOT);
            String parent = bone.has("parent") ? bone.get("parent").getAsString().toLowerCase(Locale.ROOT) : null;
            bone.addProperty("name", name);
            if (removedBone(name)) {
                continue;
            }
            kept.add(bone);
            bones.put(name, new Bone(name, parent));
        }
        internal().add("bones", kept);

        for (Bone bone : bones.values()) {
            Set<String> visited = new HashSet<>();
            for (String cur = bone.parent(); cur != null && bones.containsKey(cur) && visited.add(cur);
                 cur = bones.get(cur).parent()) {
                descendants.computeIfAbsent(cur, k -> new LinkedHashSet<>()).add(bone.name());
            }
        }

        List<String> order = new ArrayList<>();
        for (String name : bones.keySet()) {
            if (!name.startsWith("uv_")) {
                order.add(name);
            }
        }
        Collections.sort(order);
        boneOrder = order;
        for (int i = 0; i < order.size(); i++) {
            boneIndex.put(order.get(i), i);
        }
    }

    private int boneProperties() {
        return ModelSpec.propertyCount(bones.size());
    }

    private int animationProperties() {
        return animationDoc == null ? 0 : ModelSpec.propertyCount(animationIds.size());
    }

    private void decideFeatures() {
        int base = boneProperties() + animationProperties();
        manual = opt.manualAnimator() && bones.containsKey(MANUAL_BONE) && base + MANUAL_PROPERTIES.size() <= MAX_PROPERTIES;
        int withTints = base + (manual ? MANUAL_PROPERTIES.size() : 0) + boneProperties() + 1;
        tints = opt.boneTints() && withTints <= MAX_PROPERTIES;
        if (opt.boneTints() && !tints) {
            warn.accept("bone tints disabled: they would need more than " + MAX_PROPERTIES + " entity properties");
        }
    }

    private void writeGeometries() {
        splitTranslucent();
        Set<String> written = new HashSet<>();
        for (BedrockModel.Texture texture : textures.values()) {
            String name = geometryName(texture);
            if (!written.add(name)) {
                continue;
            }
            JsonObject doc = geometryDoc.deepCopy();
            JsonObject description = doc.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                    .getAsJsonObject("description");
            description.addProperty("texture_width", texture.uvWidth());
            description.addProperty("texture_height", texture.uvHeight());
            description.addProperty("identifier", "geometry." + name);
            put(folder("models/entity") + name + ".json", doc);
            if (tints) {
                put(folder("models/entity") + name + TINT_SUFFIX + ".json", tintCopy(doc));
            }
        }
    }

    /**
     * Copy of a geometry for the "_tintblend" render controller, with every cube inflated a tiny bit so its colour
     * layer sits just in front of the black "_tint" layer instead of fighting it for the same depth.
     */
    private static JsonObject tintCopy(JsonObject doc) {
        JsonObject copy = doc.deepCopy();
        JsonObject internal = copy.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        JsonObject description = internal.getAsJsonObject("description");
        description.addProperty("identifier", description.get("identifier").getAsString() + TINT_SUFFIX);
        for (JsonElement bone : internal.getAsJsonArray("bones")) {
            JsonArray cubes = bone.getAsJsonObject().getAsJsonArray("cubes");
            if (cubes == null) {
                continue;
            }
            for (JsonElement cube : cubes) {
                JsonObject object = cube.getAsJsonObject();
                double inflate = object.has("inflate") ? object.get("inflate").getAsDouble() : 0;
                object.add("inflate", JsonNum.raw(inflate + 0.1));
            }
        }
        return copy;
    }

    // ---- translucency ------------------------------------------------------------------------------------------

    private static final int EMPTY = 0;
    private static final int GLASS = 1;
    private static final int OPAQUE = 2;

    private BufferedImage image(String name) {
        return images.computeIfAbsent(name, key -> {
            BedrockModel.Texture texture = textures.get(key);
            if (texture == null) {
                return null;
            }
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(texture.png()));
                return img == null || !img.getColorModel().hasAlpha() ? null : img;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        });
    }

    private double[] uvScale(BedrockModel.Texture texture, BufferedImage img) {
        double width = texture.uvWidth() > 0 ? texture.uvWidth() : img.getWidth();
        double height = texture.uvHeight() > 0 ? texture.uvHeight() : img.getHeight();
        return new double[]{img.getWidth() / width, img.getHeight() / height};
    }

    private static int cubeClass(JsonObject cube, BufferedImage img, double sx, double sy, double threshold) {
        long[] counts = new long[2];
        JsonElement uv = cube.get("uv");
        if (uv != null && uv.isJsonObject()) {
            for (JsonElement face : uv.getAsJsonObject().asMap().values()) {
                if (!face.isJsonObject() || !face.getAsJsonObject().has("uv") || !face.getAsJsonObject().has("uv_size")) {
                    continue;
                }
                JsonArray origin = face.getAsJsonObject().getAsJsonArray("uv");
                JsonArray size = face.getAsJsonObject().getAsJsonArray("uv_size");
                countFace(img, origin.get(0).getAsDouble(), origin.get(1).getAsDouble(),
                        size.get(0).getAsDouble(), size.get(1).getAsDouble(), sx, sy, counts);
            }
        }
        long visible = counts[0] + counts[1];
        if (visible == 0) {
            return EMPTY;
        }
        return (double) counts[0] / visible >= threshold ? GLASS : OPAQUE;
    }

    private static void countFace(BufferedImage img, double u, double v, double w, double h, double sx, double sy,
                                  long[] counts) {
        int x0 = Math.max(0, (int) Math.floor(Math.min(u, u + w) * sx));
        int x1 = Math.min(img.getWidth(), (int) Math.ceil(Math.max(u, u + w) * sx));
        int y0 = Math.max(0, (int) Math.floor(Math.min(v, v + h) * sy));
        int y1 = Math.min(img.getHeight(), (int) Math.ceil(Math.max(v, v + h) * sy));
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int alpha = (img.getRGB(x, y) >>> 24) & 0xFF;
                if (alpha == 0) {
                    continue;
                }
                if (alpha < 255) {
                    counts[0]++;
                } else {
                    counts[1]++;
                }
            }
        }
    }

    /**
     * Bones that mix glass-like and opaque cubes get their glass cubes moved to a "_t" child bone, which the render
     * controller draws with a blending material.
     */
    private void splitTranslucent() {
        if (!opt.translucentMaterials()) {
            return;
        }
        JsonArray array = internal().getAsJsonArray("bones");
        Map<String, JsonObject> byName = new LinkedHashMap<>();
        for (JsonElement element : array) {
            byName.put(element.getAsJsonObject().get("name").getAsString().toLowerCase(Locale.ROOT), element.getAsJsonObject());
        }
        for (BedrockModel.Texture texture : textures.values()) {
            String key = texture.name();
            if (key.endsWith("_e")) {
                continue;
            }
            BufferedImage img = image(key);
            if (img == null) {
                continue;
            }
            double[] scale = uvScale(texture, img);
            Set<String> bound = lower(texture.bindingBones());
            boolean allBones = bound.contains("*");
            List<JsonObject> added = new ArrayList<>();
            for (JsonObject bone : new ArrayList<>(byName.values())) {
                String name = bone.get("name").getAsString();
                if (name.endsWith("_t") || (!allBones && !bound.contains(name.toLowerCase(Locale.ROOT))) || !bone.has("cubes")) {
                    continue;
                }
                JsonArray glass = new JsonArray();
                JsonArray rest = new JsonArray();
                int opaque = 0;
                for (JsonElement cube : bone.getAsJsonArray("cubes")) {
                    int type = cubeClass(cube.getAsJsonObject(), img, scale[0], scale[1], opt.translucentThreshold());
                    if (type == GLASS) {
                        glass.add(cube);
                    } else {
                        rest.add(cube);
                        if (type == OPAQUE) {
                            opaque++;
                        }
                    }
                }
                if (glass.isEmpty() || opaque == 0) {
                    continue;
                }
                String glassName = name + "_t";
                if (byName.containsKey(glassName.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                bone.add("cubes", rest);
                JsonObject glassBone = new JsonObject();
                glassBone.addProperty("name", glassName);
                glassBone.addProperty("parent", name);
                if (bone.has("pivot")) {
                    glassBone.add("pivot", bone.get("pivot"));
                }
                glassBone.add("cubes", glass);
                added.add(glassBone);
                byName.put(glassName.toLowerCase(Locale.ROOT), glassBone);
                translucentSplits.computeIfAbsent(key, k -> new ArrayList<>()).add(new String[]{glassName, name});
            }
            added.forEach(array::add);
        }
    }

    private static Set<String> lower(Set<String> names) {
        Set<String> result = new HashSet<>();
        names.forEach(n -> result.add(n.toLowerCase(Locale.ROOT)));
        return result;
    }

    private double semiFraction(String name) {
        BufferedImage img = image(name);
        if (img == null) {
            return 0;
        }
        long semi = 0;
        long visible = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int alpha = (img.getRGB(x, y) >>> 24) & 0xFF;
                if (alpha == 0) {
                    continue;
                }
                visible++;
                if (alpha < 255) {
                    semi++;
                }
            }
        }
        return visible == 0 ? 0 : (double) semi / visible;
    }

    private Set<String> translucentBones(BedrockModel.Texture texture) {
        BufferedImage img = image(texture.name());
        if (img == null) {
            return Collections.emptySet();
        }
        double[] scale = uvScale(texture, img);
        Set<String> bound = lower(texture.bindingBones());
        boolean allBones = bound.contains("*");
        Set<String> result = new LinkedHashSet<>();
        for (JsonElement element : internal().getAsJsonArray("bones")) {
            JsonObject bone = element.getAsJsonObject();
            String name = bone.get("name").getAsString();
            if (name.endsWith("_t") || (!allBones && !bound.contains(name.toLowerCase(Locale.ROOT))) || !bone.has("cubes")) {
                continue;
            }
            boolean glass = false;
            boolean opaque = false;
            for (JsonElement cube : bone.getAsJsonArray("cubes")) {
                int type = cubeClass(cube.getAsJsonObject(), img, scale[0], scale[1], opt.translucentThreshold());
                if (type == GLASS) {
                    glass = true;
                } else if (type == OPAQUE) {
                    opaque = true;
                }
            }
            if (glass && !opaque) {
                result.add(name);
            }
        }
        return result;
    }

    // ---- textures ----------------------------------------------------------------------------------------------

    private void writeTextures() {
        for (BedrockModel.Texture texture : textures.values()) {
            String path = texturePath(textureName(texture));
            files.put(path + ".png", texture.png());
            if (tints && !texture.name().endsWith("_e")) {
                byte[] luminance = tintLuminance(texture.png());
                if (luminance != null) {
                    files.put(path + TINT_TEXTURE_SUFFIX + ".png", luminance);
                }
            }
        }
    }

    /**
     * The texture the "_tintblend" render controller draws over a black copy of the tinted bones: white, with the
     * original pixel's brightness as alpha. Blending the tint colour with that alpha gives colour * brightness, like
     * a Java tint (black stays black, shading is kept).
     */
    static byte[] tintLuminance(byte[] png) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(png));
            if (source == null) {
                return null;
            }
            BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    int argb = source.getRGB(x, y);
                    int alpha = (argb >>> 24) & 0xFF;
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    // Same cut-out as the alpha-tested material: pixels under half alpha are not drawn at all
                    int luminance = alpha < 128 ? 0 : Math.round(0.299f * r + 0.587f * g + 0.114f * b);
                    result.setRGB(x, y, (luminance << 24) | 0xFFFFFF);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(result, "png", out);
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ---- client entity (Entity, ManualAnimator) -------------------------------------------------------------

    private void writeEntity() {
        JsonObject materials = new JsonObject();
        materials.addProperty("default", "entity_alphatest_change_color_one_sided");
        materials.addProperty("anim", "entity_alphatest_anim_change_color_one_sided");
        materials.addProperty("blend", "entity_alphablend_change_color_one_sided");
        materials.addProperty("tintblend", "entity_alphablend");
        JsonObject entityTextures = new JsonObject();
        JsonObject geometry = new JsonObject();
        JsonObject animations = new JsonObject();
        animations.addProperty("look_at_target",
                hasHeadAnimation ? animationName("look_at_target") : "animation.common.look_at_target");
        JsonArray animate = new JsonArray();
        animate.add("look_at_target");
        JsonArray renderControllers = new JsonArray();

        for (BedrockModel.Texture texture : textures.values()) {
            if (texture.name().endsWith("_e")) {
                continue;
            }
            String geoName = geometryName(texture);
            String name = textureName(texture);
            geometry.addProperty(geoName, "geometry." + geoName);
            entityTextures.addProperty(name, texturePath(name));
            if (tints) {
                geometry.addProperty(geoName + TINT_SUFFIX, "geometry." + geoName + TINT_SUFFIX);
                entityTextures.addProperty(name + TINT_TEXTURE_SUFFIX, texturePath(name) + TINT_TEXTURE_SUFFIX);
            }
            String controller = renderControllerName(texture.name());
            renderControllers.add(controller);
            if (tints) {
                renderControllers.add(controller + "_tint");
                renderControllers.add(controller + "_tintblend");
            }
        }
        for (String animation : animationIds) {
            animate.add(animation + "_control");
            animations.addProperty(animation, animationName(animation));
            animations.addProperty(animation + "_control", controllerName(animation));
        }
        if (manual) {
            // Last, so it replaces what the other animations did to the root bone
            animations.addProperty("manual_root", animationName("manual_root"));
            JsonObject active = new JsonObject();
            active.addProperty("manual_root", "query.property('" + opt.namespace() + ":manual_on') == 1");
            animate.add(active);
            put(folder("animations") + id + "_manual.json", manualAnimation());
        }

        JsonObject scripts = new JsonObject();
        scripts.add("animate", animate);
        JsonObject description = new JsonObject();
        description.addProperty("identifier", opt.namespace() + ":" + id);
        description.add("materials", materials);
        description.add("textures", entityTextures);
        description.add("geometry", geometry);
        description.add("animations", animations);
        description.add("scripts", scripts);
        description.add("render_controllers", renderControllers);
        JsonObject clientEntity = new JsonObject();
        clientEntity.add("description", description);
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.10.0");
        root.add("minecraft:client_entity", clientEntity);
        put(folder("entity") + id + ".json", root);
    }

    /**
     * Bedrock side of ModelEngine's manual bone animator on the root bone. The runtime sends the pose as int
     * properties: manual_on (0/1), manual_rx/ry/rz (Bedrock euler degrees x100) and manual_px/py/pz (Bedrock
     * pixels x100). The animation applies them to the root bone and replaces the bone's other animations while
     * manual_on is 1, like ModelEngine's override.
     */
    private JsonObject manualAnimation() {
        JsonObject bone = new JsonObject();
        bone.add("rotation", manualVector("manual_rx", "manual_ry", "manual_rz"));
        bone.add("position", manualVector("manual_px", "manual_py", "manual_pz"));
        JsonObject boneMap = new JsonObject();
        boneMap.add(MANUAL_BONE, bone);
        JsonObject animation = new JsonObject();
        animation.addProperty("loop", true);
        animation.addProperty("override_previous_animation", true);
        animation.add("bones", boneMap);
        JsonObject animations = new JsonObject();
        animations.add(animationName("manual_root"), animation);
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.8.0");
        root.add("animations", animations);
        return root;
    }

    private JsonArray manualVector(String x, String y, String z) {
        JsonArray vector = new JsonArray();
        for (String property : new String[]{x, y, z}) {
            vector.add("query.property('" + opt.namespace() + ":" + property + "') / 100");
        }
        return vector;
    }

    // ---- render controllers (RenderController) ---------------------------------------------------------------

    /**
     * True while bit {@code index} of {@code <property><k>} is set; for {@code bone} the bit marks a HIDDEN bone
     * (what the runtime sends: BetterModel reports the bones it hides), so a bone shows while its bit is clear and a
     * model with no state yet (all properties 0) shows every bone.
     */
    private String visibleExpression(String property, int index) {
        return "math.mod(math.floor(query.property('" + opt.namespace() + ":" + property + index / BITS + "') / "
                + (1 << (index % BITS)) + "), 2) == " + (property.equals("bone") ? 0 : 1);
    }

    private void writeRenderControllers() {
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.8.0");
        JsonObject controllers = new JsonObject();
        root.add("render_controllers", controllers);

        List<String> sortedAll = new ArrayList<>(bones.keySet());
        Collections.sort(sortedAll);
        Set<String> processed = new HashSet<>();
        Map<String, Set<String>> bindings = new LinkedHashMap<>();
        textures.values().forEach(t -> bindings.put(t.name(), t.bindingBones()));

        for (BedrockModel.Texture texture : textures.values()) {
            String key = texture.name();
            if (key.endsWith("_e")) {
                continue;
            }
            Set<String> uvBonesId = texture.bindingBones();
            JsonObject controller = new JsonObject();
            String controllerName = renderControllerName(key);
            controllers.add(controllerName, controller);
            controller.addProperty("geometry", "Geometry." + geometryName(texture));

            JsonArray materials = new JsonArray();
            JsonObject materialItem = new JsonObject();
            boolean defaultMaterial = false;
            if (opt.translucentMaterials() && semiFraction(key) >= 0.999) {
                materialItem.addProperty("*", "Material.blend");
            } else {
                materialItem.addProperty("*", "Material.default");
                defaultMaterial = true;
            }
            materials.add(materialItem);
            List<String[]> splits = translucentSplits.getOrDefault(key, List.of());
            if (opt.translucentMaterials() && defaultMaterial) {
                for (String glassBone : translucentBones(texture)) {
                    materials.add(single(glassBone, "Material.blend"));
                }
                for (String[] split : splits) {
                    materials.add(single(split[0], "Material.blend"));
                }
            }
            controller.add("materials", materials);

            JsonArray textureList = new JsonArray();
            textureList.add("Texture." + textureName(texture));
            controller.add("textures", textureList);

            JsonArray partVisibility = new JsonArray();
            partVisibility.add(single("*", false));
            JsonArray tintVisibility = new JsonArray();
            tintVisibility.add(single("*", false));
            Map<String, JsonElement> capturedVisibility = new HashMap<>();
            Map<String, JsonElement> capturedTintVisibility = new HashMap<>();

            Set<String> uvAllBones = new HashSet<>();
            for (String uvBone : uvBonesId) {
                if (uvBone.equals("*")) {
                    uvAllBones.addAll(bones.keySet());
                }
                if (bones.containsKey(uvBone.toLowerCase(Locale.ROOT))) {
                    uvAllBones.add(uvBone.toLowerCase(Locale.ROOT));
                }
            }
            boolean all = uvBonesId.contains("*");

            for (String boneName : sortedAll) {
                boolean uvParent = false;
                for (String child : descendants.getOrDefault(boneName, Set.of())) {
                    if (child.startsWith("uv_") && uvAllBones.contains(child)) {
                        uvParent = true;
                    }
                }
                for (Map.Entry<String, Set<String>> entry : bindings.entrySet()) {
                    if (entry.getKey().equals(key)) {
                        continue;
                    }
                    if (entry.getValue().stream().anyMatch(boneName::equalsIgnoreCase)) {
                        uvParent = false;
                        break;
                    }
                }
                if (processed.contains(boneName) || !(uvParent || uvAllBones.contains(boneName) || all)) {
                    continue;
                }
                Integer index = boneIndex.get(boneName.startsWith("uv_") ? bones.get(boneName).parent() : boneName);
                if (index == null) {
                    continue; // a uv_ bone without a drawn parent has nothing to follow
                }
                String visible = visibleExpression("bone", index);
                JsonObject visibilityItem = new JsonObject();
                if (tints) {
                    // Tinted bones move from this controller to its "_tint" twin
                    String tinted = visibleExpression("tintmask", index);
                    visibilityItem.addProperty(boneName, "(" + visible + ") && !(" + tinted + ")");
                    JsonObject tintItem = new JsonObject();
                    tintItem.addProperty(boneName, "(" + visible + ") && (" + tinted + ")");
                    tintVisibility.add(tintItem);
                    capturedTintVisibility.put(boneName, tintItem.get(boneName));
                } else {
                    visibilityItem.addProperty(boneName, visible);
                }
                partVisibility.add(visibilityItem);
                capturedVisibility.put(boneName, visibilityItem.get(boneName));
                if (!all) {
                    processed.add(boneName);
                }
            }
            for (String[] split : splits) {
                JsonElement visibility = capturedVisibility.get(split[1]);
                if (visibility != null) {
                    JsonObject item = new JsonObject();
                    item.add(split[0], visibility.deepCopy());
                    partVisibility.add(item);
                }
                JsonElement tintVis = capturedTintVisibility.get(split[1]);
                if (tintVis != null) {
                    JsonObject item = new JsonObject();
                    item.add(split[0], tintVis.deepCopy());
                    tintVisibility.add(item);
                }
            }
            controller.add("part_visibility", partVisibility);

            if (tints) {
                // Bedrock ignores a render controller's "color" (MCPE-183985) and cannot multiply colours, so a tint
                // is built from two layers: "_tint" draws the tinted bones black, then "_tintblend" blends the tint
                // colour over them with each pixel's brightness as alpha. That gives colour * brightness, like a
                // Java tint. "_tintblend" uses slightly inflated geometry so it is not hidden behind the black layer.
                JsonObject tintController = controller.deepCopy();
                tintController.add("part_visibility", tintVisibility);
                tintController.add("overlay_color", tintColor(false));
                controllers.add(controllerName + "_tint", tintController);

                JsonObject blendController = controller.deepCopy();
                blendController.add("part_visibility", tintVisibility.deepCopy());
                blendController.add("overlay_color", tintColor(true));
                blendController.addProperty("geometry", controller.get("geometry").getAsString() + TINT_SUFFIX);
                blendController.add("textures", array("Texture." + textureName(texture) + TINT_TEXTURE_SUFFIX));
                blendController.add("materials", array(single("*", "Material.tintblend")));
                controllers.add(controllerName + "_tintblend", blendController);
            }
        }
        put(folder("render_controllers") + id + ".json", root);
    }

    /** Overlay colour of the tint layers: black for "_tint", or the colour the runtime sends (6 bits per channel). */
    private JsonObject tintColor(boolean colored) {
        String property = "query.property('" + opt.namespace() + ":tintcolor')";
        JsonObject overlay = new JsonObject();
        if (colored) {
            overlay.addProperty("r", "math.floor(" + property + " / 4096) / 63");
            overlay.addProperty("g", "math.mod(math.floor(" + property + " / 64), 64) / 63");
            overlay.addProperty("b", "math.mod(" + property + ", 64) / 63");
        } else {
            overlay.addProperty("r", 0.0);
            overlay.addProperty("g", 0.0);
            overlay.addProperty("b", 0.0);
        }
        overlay.addProperty("a", 1.0);
        return overlay;
    }

    // ---- spec ----------------------------------------------------------------------------------------------------

    private ModelSpec spec() {
        String ns = opt.namespace() + ":";
        int maxMask = (1 << BITS) - 1;
        List<ModelSpec.Property> properties = new ArrayList<>();
        for (int i = 0; i < boneProperties(); i++) {
            properties.add(new ModelSpec.Property(ns + "bone" + i, 0, maxMask, 0));
        }
        for (int i = 0; i < animationProperties(); i++) {
            properties.add(new ModelSpec.Property(ns + "anim" + i, 0, maxMask, 0));
        }
        if (tints) {
            for (int i = 0; i < boneProperties(); i++) {
                properties.add(new ModelSpec.Property(ns + "tintmask" + i, 0, maxMask, 0));
            }
            properties.add(new ModelSpec.Property(ns + "tintcolor", 0, (1 << 18) - 1, 0));
        }
        if (manual) {
            for (String property : MANUAL_PROPERTIES) {
                properties.add(property.equals("manual_on")
                        ? new ModelSpec.Property(ns + property, 0, 1, 0)
                        : new ModelSpec.Property(ns + property, -1_000_000, 1_000_000, 0));
            }
        }
        return new ModelSpec(id, opt.namespace() + ":" + id, boneOrder, animationIds, properties,
                ModelSpec.DEFAULT_HITBOX, ModelSpec.DEFAULT_HITBOX);
    }

    // ---- small JSON helpers -------------------------------------------------------------------------------------

    private static JsonObject single(String key, String value) {
        JsonObject object = new JsonObject();
        object.addProperty(key, value);
        return object;
    }

    private static JsonObject single(String key, boolean value) {
        JsonObject object = new JsonObject();
        object.addProperty(key, value);
        return object;
    }

    private static JsonArray array(String value) {
        JsonArray array = new JsonArray();
        array.add(value);
        return array;
    }

    private static JsonArray array(JsonElement value) {
        JsonArray array = new JsonArray();
        array.add(value);
        return array;
    }
}
