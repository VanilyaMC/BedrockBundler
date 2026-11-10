package dev.bedrockbundler.paper.itemsadder;

import dev.bedrockbundler.common.input.IaBlock;
import dev.bedrockbundler.common.input.IaItem;
import dev.bedrockbundler.common.java.JavaPack;
import dev.bedrockbundler.paper.BedrockBundlerPlugin;
import dev.bedrockbundler.paper.ContentSource;
import dev.lone.itemsadder.api.CustomStack;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** ItemsAdder 4.x. Loaded by name from {@link BedrockBundlerPlugin} only when ItemsAdder is installed. */
public final class ItemsAdderSource implements ContentSource {

    private BedrockBundlerPlugin plugin;
    private IaRegistryReader reader;

    @Override
    public String pluginName() {
        return "ItemsAdder";
    }

    @Override
    public void enable(BedrockBundlerPlugin plugin) {
        this.plugin = plugin;
        this.reader = new IaRegistryReader(plugin.getLogger());
        plugin.getServer().getPluginManager().registerEvents(new ItemsAdderListener(plugin), plugin);
        if (plugin.getServer().getPluginManager().getPlugin("Geyser-Spigot") != null) {
            // Emotes are played through Geyser's session, so only with Geyser on this server.
            plugin.getServer().getPluginManager().registerEvents(
                    new ItemsAdderEmotes(plugin.emotePlayer()), plugin);
        }
    }

    @Override
    public List<dev.bedrockbundler.common.input.EmoteAnimation> emotes() {
        Plugin itemsAdder = plugin.getServer().getPluginManager().getPlugin("ItemsAdder");
        if (itemsAdder == null) {
            return List.of();
        }
        try {
            // Content packs first: the converter keeps the first emote of a name.
            List<dev.bedrockbundler.common.input.EmoteAnimation> emotes = new java.util.ArrayList<>(
                    ItemsAdderEmotes.read(itemsAdder.getDataFolder().toPath().resolve("contents")));
            // ItemsAdder's own emotes (wave, death, magic_trick_head...) live in its jar.
            try (java.io.InputStream in = itemsAdder.getResource(ItemsAdderEmotes.BUILT_IN)) {
                if (in != null) {
                    emotes.add(new dev.bedrockbundler.common.input.EmoteAnimation("ItemsAdder.jar/" + ItemsAdderEmotes.BUILT_IN,
                            new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
                }
            }
            return emotes;
        } catch (IOException e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not read ItemsAdder emotes", e);
            return List.of();
        }
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
