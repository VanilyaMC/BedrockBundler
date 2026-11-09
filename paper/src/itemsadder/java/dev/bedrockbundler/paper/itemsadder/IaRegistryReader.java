package dev.bedrockbundler.paper.itemsadder;

import dev.bedrockbundler.common.input.IaBlock;
import dev.bedrockbundler.common.input.IaItem;
import dev.bedrockbundler.paper.ItemSnapshots;
import dev.lone.itemsadder.api.CustomBlock;
import dev.lone.itemsadder.api.CustomStack;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Snapshots the ItemsAdder registry into plain records. Must run on the server thread. */
final class IaRegistryReader {

    /** Vanilla blocks ItemsAdder uses for "real" custom blocks; Geyser can swap their states. */
    private static final Set<Material> STATE_BLOCKS = EnumSet.of(
            Material.NOTE_BLOCK, Material.BROWN_MUSHROOM_BLOCK, Material.RED_MUSHROOM_BLOCK,
            Material.MUSHROOM_STEM, Material.CHORUS_PLANT, Material.TRIPWIRE);

    private final Logger logger;

    IaRegistryReader(Logger logger) {
        this.logger = logger;
    }

    List<IaItem> items() {
        List<IaItem> items = new ArrayList<>();
        // Raw Set: the obfuscated ItemsAdder jar has no generic signatures.
        for (Object rawId : CustomStack.getNamespacedIdsInRegistry()) {
            String id = (String) rawId;
            try {
                CustomStack stack = CustomStack.getInstance(id);
                if (stack != null) {
                    items.add(item(id, stack));
                }
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Could not read ItemsAdder item " + id, e);
            }
        }
        return items;
    }

    private static IaItem item(String id, CustomStack stack) {
        ItemStack item = stack.getItemStack();
        return ItemSnapshots.item(id, item, ChatColor.stripColor(stack.getDisplayName()),
                stack.isBlock(), CustomStack.isFurniture(item));
    }

    /** @param skipped receives ids of blocks that are not state-based (entity/furniture blocks) */
    List<IaBlock> blocks(List<String> skipped) {
        List<IaBlock> blocks = new ArrayList<>();
        for (Object rawId : CustomBlock.getNamespacedIdsInRegistry()) {
            String id = (String) rawId;
            try {
                CustomBlock block = CustomBlock.getInstance(id);
                BlockData data = block == null ? null : block.getBaseBlockData();
                if (data == null || !STATE_BLOCKS.contains(data.getMaterial())) {
                    skipped.add(id);
                    continue;
                }
                blocks.add(new IaBlock(id, data.getAsString(), ChatColor.stripColor(block.getDisplayName()),
                        hardness(block), block.getOriginalLightLevel()));
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Could not read ItemsAdder block " + id, e);
            }
        }
        return blocks;
    }

    private static float hardness(CustomBlock block) {
        ConfigurationSection section = block.getConfigSectionCopy();
        if (section != null) {
            for (String path : List.of("specific_properties.block.hardness", "behaviours.block.hardness", "block.hardness")) {
                if (section.isSet(path)) {
                    return (float) section.getDouble(path);
                }
            }
        }
        return 1.0f;
    }
}
