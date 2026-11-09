package dev.bedrockadder.common.java;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.bedrockadder.common.ConversionLog;
import dev.bedrockadder.common.Json;
import dev.bedrockadder.common.ResourceId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves item model definitions, blockstates and model parent chains of a {@link JavaPack}. */
public final class ModelResolver {

    private static final String[] FACES = {"down", "up", "north", "south", "west", "east"};

    private final JavaPack pack;
    private final ConversionLog log;
    private final Map<String, JavaModel> cache = new HashMap<>();

    public ModelResolver(JavaPack pack, ConversionLog log) {
        this.pack = pack;
        this.log = log;
    }

    // ---------------------------------------------------------------- items

    /**
     * Finds the model an item renders with.
     *
     * @param itemModel       {@code item_model} component, or null
     * @param material        vanilla base item, used for CustomModelData lookups
     * @param customModelData legacy CustomModelData, or null
     * @return the model id, or null when nothing matches
     */
    public ResourceId itemModel(String itemModel, String material, Integer customModelData) {
        if (itemModel != null) {
            ResourceId id = ResourceId.parse(itemModel);
            JsonObject definition = pack.json(id.asset("items", ".json"));
            if (definition != null) {
                return pick(Json.object(definition, "model"), customModelData);
            }
            // item_model pointing straight at a model file
            if (pack.has(id.asset("models", ".json"))) {
                return id;
            }
            return null;
        }
        if (customModelData == null) {
            return null;
        }
        ResourceId base = ResourceId.parse(material);
        JsonObject definition = pack.json(base.asset("items", ".json"));
        if (definition != null) {
            ResourceId picked = pick(Json.object(definition, "model"), customModelData);
            // The fallback of a vanilla definition is the vanilla model, which is not a match.
            if (picked != null && !picked.equals(new ResourceId("minecraft", "item/" + base.path()))) {
                return picked;
            }
        }
        // Pre-1.21.4 style overrides
        JsonObject legacy = pack.json(new ResourceId(base.namespace(), "item/" + base.path()).asset("models", ".json"));
        JsonArray overrides = Json.array(legacy, "overrides");
        if (overrides != null) {
            for (JsonElement element : overrides) {
                JsonObject override = element.getAsJsonObject();
                JsonObject predicate = Json.object(override, "predicate");
                if (predicate != null && predicate.has("custom_model_data")
                        && predicate.get("custom_model_data").getAsInt() == customModelData) {
                    return ResourceId.parse(Json.string(override, "model", ""));
                }
            }
        }
        return null;
    }

    /**
     * Whether an item draws nothing on purpose ({@code "type": "empty"}), like the invisible buttons of menus whose
     * background glyph already shows them.
     */
    public boolean isEmpty(String itemModel) {
        if (itemModel == null) {
            return false;
        }
        JsonObject definition = pack.json(ResourceId.parse(itemModel).asset("items", ".json"));
        JsonObject model = Json.object(definition, "model");
        return model != null && Json.string(model, "type", "").replace("minecraft:", "").equals("empty");
    }

    /**
     * The model an item switches to while it is used ({@code "type": "condition", "property": "using_item"}):
     * a raised shield, a trident being charged. Null when the item has none.
     */
    public ResourceId usingItemModel(String itemModel) {
        if (itemModel == null) {
            return null;
        }
        JsonObject definition = pack.json(ResourceId.parse(itemModel).asset("items", ".json"));
        JsonObject node = Json.object(definition, "model");
        if (node == null || !Json.string(node, "type", "").replace("minecraft:", "").equals("condition")
                || !Json.string(node, "property", "").replace("minecraft:", "").equals("using_item")) {
            return null;
        }
        return pick(Json.object(node, "on_true"), null);
    }

    /** Walks a 1.21.4+ item model definition tree down to a plain model reference. */
    private ResourceId pick(JsonObject node, Integer customModelData) {
        if (node == null) {
            return null;
        }
        String type = Json.string(node, "type", "minecraft:model").replace("minecraft:", "");
        switch (type) {
            case "model" -> {
                String model = Json.string(node, "model", null);
                return model == null ? null : ResourceId.parse(model);
            }
            case "range_dispatch" -> {
                JsonArray entries = Json.array(node, "entries");
                String property = Json.string(node, "property", "").replace("minecraft:", "");
                if (entries != null && property.equals("custom_model_data") && customModelData != null) {
                    JsonObject best = null;
                    float bestThreshold = Float.NEGATIVE_INFINITY;
                    for (JsonElement element : entries) {
                        JsonObject entry = element.getAsJsonObject();
                        float threshold = Json.number(entry, "threshold", Float.NEGATIVE_INFINITY);
                        if (threshold <= customModelData && threshold > bestThreshold) {
                            best = entry;
                            bestThreshold = threshold;
                        }
                    }
                    if (best != null) {
                        return pick(Json.object(best, "model"), customModelData);
                    }
                }
                JsonObject fallback = Json.object(node, "fallback");
                if (fallback != null) {
                    return pick(fallback, customModelData);
                }
                return entries != null && !entries.isEmpty()
                        ? pick(Json.object(entries.get(0).getAsJsonObject(), "model"), customModelData) : null;
            }
            case "select" -> {
                JsonObject fallback = Json.object(node, "fallback");
                if (fallback != null) {
                    return pick(fallback, customModelData);
                }
                JsonArray cases = Json.array(node, "cases");
                return cases != null && !cases.isEmpty()
                        ? pick(Json.object(cases.get(0).getAsJsonObject(), "model"), customModelData) : null;
            }
            case "condition" -> {
                ResourceId onFalse = pick(Json.object(node, "on_false"), customModelData);
                return onFalse != null ? onFalse : pick(Json.object(node, "on_true"), customModelData);
            }
            case "composite" -> {
                JsonArray models = Json.array(node, "models");
                return models != null && !models.isEmpty() ? pick(models.get(0).getAsJsonObject(), customModelData) : null;
            }
            case "special" -> {
                String base = Json.string(node, "base", null);
                return base == null ? null : ResourceId.parse(base);
            }
            default -> {
                return null;
            }
        }
    }

    // ---------------------------------------------------------------- blocks

    /** A blockstate variant: model plus its rotation in 90 degree steps. */
    public record Variant(ResourceId model, int x, int y) {
    }

    /** Finds the blockstate variant for a full Java block state string. */
    public Variant blockVariant(String javaState) {
        int bracket = javaState.indexOf('[');
        ResourceId block = ResourceId.parse(bracket < 0 ? javaState : javaState.substring(0, bracket));
        Map<String, String> state = bracket < 0 ? Map.of() : parseProperties(javaState.substring(bracket + 1, javaState.length() - 1));
        JsonObject blockstate = pack.json(block.asset("blockstates", ".json"));
        JsonObject variants = Json.object(blockstate, "variants");
        if (variants == null) {
            return null;
        }
        for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {
            Map<String, String> required = parseProperties(entry.getKey());
            boolean matches = true;
            for (Map.Entry<String, String> property : required.entrySet()) {
                if (!property.getValue().equals(state.get(property.getKey()))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                JsonElement value = entry.getValue();
                JsonObject variant = value.isJsonArray() ? value.getAsJsonArray().get(0).getAsJsonObject() : value.getAsJsonObject();
                String model = Json.string(variant, "model", null);
                if (model == null) {
                    return null;
                }
                return new Variant(ResourceId.parse(model), (int) Json.number(variant, "x", 0), (int) Json.number(variant, "y", 0));
            }
        }
        return null;
    }

    public static Map<String, String> parseProperties(String properties) {
        Map<String, String> result = new LinkedHashMap<>();
        if (properties.isBlank()) {
            return result;
        }
        for (String pair : properties.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                result.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- models

    public JavaModel model(ResourceId id) {
        return cache.computeIfAbsent(id.toString(), key -> resolve(id));
    }

    private JavaModel resolve(ResourceId id) {
        Map<String, String> textures = new LinkedHashMap<>();
        Map<String, JsonObject> display = new LinkedHashMap<>();
        JsonArray elements = null;
        JavaModel.Kind flatKind = null;
        boolean missing = false;

        ResourceId current = id;
        for (int depth = 0; current != null && depth < 32; depth++) {
            String vanillaName = current.namespace().equals("minecraft") ? current.path() : null;
            if (vanillaName != null && BuiltinModels.GENERATED.contains(vanillaName)) {
                flatKind = flatKind == null ? JavaModel.Kind.GENERATED : flatKind;
                break;
            }
            if (vanillaName != null && BuiltinModels.HANDHELD.contains(vanillaName)) {
                flatKind = JavaModel.Kind.HANDHELD;
                break;
            }
            JsonObject json = pack.json(current.asset("models", ".json"));
            if (json == null && vanillaName != null) {
                json = BuiltinModels.get(vanillaName);
            }
            if (json == null) {
                log.warn("Missing model " + current + (current.equals(id) ? "" : " (parent of " + id + ")"));
                missing = true;
                break;
            }
            JsonObject jsonTextures = Json.object(json, "textures");
            if (jsonTextures != null) {
                for (Map.Entry<String, JsonElement> entry : jsonTextures.entrySet()) {
                    textures.putIfAbsent(entry.getKey(), entry.getValue().getAsString());
                }
            }
            JsonObject jsonDisplay = Json.object(json, "display");
            if (jsonDisplay != null) {
                for (Map.Entry<String, JsonElement> entry : jsonDisplay.entrySet()) {
                    if (entry.getValue().isJsonObject()) {
                        display.putIfAbsent(entry.getKey(), entry.getValue().getAsJsonObject());
                    }
                }
            }
            if (elements == null) {
                elements = Json.array(json, "elements");
            }
            String parent = Json.string(json, "parent", null);
            current = parent == null ? null : ResourceId.parse(parent);
        }

        Map<String, String> resolved = new LinkedHashMap<>();
        for (String key : textures.keySet()) {
            String texture = resolveTexture(textures, key);
            if (texture != null) {
                resolved.put(key, pack.resolveSprite(texture));
            }
        }

        if (elements != null) {
            return new JavaModel(JavaModel.Kind.ELEMENTS, resolved, parseElements(elements, textures), display);
        }
        if (flatKind != null) {
            return new JavaModel(flatKind, resolved, List.of(), display);
        }
        if (!missing) {
            log.warn("Model " + id + " has neither elements nor a generated parent");
        }
        return new JavaModel(JavaModel.Kind.MISSING, resolved, List.of(), display);
    }

    private static String resolveTexture(Map<String, String> textures, String key) {
        String value = textures.get(key);
        for (int i = 0; value != null && value.startsWith("#") && i < 16; i++) {
            value = textures.get(value.substring(1));
        }
        return value == null || value.startsWith("#") ? null : value;
    }

    private List<JavaModel.Element> parseElements(JsonArray array, Map<String, String> textures) {
        List<JavaModel.Element> elements = new ArrayList<>();
        for (JsonElement raw : array) {
            JsonObject json = raw.getAsJsonObject();
            float[] from = Json.floats(Json.array(json, "from"), 3, 0);
            float[] to = Json.floats(Json.array(json, "to"), 3, 16);

            JavaModel.Rotation rotation = null;
            JsonObject rot = Json.object(json, "rotation");
            if (rot != null && Json.number(rot, "angle", 0) != 0) {
                rotation = new JavaModel.Rotation(Json.floats(Json.array(rot, "origin"), 3, 8),
                        Json.string(rot, "axis", "y"), Json.number(rot, "angle", 0),
                        rot.has("rescale") && rot.get("rescale").getAsBoolean());
            }

            Map<String, JavaModel.Face> faces = new LinkedHashMap<>();
            JsonObject jsonFaces = Json.object(json, "faces");
            for (String name : FACES) {
                JsonObject face = Json.object(jsonFaces, name);
                if (face == null) {
                    continue;
                }
                JsonArray uvArray = Json.array(face, "uv");
                float[] uv = uvArray != null ? Json.floats(uvArray, 4, 0) : autoUv(name, from, to);
                String var = Json.string(face, "texture", null);
                String texture = null;
                if (var != null) {
                    String plain = var.startsWith("#") ? var.substring(1) : null;
                    texture = plain != null ? resolveTexture(textures, plain) : var;
                    if (texture != null) {
                        texture = pack.resolveSprite(texture);
                    }
                    var = plain != null ? plain : var;
                }
                faces.put(name, new JavaModel.Face(uv, texture, var, ((int) Json.number(face, "rotation", 0) % 360 + 360) % 360,
                        (int) Json.number(face, "tintindex", -1)));
            }
            elements.add(new JavaModel.Element(from, to, rotation, faces));
        }
        return elements;
    }

    /** Java's automatic UVs for faces without an explicit {@code uv}. */
    static float[] autoUv(String face, float[] from, float[] to) {
        return switch (face) {
            case "down" -> new float[]{from[0], 16 - to[2], to[0], 16 - from[2]};
            case "up" -> new float[]{from[0], from[2], to[0], to[2]};
            case "north" -> new float[]{16 - to[0], 16 - to[1], 16 - from[0], 16 - from[1]};
            case "south" -> new float[]{from[0], 16 - to[1], to[0], 16 - from[1]};
            case "west" -> new float[]{from[2], 16 - to[1], to[2], 16 - from[1]};
            case "east" -> new float[]{16 - to[2], 16 - to[1], 16 - from[2], 16 - from[1]};
            default -> new float[]{0, 0, 16, 16};
        };
    }
}
