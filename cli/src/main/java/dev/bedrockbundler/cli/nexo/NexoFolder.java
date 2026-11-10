package dev.bedrockbundler.cli.nexo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.bedrockbundler.common.Json;
import dev.bedrockbundler.common.ResourceId;
import dev.bedrockbundler.common.convert.GlyphTitleOffsets;
import dev.bedrockbundler.common.input.ConversionInput;
import dev.bedrockbundler.common.input.IaBlock;
import dev.bedrockbundler.common.input.IaItem;
import dev.bedrockbundler.common.java.JavaPack;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Reads a Nexo plugin folder ({@code plugins/Nexo}) without a running server: item and glyph configs plus the
 * resource pack. When Nexo's generated pack zip is given it is used as is; otherwise the pack is assembled from
 * {@code pack/assets} and {@code pack/external_packs}, and the files Nexo would generate for custom blocks
 * (block state variants) and glyphs (font providers) are added.
 *
 * <p>Only what the converter needs is read. Item ids, block states and model lookups follow Nexo 1.30
 * ({@code BlockVariants.model}: a {@code custom_block.model} value is a resource location, so a bare name means
 * the {@code minecraft} namespace).
 */
public final class NexoFolder {

    private final Path folder;
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, Map<String, Object>> items = new TreeMap<>();
    private final Map<String, Map<String, Object>> glyphs = new TreeMap<>();

    public NexoFolder(Path folder) throws IOException {
        this.folder = folder;
        readSections(folder.resolve("items"), items);
        readSections(folder.resolve("glyphs"), glyphs);
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /**
     * Shifts Nexo's menu titles put in front of GUI glyphs ({@code <shift:-37><glyph:menu_items>}), from the YAML
     * files of the Nexo folder; see {@link GlyphTitleOffsets}.
     */
    public Map<Integer, Integer> glyphOffsets() throws IOException {
        List<String> texts = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                try {
                    String text = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
                    if (text.contains("<shift:")) {
                        texts.add(text);
                    }
                } catch (IOException | RuntimeException ignored) {
                    // not UTF-8 text
                }
            }
        }
        return GlyphTitleOffsets.find(texts, id -> {
            Map<String, Object> glyph = glyphs.get(id);
            Object chars = glyph == null ? null : glyph.get("char");
            if (chars == null && glyph != null && glyph.get("chars") instanceof List<?> rows && !rows.isEmpty()) {
                chars = rows.get(0);
            }
            return chars == null ? null : String.valueOf(chars);
        });
    }

    public int itemCount() {
        return items.size();
    }

    // ------------------------------------------------------------------ configs

    private void readSections(Path root, Map<String, Map<String, Object>> into) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        for (Path file : files) {
            Object loaded;
            try (InputStream in = Files.newInputStream(file)) {
                loaded = yaml.load(in);
            } catch (RuntimeException e) {
                warnings.add("Could not parse " + root.relativize(file) + ": " + e.getMessage());
                continue;
            }
            if (!(loaded instanceof Map<?, ?> map)) {
                continue;
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> section) {
                    String id = String.valueOf(entry.getKey());
                    if (into.put(id, cast(section)) != null) {
                        warnings.add("Duplicate id " + id + " in " + root.relativize(file) + "; the later one wins");
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ conversion input

    /**
     * @param generatedPack Nexo's generated pack ({@code pack/pack.zip}), or null to assemble it from the folder
     */
    public ConversionInput input(Path generatedPack) throws IOException {
        Map<String, byte[]> pack;
        boolean synthesize = generatedPack == null;
        if (generatedPack != null) {
            pack = Files.isDirectory(generatedPack) ? readDirectory(generatedPack) : JavaPack.readZip(generatedPack);
        } else {
            pack = assemblePack();
        }

        List<IaItem> itemList = new ArrayList<>();
        List<IaBlock> blockList = new ArrayList<>();
        Map<String, JsonObject> blockstates = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : items.entrySet()) {
            String id = entry.getKey();
            Map<String, Object> config = entry.getValue();
            IaItem item = item(id, config, pack, synthesize);
            if (item != null) {
                itemList.add(item);
            }
            Map<String, Object> block = section(section(config, "Mechanics"), "custom_block");
            if (block != null) {
                IaBlock iaBlock = block(id, config, block, pack, synthesize ? blockstates : null);
                if (iaBlock != null) {
                    blockList.add(iaBlock);
                }
            }
        }
        if (synthesize) {
            blockstates.forEach((path, json) -> pack.put(path, Json.bytes(json)));
            addGlyphFonts(pack);
        }
        return new ConversionInput(pack, itemList, blockList);
    }

    private IaItem item(String id, Map<String, Object> config, Map<String, byte[]> pack, boolean synthesize) {
        String material = "minecraft:" + string(config, "material", "PAPER").toLowerCase(Locale.ROOT);
        Map<String, Object> components = section(config, "Components");
        Map<String, Object> packSection = section(config, "Pack");

        String itemModel = string(components, "item_model", null);
        Integer customModelData = integer(packSection, "custom_model_data");
        if (synthesize) {
            itemModel = synthesizeItemDefinition(id, itemModel, customModelData, packSection, pack);
        }
        if (itemModel == null && customModelData == null) {
            // With Pack.generation.prefer_item_models Nexo gives every item nexo:<id>.
            String fallback = "nexo:" + id;
            if (pack.containsKey(ResourceId.parse(fallback).asset("items", ".json"))) {
                itemModel = fallback;
            } else if (packSection != null) {
                warnings.add("Item " + id + " has no Components.item_model or Pack.custom_model_data; convert Nexo's "
                        + "generated pack (--pack plugins/Nexo/pack/pack.zip) to include it");
            }
        }
        if (itemModel == null && customModelData == null) {
            return null;
        }

        Map<String, Object> equippable = section(components, "equippable");
        String slot = equippable == null ? null : string(equippable, "slot", null);
        String equipSlot = slot == null ? null : switch (slot.toUpperCase(Locale.ROOT)) {
            case "HEAD" -> "head";
            case "CHEST" -> "chest";
            case "LEGS" -> "legs";
            case "FEET" -> "feet";
            case "BODY" -> "body";
            default -> null;
        };
        String armorAsset = equippable == null ? null : string(equippable, "asset_id", null);
        Map<String, Object> customArmor = section(config, "CustomArmor");
        if (armorAsset == null && customArmor != null && string(customArmor, "id", null) != null) {
            armorAsset = "nexo:" + string(customArmor, "id", null);
        }

        Map<String, Object> foodSection = section(components, "food");
        IaItem.Food food = foodSection == null ? null : new IaItem.Food(
                integerOr(foodSection, "nutrition", 0),
                (float) number(foodSection, "saturation", 0),
                Boolean.TRUE.equals(foodSection.get("can_always_eat")));

        Integer maxDamage = integer(components, "max_damage");
        if (maxDamage == null) {
            maxDamage = integer(components, "durability");
        }
        Map<String, Object> mechanics = section(config, "Mechanics");
        return new IaItem(
                "nexo:" + id,
                material,
                itemModel,
                itemModel == null ? customModelData : null,
                plain(string(config, "itemname", string(config, "displayname", null))),
                integer(components, "max_stack_size"),
                maxDamage,
                equipSlot,
                armorAsset,
                0,
                food,
                section(mechanics, "custom_block") != null,
                section(mechanics, "furniture") != null,
                thrownDisplay(section(mechanics, "trident")));
    }

    /** Java display context of a thrown custom trident ({@code Mechanics.trident.display_transform}, default NONE). */
    private static String thrownDisplay(Map<String, Object> trident) {
        return trident == null ? null : string(trident, "display_transform", "NONE").toLowerCase(Locale.ROOT);
    }

    /**
     * Adds the item definition Nexo would generate when the pack has none: {@code items/<item_model>.json} pointing
     * at {@code Pack.model}, or, for an item with only {@code Pack.texture}, a {@code nexo:<id>} definition and an
     * {@code item/generated} model (Nexo does this with {@code prefer_item_models}). Files already in the pack win.
     *
     * @return the item model id to use (unchanged when nothing was added)
     */
    private String synthesizeItemDefinition(String id, String itemModel, Integer customModelData,
                                            Map<String, Object> packSection, Map<String, byte[]> pack) {
        if (packSection == null || customModelData != null && itemModel == null) {
            return itemModel;
        }
        String model = string(packSection, "model", null);
        String texture = string(packSection, "texture", null);
        String target = itemModel != null ? itemModel : "nexo:" + id;
        String definitionPath = ResourceId.parse(target).asset("items", ".json");
        if (pack.containsKey(definitionPath)) {
            return target;
        }
        if (model == null && texture != null) {
            ResourceId textureId = ResourceId.parse(texture);
            model = "nexo:item/" + id;
            JsonObject generated = new JsonObject();
            generated.addProperty("parent", "minecraft:item/generated");
            JsonObject textures = new JsonObject();
            textures.addProperty("layer0", textureId.toString());
            generated.add("textures", textures);
            pack.put(ResourceId.parse(model).asset("models", ".json"), Json.bytes(generated));
        }
        if (model == null) {
            return itemModel;
        }
        JsonObject reference = new JsonObject();
        reference.addProperty("type", "minecraft:model");
        reference.addProperty("model", ResourceId.parse(model).toString());
        JsonObject definition = new JsonObject();
        definition.add("model", reference);
        pack.put(definitionPath, Json.bytes(definition));
        return target;
    }

    private IaBlock block(String id, Map<String, Object> config, Map<String, Object> block, Map<String, byte[]> pack,
                          Map<String, JsonObject> blockstates) {
        String type = string(block, "type", "NOTEBLOCK");
        Integer variation = integer(block, "custom_variation");
        if (variation == null) {
            warnings.add("Block " + id + " has no custom_variation (Nexo assigns one at runtime); convert on the server "
                    + "or set it in the config");
            return null;
        }
        String state = NexoBlockStates.state(type, variation);
        if (state == null) {
            warnings.add("Block " + id + ": unsupported type " + type + " or variation " + variation);
            return null;
        }
        if (blockstates != null) {
            String model = blockModel(id, config, block, pack);
            int bracket = state.indexOf('[');
            ResourceId javaBlock = ResourceId.parse(state.substring(0, bracket));
            String path = javaBlock.asset("blockstates", ".json");
            JsonObject file = blockstates.computeIfAbsent(path, p -> {
                JsonObject existing = pack.containsKey(p) ? Json.parse(pack.get(p)).getAsJsonObject() : new JsonObject();
                if (!existing.has("variants")) {
                    existing.add("variants", new JsonObject());
                }
                return existing;
            });
            JsonObject variant = new JsonObject();
            variant.addProperty("model", model);
            file.getAsJsonObject("variants").add(state.substring(bracket + 1, state.length() - 1), variant);
        }
        Map<String, Object> light = section(block, "light");
        int lightLevel = light != null ? integerOr(light, "level", 0) : integerOr(block, "light", 0);
        return new IaBlock("nexo:" + id, state, plain(string(config, "itemname", string(config, "displayname", id))),
                (float) number(block, "hardness", 1), lightLevel);
    }

    /** Nexo's {@code BlockVariants.model}: custom_block.model, then the first variant's model, then Pack.model, then the id. */
    private String blockModel(String id, Map<String, Object> config, Map<String, Object> block, Map<String, byte[]> pack) {
        String model = string(block, "model", null);
        if (model == null && block.get("variants") instanceof List<?> variants) {
            for (Object variant : variants) {
                if (variant instanceof Map<?, ?> map && map.get("model") != null) {
                    model = String.valueOf(map.get("model"));
                    break;
                }
            }
        }
        String packModel = string(section(config, "Pack"), "model", null);
        if (model == null) {
            model = packModel != null ? packModel : id;
        }
        ResourceId resolved = ResourceId.parse(model);
        if (!pack.containsKey(resolved.asset("models", ".json")) && packModel != null && !packModel.equals(model)
                && pack.containsKey(ResourceId.parse(packModel).asset("models", ".json"))) {
            warnings.add("Block " + id + ": custom_block.model '" + model + "' means model " + resolved
                    + ", which the pack does not contain (Java clients see a missing model). Using Pack.model "
                    + packModel + " for Bedrock; fix the Nexo config");
            return packModel;
        }
        return resolved.toString();
    }

    /** Adds the bitmap providers Nexo generates for glyphs to their font files. */
    private void addGlyphFonts(Map<String, byte[]> pack) {
        Map<String, JsonObject> fonts = new TreeMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : glyphs.entrySet()) {
            Map<String, Object> glyph = entry.getValue();
            String texture = string(glyph, "texture", null);
            Object chars = glyph.get("char");
            if (texture == null || chars == null) {
                if (texture != null) {
                    warnings.add("Glyph " + entry.getKey() + " has no fixed 'char'; Nexo assigns one at runtime, so it is skipped");
                }
                continue;
            }
            if (!texture.endsWith(".png")) {
                texture = texture + ".png";
            }
            String font = string(glyph, "font", "minecraft:default");
            String path = ResourceId.parse(font).asset("font", ".json");
            JsonObject file = fonts.computeIfAbsent(path, p -> {
                JsonObject existing = pack.containsKey(p) ? Json.parse(pack.get(p)).getAsJsonObject() : new JsonObject();
                if (!existing.has("providers")) {
                    existing.add("providers", new JsonArray());
                }
                return existing;
            });
            JsonObject provider = new JsonObject();
            provider.addProperty("type", "bitmap");
            provider.addProperty("file", texture);
            provider.addProperty("ascent", integerOr(glyph, "ascent", 8));
            provider.addProperty("height", integerOr(glyph, "height", 8));
            JsonArray charRows = new JsonArray();
            if (chars instanceof List<?> rows) {
                rows.forEach(row -> charRows.add(String.valueOf(row)));
            } else {
                charRows.add(String.valueOf(chars));
            }
            provider.add("chars", charRows);
            if (!containsProvider(file.getAsJsonArray("providers"), provider)) {
                file.getAsJsonArray("providers").add(provider);
            }
        }
        fonts.forEach((path, json) -> pack.put(path, Json.bytes(json)));
    }

    private static boolean containsProvider(JsonArray providers, JsonObject provider) {
        for (JsonElement element : providers) {
            if (element.isJsonObject() && element.getAsJsonObject().get("chars") != null
                    && element.getAsJsonObject().get("chars").equals(provider.get("chars"))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ pack

    /** {@code pack/external_packs} first (zips and folders, sorted by name), then {@code pack/assets} on top. */
    private Map<String, byte[]> assemblePack() throws IOException {
        Map<String, byte[]> pack = new HashMap<>();
        Path external = folder.resolve("pack").resolve("external_packs");
        if (Files.isDirectory(external)) {
            List<Path> entries;
            try (Stream<Path> list = Files.list(external)) {
                entries = list.sorted().toList();
            }
            for (Path entry : entries) {
                if (Files.isDirectory(entry)) {
                    pack.putAll(readDirectory(entry));
                } else if (entry.getFileName().toString().endsWith(".zip")) {
                    pack.putAll(JavaPack.readZip(entry));
                }
            }
        }
        Path assets = folder.resolve("pack").resolve("assets");
        if (Files.isDirectory(assets)) {
            readDirectory(folder.resolve("pack")).forEach((path, bytes) -> {
                if (path.startsWith("assets/")) {
                    pack.put(path, bytes);
                }
            });
        }
        return pack;
    }

    static Map<String, byte[]> readDirectory(Path root) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        return files;
    }

    // ------------------------------------------------------------------ yaml helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static Map<String, Object> section(Map<String, Object> map, String key) {
        return map != null && map.get(key) instanceof Map<?, ?> section ? cast(section) : null;
    }

    private static String string(Map<String, Object> map, String key, String fallback) {
        Object value = map == null ? null : map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static Integer integer(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static int integerOr(Map<String, Object> map, String key, int fallback) {
        Integer value = integer(map, key);
        return value == null ? fallback : value;
    }

    private static double number(Map<String, Object> map, String key, double fallback) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return fallback;
    }

    /** Strips MiniMessage tags; Geyser shows the item's own name anyway, this is the creative/fallback name. */
    static String plain(String text) {
        if (text == null) {
            return null;
        }
        String plain = text.replaceAll("<[^<>]*>", "").strip();
        return plain.isEmpty() ? null : plain;
    }
}
