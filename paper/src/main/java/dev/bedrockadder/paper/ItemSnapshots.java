package dev.bedrockadder.paper;

import dev.bedrockadder.common.input.IaItem;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.inventory.meta.components.FoodComponent;

import java.util.Collection;

/** Reads what the converter needs from a built custom item stack, with plain Bukkit API. */
public final class ItemSnapshots {

    private ItemSnapshots() {
    }

    public static IaItem item(String id, ItemStack item, String displayName, boolean block, boolean furniture) {
        Material type = item.getType();
        ItemMeta meta = item.getItemMeta();
        String material = type.getKey().toString();

        String itemModel = meta.hasItemModel() ? meta.getItemModel().toString() : null;
        if (material.equals(itemModel)) {
            itemModel = null; // the vanilla model is not a custom one
        }
        Integer customModelData = meta.hasCustomModelData() ? meta.getCustomModelData() : null;

        Integer maxStackSize = meta.hasMaxStackSize() && meta.getMaxStackSize() != type.getMaxStackSize()
                ? meta.getMaxStackSize() : null;
        Integer maxDamage = meta instanceof Damageable damageable && damageable.hasMaxDamage()
                && damageable.getMaxDamage() != type.getMaxDurability() ? damageable.getMaxDamage() : null;

        String slot = null;
        String armorAsset = null;
        if (meta.hasEquippable()) {
            EquippableComponent equippable = meta.getEquippable();
            slot = slotName(equippable.getSlot());
            armorAsset = equippable.getModel() == null ? null : equippable.getModel().toString();
        } else if (type.isItem()) {
            slot = slotName(type.getEquipmentSlot());
        }

        IaItem.Food food = null;
        if (meta.hasFood()) {
            FoodComponent component = meta.getFood();
            food = new IaItem.Food(component.getNutrition(), component.getSaturation(), component.canAlwaysEat());
        }

        return new IaItem(id, material, itemModel, customModelData, displayName,
                maxStackSize, maxDamage, slot, armorAsset, armorPoints(meta), food, block, furniture);
    }

    private static String slotName(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "head";
            case CHEST -> "chest";
            case LEGS -> "legs";
            case FEET -> "feet";
            case BODY -> "body";
            default -> null;
        };
    }

    private static int armorPoints(ItemMeta meta) {
        if (!meta.hasAttributeModifiers()) {
            return 0;
        }
        Collection<AttributeModifier> modifiers = meta.getAttributeModifiers(Attribute.ARMOR);
        double total = 0;
        if (modifiers != null) {
            for (AttributeModifier modifier : modifiers) {
                if (modifier.getOperation() == AttributeModifier.Operation.ADD_NUMBER) {
                    total += modifier.getAmount();
                }
            }
        }
        return (int) Math.round(total);
    }
}
