package dev.bedrockadder.common.input;

/**
 * Snapshot of one ItemsAdder custom item, taken on the server thread so the converter
 * can run anywhere without touching the Bukkit or ItemsAdder APIs.
 *
 * @param id               namespaced ItemsAdder id, e.g. {@code iasurvival:ruby}
 * @param material         vanilla base item, e.g. {@code minecraft:paper}
 * @param itemModel        value of the {@code item_model} component, or null when the item uses CustomModelData
 * @param customModelData  legacy CustomModelData, or null
 * @param displayName      plain display name
 * @param maxStackSize     only set when it differs from the base item
 * @param maxDamage        only set when it differs from the base item
 * @param equipSlot        {@code head}, {@code chest}, {@code legs}, {@code feet} or {@code body} (animal armor) for wearables, else null
 * @param armorAsset       equipment asset id ({@code equippable.asset_id}) for armor, else null
 * @param protection       armor points, for the Bedrock armor bar
 * @param food             food properties, or null
 * @param block            true when the item places an ItemsAdder custom block
 * @param furniture        true when the item places an ItemsAdder furniture
 */
public record IaItem(
        String id,
        String material,
        String itemModel,
        Integer customModelData,
        String displayName,
        Integer maxStackSize,
        Integer maxDamage,
        String equipSlot,
        String armorAsset,
        int protection,
        Food food,
        boolean block,
        boolean furniture
) {

    public record Food(int nutrition, float saturation, boolean canAlwaysEat) {
    }
}
