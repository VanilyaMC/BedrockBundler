package dev.bedrockadder.common.spec;

/**
 * One custom item for Geyser, in a form the extension turns into a {@code CustomItemDefinition}.
 *
 * @param javaItem        vanilla base item
 * @param model           item model the definition matches; for legacy items this is the base item itself
 * @param customModelData set for legacy (CustomModelData) items, matched with a predicate
 * @param bedrockId       Bedrock identifier, e.g. {@code bedrockadder:iasurvival_ruby}
 * @param icon            key in {@code item_texture.json}
 * @param handheld        renders like a tool in hand
 * @param creativeCategory {@code items}, {@code equipment}, {@code construction} or {@code nature}
 * @param protection      armor points shown on Bedrock
 * @param tags            Bedrock item tags, e.g. {@code minecraft:horse_armor} so horses accept the item
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
        java.util.List<String> tags
) {

    public record Food(int nutrition, float saturation, boolean canAlwaysEat) {
    }
}
