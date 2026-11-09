package dev.bedrockadder.paper.itemsadder;

import dev.bedrockadder.common.input.IaBlock;
import dev.bedrockadder.common.input.IaItem;
import dev.bedrockadder.common.java.JavaPack;
import dev.bedrockadder.paper.BedrockAdderPlugin;
import dev.bedrockadder.paper.ContentSource;
import dev.lone.itemsadder.api.CustomStack;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** ItemsAdder 4.x. Loaded by name from {@link BedrockAdderPlugin} only when ItemsAdder is installed. */
public final class ItemsAdderSource implements ContentSource {

    private BedrockAdderPlugin plugin;
    private IaRegistryReader reader;

    @Override
    public String pluginName() {
        return "ItemsAdder";
    }

    @Override
    public void enable(BedrockAdderPlugin plugin) {
        this.plugin = plugin;
        this.reader = new IaRegistryReader(plugin.getLogger());
        plugin.getServer().getPluginManager().registerEvents(new ItemsAdderListener(plugin), plugin);
    }

    @Override
    public List<IaItem> items() {
        return reader.items();
    }

    @Override
    public List<IaBlock> blocks(List<String> skipped) {
        return reader.blocks(skipped);
    }

    @Override
    public Map<String, byte[]> packFromDisk() throws IOException {
        Plugin itemsAdder = plugin.getServer().getPluginManager().getPlugin("ItemsAdder");
        Path zip = itemsAdder == null ? null : itemsAdder.getDataFolder().toPath().resolve("output").resolve("generated.zip");
        if (zip == null || !Files.isRegularFile(zip)) {
            return null;
        }
        return JavaPack.readZip(zip);
    }

    @Override
    public boolean isFurniture(ItemDisplay display) {
        return isCustomItem(display.getItemStack());
    }

    @Override
    public boolean isCustomItem(ItemStack item) {
        return item != null && !item.getType().isAir() && CustomStack.byItemStack(item) != null;
    }
}
