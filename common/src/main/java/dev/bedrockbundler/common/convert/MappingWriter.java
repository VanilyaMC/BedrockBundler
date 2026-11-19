package dev.bedrockbundler.common.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bedrockbundler.common.Json;
import dev.bedrockbundler.common.spec.ItemSpec;

import java.util.List;

/**
 * Writes items as Geyser v2 JSON mappings. The extension registers items through the API instead; this file
 * is for servers that prefer plain {@code custom_mappings} and for debugging.
 */
public final class MappingWriter {

    private MappingWriter() {
    }

    public static JsonObject write(List<ItemSpec> items) {
        JsonObject byItem = new JsonObject();
        for (ItemSpec item : items) {
            JsonObject definition = new JsonObject();
            if (item.customModelData() != null) {
                definition.addProperty("type", "legacy");
                definition.addProperty("custom_model_data", item.customModelData());
            } else {
                definition.addProperty("type", "definition");
                definition.addProperty("model", item.model());
            }
            definition.addProperty("bedrock_identifier", item.bedrockId());
            if (item.displayName() != null) {
                definition.addProperty("display_name", item.displayName());
            }

            JsonObject options = new JsonObject();
            if (item.dyedIcon() != null) {
                // Geyser 2.12 mappings: a dyeable item with a grayscale icon the client tints with the item's colour.
                JsonObject icons = new JsonObject();
                icons.addProperty("default", item.icon());
                icons.addProperty("dyed", item.dyedIcon());
                options.add("icons", icons);
                JsonObject dyeable = new JsonObject();
                dyeable.addProperty("default_color", "#" + String.format(java.util.Locale.ROOT, "%06X",
                        (item.dyeColor() == null ? 0xFFFFFF : item.dyeColor()) & 0xFFFFFF));
                options.add("dyeable", dyeable);
            } else {
                options.addProperty("icon", item.icon());
            }
            options.addProperty("creative_category", item.creativeCategory());
            if (item.handheld()) {
                options.addProperty("display_handheld", true);
            }
            if (item.protection() > 0) {
                options.addProperty("protection_value", item.protection());
            }
            definition.add("bedrock_options", options);

            JsonObject components = new JsonObject();
            if (item.maxStackSize() != null) {
                components.addProperty("minecraft:max_stack_size", item.maxStackSize());
            }
            if (item.maxDamage() != null) {
                components.addProperty("minecraft:max_damage", item.maxDamage());
            }
            if (item.equipSlot() != null) {
                JsonObject equippable = new JsonObject();
                equippable.addProperty("slot", item.equipSlot());
                components.add("minecraft:equippable", equippable);
            }
            if (item.food() != null) {
                JsonObject food = new JsonObject();
                food.addProperty("nutrition", item.food().nutrition());
                food.addProperty("saturation", item.food().saturation());
                food.addProperty("can_always_eat", item.food().canAlwaysEat());
                components.add("minecraft:food", food);
                components.add("minecraft:consumable", new JsonObject());
            }
            if (!components.isEmpty()) {
                definition.add("components", components);
            }

            JsonArray definitions = byItem.has(item.javaItem()) ? byItem.getAsJsonArray(item.javaItem()) : new JsonArray();
            definitions.add(definition);
            byItem.add(item.javaItem(), definitions);
        }
        JsonObject root = new JsonObject();
        root.addProperty("format_version", 2);
        root.add("items", byItem);
        return root;
    }

    public static byte[] bytes(List<ItemSpec> items) {
        return Json.bytes(write(items));
    }
}
