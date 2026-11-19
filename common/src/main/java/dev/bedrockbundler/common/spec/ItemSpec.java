package dev.bedrockbundler.common.spec;

/**
 * One custom item for Geyser, in a form the extension turns into a {@code CustomItemDefinition}.
 *
 * @param javaItem        vanilla base item
 * @param model           item model the definition matches; for legacy items this is the base item itself
 * @param customModelData set for legacy (CustomModelData) items, matched with a predicate
 * @param bedrockId       Bedrock identifier, e.g. {@code bedrockbundler:iasurvival_ruby}
 * @param icon            key in {@code item_texture.json}
 * @param handheld        renders like a tool in hand
 * @param creativeCategory {@code items}, {@code equipment}, {@code construction} or {@code nature}
 * @param protection      armor points shown on Bedrock
 * @param tags            Bedrock item tags, e.g. {@code minecraft:horse_armor} so horses accept the item
 * @param dyedIcon        key in {@code item_texture.json} of the grayscale icon Bedrock tints with the item's dye
 *                        colour; set (not null) marks the item as dyeable. Absent in older bundles, then null.
 */
public record ItemSpec(
        String javaItem,
        String model,
        Integer customModelData,
        String bedrockId,
        String displayName,
        String icon,
        boolean handheld,
        String creativeCategory,
        Integer maxStackSize,
        Integer maxDamage,
        String equipSlot,
        int protection,
        Food food,
        java.util.List<String> tags,
        String dyedIcon
) {

    /** Constructor of the format before dyeable items: not dyeable. */
    public ItemSpec(String javaItem, String model, Integer customModelData, String bedrockId, String displayName,
                    String icon, boolean handheld, String creativeCategory, Integer maxStackSize, Integer maxDamage,
                    String equipSlot, int protection, Food food, java.util.List<String> tags) {
        this(javaItem, model, customModelData, bedrockId, displayName, icon, handheld, creativeCategory, maxStackSize,
                maxDamage, equipSlot, protection, food, tags, null);
    }

    public record Food(int nutrition, float saturation, boolean canAlwaysEat) {
    }
}
