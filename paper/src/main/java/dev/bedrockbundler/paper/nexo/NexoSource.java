package dev.bedrockbundler.paper.nexo;

import com.nexomc.nexo.NexoPlugin;
import com.nexomc.nexo.fonts.FontManager;
import com.nexomc.nexo.glyphs.Glyph;
import com.nexomc.nexo.mechanics.furniture.FurnitureMechanic;
import com.nexomc.nexo.mechanics.furniture.FurnitureProperties;
import com.nexomc.nexo.mechanics.furniture.door.FurnitureDoor;
import dev.bedrockbundler.common.convert.GlyphTitleOffsets;
import com.nexomc.nexo.api.NexoBlocks;
import com.nexomc.nexo.api.NexoFurniture;
import com.nexomc.nexo.api.NexoItems;
import com.nexomc.nexo.items.ItemBuilder;
import com.nexomc.nexo.mechanics.custom_block.CustomBlockMechanic;
import dev.bedrockbundler.common.convert.ConversionContext;
import dev.bedrockbundler.common.input.IaBlock;
import dev.bedrockbundler.common.input.IaItem;
import dev.bedrockbundler.common.java.JavaPack;
import dev.bedrockbundler.paper.BedrockBundlerPlugin;
import dev.bedrockbundler.paper.ContentSource;
import dev.bedrockbundler.paper.ItemSnapshots;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * Nexo (1.2x-1.30). Items, blocks and furniture come from Nexo's API, the pack from the pack Nexo builds, so
 * nothing about Nexo's config format or block state encoding is guessed.
 */
public final class NexoSource implements ContentSource {

    /** Vanilla blocks whose states Nexo uses for custom blocks (note block, string/tripwire, chorus). */
    private static final Set<Material> STATE_BLOCKS = EnumSet.of(Material.NOTE_BLOCK, Material.TRIPWIRE, Material.CHORUS_PLANT);

    private BedrockBundlerPlugin plugin;

    @Override
    public String pluginName() {
        return "Nexo";
    }

    @Override
    public void enable(BedrockBundlerPlugin plugin) {
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
                        NexoBlocks.isCustomBlock(id), NexoFurniture.isFurniture(id)).withEntityDisplay(thrownDisplay(id)));
            } catch (RuntimeException | LinkageError e) {
                plugin.getLogger().log(Level.WARNING, "Could not read Nexo item " + id, e);
            }
        }
        return items;
    }

    /**
     * Java display context Nexo draws a thrown custom trident with ({@code TridentMechanic.getTransform()}), or
     * null when the item has no trident mechanic. Read reflectively: the mechanic is not in every Nexo release.
     */
    private String thrownDisplay(String id) {
        try {
            Class<?> managerClass = Class.forName("com.nexomc.nexo.mechanics.MechanicsManager");
            Object manager = managerClass.getField("INSTANCE").get(null);
            Object factory = managerClass.getMethod("getMechanicFactory", String.class).invoke(manager, "trident");
            if (factory == null) {
                return null;
            }
            Object mechanic = factory.getClass().getMethod("getMechanic", String.class).invoke(factory, id);
            if (mechanic == null) {
                return null;
            }
            Object transform = mechanic.getClass().getMethod("getTransform").invoke(mechanic);
            return transform == null ? null : transform.toString().toLowerCase(Locale.ROOT);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.FINE, "Could not read the trident mechanic of " + id, e);
            return null;
        }
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

    /** Modification time and size of the generated pack, or null when there is none; used to see Nexo write it. */
    String packStamp() {
        try {
            Path zip = outputPath();
            if (zip == null || !Files.isRegularFile(zip)) {
                return null;
            }
            return Files.getLastModifiedTime(zip).toMillis() + ":" + Files.size(zip);
        } catch (IOException | RuntimeException e) {
            return null;
        }
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
    public boolean isCustomItem(ItemStack item) {
        try {
            return item != null && !item.getType().isAir() && NexoItems.idFromItem(item) != null;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Nexo sends the furniture item with packets; the display entity itself usually holds nothing. */
    @Override
    public ItemStack displayItem(ItemDisplay display) {
        try {
            FurnitureMechanic mechanic = NexoFurniture.furnitureMechanic(display);
            ItemStack item = mechanic == null ? null : connectableItem(display, mechanic);
            if (item == null && mechanic != null) {
                item = NexoFurniture.displayItem(display, mechanic);
            }
            if (item == null || item.getType().isAir()) {
                item = NexoFurniture.furnitureItem(display);
            }
            if (item != null && !item.getType().isAir()) {
                return item;
            }
        } catch (RuntimeException | LinkageError e) {
            if (displayItemErrors++ < 3) {
                plugin.getLogger().log(Level.WARNING, "Could not read the item of Nexo furniture " + display.getUniqueId(), e);
            }
        }
        return display.getItemStack();
    }

    private int displayItemErrors;
    private final Set<String> loggedConnectables = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Connectable furniture of type ITEM_MODEL shows a different item_model per neighbour state, which Nexo keeps
     * in the display's data ({@code nexo:connectable}: STRAIGHT, LEFT_END, RIGHT_END, INNER_CORNER, OUTER_CORNER,
     * the latter two also _ROTATED) and sends with packets only. Built here from that state and the mechanic's
     * keys, the same way Nexo builds it: a plain paper with the variant's item_model.
     */
    private ItemStack connectableItem(ItemDisplay display, FurnitureMechanic mechanic) {
        var connectable = mechanic.getConnectable();
        if (connectable == null) {
            return null;
        }
        String state = display.getPersistentDataContainer().get(CONNECTABLE_STATE, PersistentDataType.STRING);
        String upper = state == null ? "DEFAULT" : state.toUpperCase(java.util.Locale.ROOT);
        net.kyori.adventure.key.Key key;
        String suffix;
        if (upper.startsWith("STRAIGHT")) {
            key = connectable.getStraight();
            suffix = "straight";
        } else if (upper.startsWith("LEFT")) {
            key = connectable.getLeft();
            suffix = "left";
        } else if (upper.startsWith("RIGHT")) {
            key = connectable.getRight();
            suffix = "right";
        } else if (upper.startsWith("INNER")) {
            key = connectable.getInner();
            suffix = "inner";
        } else if (upper.startsWith("OUTER")) {
            key = connectable.getOuter();
            suffix = "outer";
        } else {
            key = null;
            suffix = null;
        }
        net.kyori.adventure.key.Key base = connectable.getDefault();
        if (suffix != null && base != null && (key == null || key.equals(base))) {
            // Nexo 1.30 returns the default key for every state unless the config names the variants; the
            // pack holds them as <item_model>_<variant> (see Converter.modelVariants).
            key = net.kyori.adventure.key.Key.key(base.namespace(), base.value() + "_" + suffix);
        }
        ItemStack item;
        if (key != null && "ITEM_MODEL".equals(String.valueOf(connectable.getType()))) {
            item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            meta.setItemModel(new org.bukkit.NamespacedKey(key.namespace(), key.value()));
            item.setItemMeta(meta);
        } else {
            var built = connectable.displayedItem(display);
            item = built == null ? null : built.build();
        }
        if (loggedConnectables.size() < 12 && loggedConnectables.add(mechanic.getItemID() + "/" + upper)) {
            ItemMeta meta = item == null ? null : item.getItemMeta();
            plugin.getLogger().fine("Connectable " + mechanic.getItemID() + " state " + upper + " ("
                    + connectable.getType() + ") shows " + (meta != null && meta.hasItemModel() ? meta.getItemModel() : item));
        }
        return item;
    }

    /** Where Nexo keeps a connectable's neighbour state ({@code ConnectableMechanic.CONNECTABLE_KEY}). */
    private static final org.bukkit.NamespacedKey CONNECTABLE_STATE = new org.bukkit.NamespacedKey("nexo", "connectable");

    /**
     * Nexo doors keep their state in the display's data ({@code FurnitureDoor.isOpen}) and send the open or closed
     * properties (a 90 degree turn by default) with packets only.
     */
    @Override
    public Transformation transformation(ItemDisplay display) {
        Transformation base = display.getTransformation();
        try {
            FurnitureMechanic mechanic = NexoFurniture.furnitureMechanic(display);
            FurnitureDoor door = mechanic == null ? null : mechanic.getDoor();
            if (door == null) {
                return base;
            }
            FurnitureProperties properties = FurnitureDoor.Companion.isOpen(display)
                    ? door.getOpenProperties() : mechanic.getProperties();
            if (properties == null) {
                return base;
            }
            return new Transformation(
                    properties.getTranslation() != null ? new Vector3f(properties.getTranslation()) : base.getTranslation(),
                    properties.getLeftRotation() != null ? new Quaternionf(properties.getLeftRotation()) : base.getLeftRotation(),
                    properties.getScale() != null ? new Vector3f(properties.getScale()) : base.getScale(),
                    properties.getRightRotation() != null ? new Quaternionf(properties.getRightRotation()) : base.getRightRotation());
        } catch (RuntimeException | LinkageError e) {
            return base;
        }
    }

    @Override
    public ConversionContext.Options adjust(ConversionContext.Options options) {
        // Nexo furniture uses display_transform NONE by default: the model is drawn without a display transform.
        ConversionContext.Options adjusted = options.withFurnitureDisplay("none");
        // Nexo places a GUI glyph with explicit shifts in the title (<shift:-37><glyph:menu_items>); without one
        // it starts at the title, unlike ItemsAdder's default of -16.
        int defaultOffset = options.guiOffsetX() == ConversionContext.Options.DEFAULT.guiOffsetX() ? 0 : options.guiOffsetX();
        return adjusted.withGuiOffsets(defaultOffset, glyphOffsets());
    }

    /** Shifts in front of glyphs in the menu titles of all plugin configs (Nexo's own menus and menu plugins). */
    private Map<Integer, Integer> glyphOffsets() {
        try {
            FontManager fonts = NexoPlugin.instance().fontManager();
            return GlyphTitleOffsets.find(configTexts(plugin.getDataFolder().toPath().getParent()), id -> {
                Glyph glyph = fonts.glyphFromID(id);
                if (glyph == null) {
                    glyph = fonts.glyphFromName(id);
                }
                char[] chars = glyph == null ? null : glyph.getChars();
                return chars == null || chars.length == 0 ? null : new String(chars);
            });
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.FINE, "Could not read Nexo glyph titles", e);
            return Map.of();
        }
    }

    /** YAML files of the plugins folder (small ones; data files and worlds are not menus). */
    static List<String> configTexts(Path pluginsFolder) {
        List<String> texts = new ArrayList<>();
        if (pluginsFolder == null || !Files.isDirectory(pluginsFolder)) {
            return texts;
        }
        try (Stream<Path> files = Files.walk(pluginsFolder, 6)) {
            files.filter(p -> {
                String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                return name.endsWith(".yml") || name.endsWith(".yaml");
            }).forEach(p -> {
                try {
                    if (Files.size(p) <= 512 * 1024) {
                        String text = Files.readString(p, StandardCharsets.UTF_8);
                        if (text.contains("<shift:")) {
                            texts.add(text);
                        }
                    }
                } catch (IOException | RuntimeException ignored) {
                    // unreadable or not UTF-8: not a menu we can read
                }
            });
        } catch (IOException | RuntimeException ignored) {
            // partial result is fine
        }
        return texts;
    }
}
