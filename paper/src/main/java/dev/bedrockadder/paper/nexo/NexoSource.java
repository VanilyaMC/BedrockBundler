package dev.bedrockadder.paper.nexo;

import com.nexomc.nexo.api.NexoBlocks;
import com.nexomc.nexo.api.NexoFurniture;
import com.nexomc.nexo.api.NexoItems;
import com.nexomc.nexo.items.ItemBuilder;
import com.nexomc.nexo.mechanics.custom_block.CustomBlockMechanic;
import dev.bedrockadder.common.convert.ConversionContext;
import dev.bedrockadder.common.input.IaBlock;
import dev.bedrockadder.common.input.IaItem;
import dev.bedrockadder.common.java.JavaPack;
import dev.bedrockadder.paper.BedrockAdderPlugin;
import dev.bedrockadder.paper.ContentSource;
import dev.bedrockadder.paper.ItemSnapshots;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Nexo (1.2x-1.30). Items, blocks and furniture come from Nexo's API, the pack from the pack Nexo builds, so
 * nothing about Nexo's config format or block state encoding is guessed.
 */
public final class NexoSource implements ContentSource {

    /** Vanilla blocks whose states Nexo uses for custom blocks (note block, string/tripwire, chorus). */
    private static final Set<Material> STATE_BLOCKS = EnumSet.of(Material.NOTE_BLOCK, Material.TRIPWIRE, Material.CHORUS_PLANT);

    private BedrockAdderPlugin plugin;

    @Override
    public String pluginName() {
        return "Nexo";
    }

    @Override
    public void enable(BedrockAdderPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(new NexoListener(plugin, this), plugin);
    }

    @Override
    public List<IaItem> items() {
        List<IaItem> items = new ArrayList<>();
        for (String id : NexoItems.itemNames()) {
            try {
                ItemBuilder builder = NexoItems.itemFromId(id);
                if (builder == null) {
                    continue;
                }
                ItemStack stack = builder.build();
                items.add(ItemSnapshots.item("nexo:" + id, stack, displayName(builder, stack),
                        NexoBlocks.isCustomBlock(id), NexoFurniture.isFurniture(id)));
            } catch (RuntimeException | LinkageError e) {
                plugin.getLogger().log(Level.WARNING, "Could not read Nexo item " + id, e);
            }
        }
        return items;
    }

    private static String displayName(ItemBuilder builder, ItemStack stack) {
        Component name = builder.getItemName();
        if (name == null) {
            ItemMeta meta = stack.getItemMeta();
            name = meta != null && meta.hasDisplayName() ? meta.displayName() : null;
        }
        if (name == null) {
            return null;
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(name).strip();
        return plain.isEmpty() ? null : plain;
    }

    @Override
    public List<IaBlock> blocks(List<String> skipped) {
        List<IaBlock> blocks = new ArrayList<>();
        for (String id : NexoBlocks.blockIDs()) {
            try {
                BlockData data = NexoBlocks.blockData(id);
                if (data == null || !STATE_BLOCKS.contains(data.getMaterial())) {
                    skipped.add(id);
                    continue;
                }
                CustomBlockMechanic mechanic = NexoBlocks.customBlockMechanic(id);
                float hardness = mechanic == null || mechanic.getBreakable() == null ? 1f
                        : (float) mechanic.getBreakable().getHardness();
                int light = lightLevel(mechanic);
                ItemBuilder builder = NexoItems.itemFromId(id);
                String name = builder == null ? id : displayName(builder, builder.build());
                blocks.add(new IaBlock("nexo:" + id, data.getAsString(), name, hardness, light));
            } catch (RuntimeException | LinkageError e) {
                plugin.getLogger().log(Level.WARNING, "Could not read Nexo block " + id, e);
            }
        }
        return blocks;
    }

    @Override
    public Map<String, byte[]> packFromDisk() throws IOException {
        Path zip = outputPath();
        if (zip == null || !Files.isRegularFile(zip)) {
            return null;
        }
        return JavaPack.readZip(zip);
    }

    /**
     * Where Nexo writes its generated pack: {@code Pack.generation.output_path} in Nexo's settings.yml (a path or a
     * list of paths, relative to the server folder), default {@code plugins/Nexo/pack/pack.zip}.
     */
    private Path outputPath() {
        Plugin nexo = plugin.getServer().getPluginManager().getPlugin("Nexo");
        if (nexo == null) {
            return null;
        }
        String configured = null;
        java.io.File settings = new java.io.File(nexo.getDataFolder(), "settings.yml");
        if (settings.isFile()) {
            org.bukkit.configuration.file.YamlConfiguration yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(settings);
            Object value = yaml.get("Pack.generation.output_path");
            if (value instanceof List<?> list && !list.isEmpty()) {
                value = list.get(0);
            }
            if (value instanceof String text && text.endsWith(".zip")) {
                configured = text;
            }
        }
        Path serverFolder = plugin.getServer().getWorldContainer().toPath();
        return configured != null ? serverFolder.resolve(configured).normalize()
                : nexo.getDataFolder().toPath().resolve("pack").resolve("pack.zip");
    }

    /**
     * The light mechanic changed shape between Nexo releases ({@code LightMechanic} in 1.29,
     * {@code CustomBlockLight} in 1.30), so its level is read reflectively.
     */
    private static int lightLevel(CustomBlockMechanic mechanic) {
        if (mechanic == null) {
            return 0;
        }
        try {
            Object light = mechanic.getClass().getMethod("getLight").invoke(mechanic);
            if (light == null) {
                return 0;
            }
            for (String getter : new String[]{"getLevel", "getLightLevel"}) {
                try {
                    return ((Number) light.getClass().getMethod(getter).invoke(light)).intValue();
                } catch (NoSuchMethodException ignored) {
                    // try the next name
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // no light
        }
        return 0;
    }

    @Override
    public boolean isFurniture(ItemDisplay display) {
        try {
            return NexoFurniture.isFurniture(display);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Override
    public ConversionContext.Options adjust(ConversionContext.Options options) {
        // Nexo furniture uses display_transform NONE by default: the model is drawn without a display transform.
        return options.withFurnitureDisplay("none");
    }
}
