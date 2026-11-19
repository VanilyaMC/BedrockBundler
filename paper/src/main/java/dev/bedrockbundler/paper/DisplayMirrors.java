package dev.bedrockbundler.paper;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Shows Bedrock players what Geyser leaves out. Geyser turns armor stands, name tags, item frames, text displays and
 * interaction entities into Bedrock entities itself, but it has no Bedrock entity for item displays and block
 * displays (they are missing from its entity registry), and an item frame holding a custom 3D item draws only the
 * item's flat icon. For those this keeps an invisible marker armor stand, shown only to Bedrock players, that wears
 * the item (the item's attachable draws it through the "furniture" pose) or the block.
 *
 * <p>What each kind does, and what it deliberately does not:
 * <ul>
 *   <li><b>Item displays of custom items that are not furniture</b> (chests, backpacks, props of other plugins):
 *       mirrored exactly like furniture. Furniture itself stays with {@link FurnitureMirror}.</li>
 *   <li><b>Item frames and glow item frames holding a custom item</b>: a stand at half size on the frame's plane. A
 *       frame on a floor or ceiling is skipped, and so is the item's rotation, because a stand can neither tilt nor
 *       roll. An invisible frame (furniture style) is hidden from Bedrock players while the mirror shows, else its
 *       flat icon would show inside the model; that also makes the frame itself unclickable for them.</li>
 *   <li><b>Armor stands</b>: not mirrored. Geyser draws armor stands with their worn items natively, and an item with
 *       an attachable is drawn on the head like for any player.</li>
 *   <li><b>Block displays</b>: a stand wearing the block, scaled to the display's size. Approximate, off by default:
 *       it was not seen on a Bedrock client, and it can only draw blocks that exist as items.</li>
 *   <li><b>Text displays</b>: Geyser already draws them (a name plate on an armor stand, multi-line centered), so
 *       mirroring is off by default, and when on it hides the original from Bedrock players so text is not drawn
 *       twice. It is an escape hatch for servers where Geyser's version misbehaves.</li>
 *   <li><b>Interaction entities</b>: nothing to do. Geyser gives them an invisible armor stand of the interaction's
 *       size, which Bedrock players can hit and use, and forwards those clicks as Java interactions.</li>
 * </ul>
 *
 * <p>Region-threading safe (Folia, Canvas): one task per source entity on that entity's own scheduler, stands spawn
 * at the entity's position (same region) and move with {@code teleportAsync}, players are only touched by the region
 * that owns them. A pass over an unchanged entity compares a few values and does nothing else, every
 * {@value #SYNC_TICKS} ticks.
 */
final class DisplayMirrors implements Listener {

    /** What a mirror shows; the order is the order of the status line. */
    enum Kind {
        ITEM("item displays"), FRAME("item frames"), BLOCK("block displays"), TEXT("text displays");

        private final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    static final String TAG = "bedrockbundler_display_mirror";
    /** Tags of the furniture mirror stands (current and former name); never source entities, but cheap to exclude. */
    private static final Set<String> OTHER_MIRROR_TAGS = Set.of("bedrockbundler_furniture_mirror", "bedrockadder_furniture_mirror");
    private static final long SYNC_TICKS = 10;
    /** Frames hold their item at half size, like the "fixed" display of Java. */
    private static final float FRAME_SCALE = 0.5f;
    /** A display's view range is in units of 64 blocks. */
    private static final double VIEW_RANGE_BLOCKS = 64;

    /** The stand for one source entity, as computed on a pass. */
    private record Look(DisplayGeometry.Pose pose, ItemStack helmet, String name, float standScale, boolean hideOriginal) {
    }

    /** State of one source entity; touched only from that entity's thread (except the maps it sits in). */
    private static final class Tracked {
        final Kind kind;
        final Entity original;
        final ChangeTracker<ItemStack> helmet = new ChangeTracker<>();
        final ChangeTracker<String> name = new ChangeTracker<>();
        /** Players this mirror hid the original from. */
        final Set<UUID> hiddenFrom = ConcurrentHashMap.newKeySet();
        volatile ArmorStand stand;
        volatile ScheduledTask task;
        Component lastText;
        String lastLegacy = "";

        Tracked(Kind kind, Entity original) {
            this.kind = kind;
            this.original = original;
        }
    }

    private final BedrockBundlerPlugin plugin;
    private final ContentSource source;
    private final DisplaySettings settings;
    private final float yawOffset;
    private final double yOffset;
    private final MirrorSupport support;
    private final Map<UUID, Tracked> tracked = new ConcurrentHashMap<>();
    private final AtomicInteger stands = new AtomicInteger();
    private volatile boolean running;
    private volatile boolean capWarned;

    DisplayMirrors(BedrockBundlerPlugin plugin, BedrockPlayers bedrock, ContentSource source, DisplaySettings settings,
                   float yawOffset, double yOffset) {
        this.plugin = plugin;
        this.source = source;
        this.settings = settings;
        this.yawOffset = yawOffset;
        this.yOffset = yOffset;
        this.support = new MirrorSupport(plugin, bedrock);
    }

    void start() {
        running = true;
        support.start();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (!Scheduling.isFolia()) {
            // Entities already loaded (plugin reload). Folia has no global entity list; there, chunks loading later
            // fire EntityAddToWorldEvent, and mirrors are never saved, so nothing is left to clean up.
            for (World world : plugin.getServer().getWorlds()) {
                for (Entity entity : world.getEntities()) {
                    if (entity.getScoreboardTags().contains(TAG)) {
                        entity.remove();
                    } else {
                        track(entity);
                    }
                }
            }
        }
    }

    void stop() {
        running = false;
        HandlerList.unregisterAll(this);
        for (Tracked t : tracked.values()) {
            ScheduledTask task = t.task;
            if (task != null) {
                task.cancel();
            }
            ArmorStand stand = t.stand;
            if (stand != null) {
                support.removeStand(stand);
            }
            restoreOriginal(t);
        }
        tracked.clear();
        stands.set(0);
        support.stop();
    }

    /** Mirror stands alive per kind (kinds that are switched off show 0). */
    Map<Kind, Integer> counts() {
        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        for (Kind kind : Kind.values()) {
            counts.put(kind, 0);
        }
        for (Tracked t : tracked.values()) {
            if (t.stand != null) {
                counts.merge(t.kind, 1, Integer::sum);
            }
        }
        return counts;
    }

    /** One line for {@code /bedrockbundler status}, listing the kinds that are switched on. */
    String statusLine() {
        Map<Kind, Integer> counts = counts();
        StringBuilder line = new StringBuilder();
        for (Kind kind : Kind.values()) {
            if (isEnabled(kind)) {
                line.append(line.isEmpty() ? "" : ", ").append(counts.get(kind)).append(' ').append(kind.label);
            }
        }
        return line.toString();
    }

    private boolean isEnabled(Kind kind) {
        return switch (kind) {
            case ITEM -> settings.items();
            case FRAME -> settings.frames();
            case BLOCK -> settings.blocks();
            case TEXT -> settings.text();
        };
    }

    private Kind kindOf(Entity entity) {
        Kind kind = null;
        if (entity instanceof ItemDisplay) {
            kind = Kind.ITEM;
        } else if (entity instanceof ItemFrame) { // includes glow item frames
            kind = Kind.FRAME;
        } else if (entity instanceof BlockDisplay) {
            kind = Kind.BLOCK;
        } else if (entity instanceof TextDisplay) {
            kind = Kind.TEXT;
        }
        return kind != null && isEnabled(kind) ? kind : null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdd(EntityAddToWorldEvent event) {
        track(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveFromWorldEvent event) {
        if (kindOf(event.getEntity()) != null) {
            untrack(event.getEntity().getUniqueId());
        }
    }

    /** Starts the per-entity task on the entity's own thread. */
    private void track(Entity entity) {
        Kind kind = kindOf(entity);
        UUID id = entity.getUniqueId();
        if (kind == null || !running || tracked.containsKey(id) || isMirrorStand(entity)) {
            return;
        }
        Tracked t = new Tracked(kind, entity);
        if (tracked.putIfAbsent(id, t) != null) {
            return; // tracked concurrently by another event
        }
        ScheduledTask task = entity.getScheduler().runAtFixedRate(plugin, st -> pass(t), () -> untrack(id), 1, SYNC_TICKS);
        if (task == null) {
            tracked.remove(id); // the entity was retired already
        } else {
            t.task = task;
        }
    }

    private static boolean isMirrorStand(Entity entity) {
        Set<String> tags = entity.getScoreboardTags();
        if (tags.isEmpty()) {
            return false;
        }
        return tags.contains(TAG) || OTHER_MIRROR_TAGS.stream().anyMatch(tags::contains);
    }

    private void untrack(UUID id) {
        Tracked t = tracked.remove(id);
        if (t == null) {
            return;
        }
        ScheduledTask task = t.task;
        if (task != null) {
            task.cancel();
        }
        dropStand(t);
    }

    /** Runs on the source entity's thread. */
    private void pass(Tracked t) {
        try {
            if (!t.original.isValid()) {
                untrack(t.original.getUniqueId());
                return;
            }
            Look look = describe(t);
            if (look == null) {
                dropStand(t); // nothing to show (yet): furniture, blank text, an empty frame, ...
                return;
            }
            ArmorStand stand = t.stand;
            if (stand != null && !stand.isValid()) {
                stands.decrementAndGet();
                t.stand = null;
                stand = null;
            }
            if (stand == null) {
                if (stands.get() >= settings.maxMirrors()) {
                    if (!capWarned) {
                        capWarned = true;
                        plugin.getLogger().warning("displays.max-mirrors (" + settings.maxMirrors()
                                + ") reached; further display mirrors for Bedrock players are skipped.");
                    }
                    return;
                }
                stand = spawn(t, look);
                if (stand == null) {
                    return;
                }
            }
            apply(t, stand, look);
            updateViewers(t, stand, look.hideOriginal());
        } catch (RuntimeException e) {
            // e.g. a translation that reaches into a chunk owned by another region
            plugin.getLogger().log(Level.FINE, "Could not mirror " + t.kind + " " + t.original.getUniqueId(), e);
        }
    }

    private ArmorStand spawn(Tracked t, Look look) {
        DisplayGeometry.Pose pose = look.pose();
        Location at = new Location(t.original.getWorld(), pose.x(), pose.y(), pose.z(), pose.yaw(), 0);
        ArmorStand stand = t.original.getWorld().spawn(at, ArmorStand.class, s -> {
            support.configureStand(s, TAG);
            if (t.kind == Kind.TEXT) {
                s.setCustomNameVisible(true);
            }
        });
        t.stand = stand;
        t.helmet.reset();
        t.name.reset();
        stands.incrementAndGet();
        return stand;
    }

    /** Removes the stand (if any) and gives the original back to the players it was hidden from. */
    private void dropStand(Tracked t) {
        ArmorStand stand = t.stand;
        if (stand != null) {
            t.stand = null;
            stands.decrementAndGet();
            support.removeStand(stand);
        }
        restoreOriginal(t);
    }

    private void restoreOriginal(Tracked t) {
        if (t.hiddenFrom.isEmpty()) {
            return;
        }
        for (Player player : support.players()) {
            try {
                if (t.hiddenFrom.contains(player.getUniqueId()) && plugin.getServer().isOwnedByCurrentRegion(player)) {
                    player.showEntity(plugin, t.original);
                    t.hiddenFrom.remove(player.getUniqueId());
                }
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.FINE, "Could not show a display again to " + player.getName(), e);
            }
        }
    }

    // ---- what to show -------------------------------------------------------------------------------------

    private Look describe(Tracked t) {
        return switch (t.kind) {
            case ITEM -> describeItem((ItemDisplay) t.original);
            case FRAME -> describeFrame((ItemFrame) t.original);
            case BLOCK -> describeBlock((BlockDisplay) t.original);
            case TEXT -> describeText(t, (TextDisplay) t.original);
        };
    }

    private Look describeItem(ItemDisplay display) {
        if (source.isFurniture(display)) {
            return null; // FurnitureMirror owns it (it may have been tracked here before the plugin knew it)
        }
        ItemStack item = display.getItemStack();
        if (item == null || item.getType().isAir() || !source.isCustomItem(item)) {
            return null; // only custom items have the attachable that draws them on a stand
        }
        Transformation transformation = display.getTransformation();
        float scale = transformation.getScale().y();
        Location l = display.getLocation();
        DisplayGeometry.Pose pose = DisplayGeometry.itemDisplay(l.getX(), l.getY(), l.getZ(), l.getYaw(),
                transformation.getTranslation(), transformation.getLeftRotation(), scale, yOffset, yawOffset);
        return new Look(pose, item, null, scale, false);
    }

    private Look describeFrame(ItemFrame frame) {
        BlockFace facing = frame.getFacing();
        if (facing == BlockFace.UP || facing == BlockFace.DOWN) {
            return null; // a stand cannot lie down
        }
        ItemStack item = frame.getItem();
        if (item == null || item.getType().isAir() || !source.isCustomItem(item)) {
            return null; // a vanilla item in a frame already looks right through Geyser
        }
        Location l = frame.getLocation();
        DisplayGeometry.Pose pose = DisplayGeometry.itemFrame(l.getX(), l.getY(), l.getZ(),
                DisplayGeometry.facingYaw(facing), FRAME_SCALE, yawOffset);
        return new Look(pose, item, null, FRAME_SCALE, settings.hideInvisibleFrames() && !frame.isVisible());
    }

    private Look describeBlock(BlockDisplay display) {
        Material material = display.getBlock().getMaterial();
        if (material.isAir() || !material.isItem()) {
            return null;
        }
        Transformation transformation = display.getTransformation();
        Location l = display.getLocation();
        DisplayGeometry.Pose pose = DisplayGeometry.blockDisplay(l.getX(), l.getY(), l.getZ(), l.getYaw(),
                transformation.getTranslation(), transformation.getLeftRotation(), transformation.getScale(), yawOffset);
        return new Look(pose, new ItemStack(material), null, DisplayGeometry.blockStandScale(transformation.getScale()), false);
    }

    private Look describeText(Tracked t, TextDisplay display) {
        Component text = display.text();
        if (LegacyText.isBlank(text) || !LegacyText.opacityVisible(display.getTextOpacity())) {
            return null;
        }
        if (!Objects.equals(text, t.lastText)) { // convert only when the text changed
            t.lastLegacy = LegacyText.toLegacy(text);
            t.lastText = text;
        }
        Transformation transformation = display.getTransformation();
        Location l = display.getLocation(); // a passenger of a mob reports its own position, which is where it is drawn
        DisplayGeometry.Pose pose = DisplayGeometry.text(l.getX(), l.getY(), l.getZ(), l.getYaw(),
                transformation.getTranslation(), LegacyText.lineCount(t.lastLegacy), settings.textYOffset());
        return new Look(pose, null, t.lastLegacy, 1f, settings.textHideOriginal());
    }

    // ---- keeping the stand in sync -------------------------------------------------------------------------

    private void apply(Tracked t, ArmorStand stand, Look look) {
        if (look.helmet() != null && t.helmet.update(look.helmet())) {
            stand.getEquipment().setHelmet(look.helmet());
        }
        if (look.name() != null && t.name.update(look.name())) {
            stand.customName(LegacyComponentSerializer.legacySection().deserialize(look.name()));
        }
        AttributeInstance scale = stand.getAttribute(Attribute.SCALE);
        double wanted = Math.max(0.0625, Math.min(16, look.standScale()));
        if (scale != null && Math.abs(scale.getBaseValue() - wanted) > 0.001) {
            scale.setBaseValue(wanted);
        }
        DisplayGeometry.Pose pose = look.pose();
        Location current = stand.getLocation();
        double dx = current.getX() - pose.x();
        double dy = current.getY() - pose.y();
        double dz = current.getZ() - pose.z();
        if (dx * dx + dy * dy + dz * dz > 1.0E-6 || Math.abs(current.getYaw() - pose.yaw()) > 0.01f) {
            stand.teleportAsync(new Location(stand.getWorld(), pose.x(), pose.y(), pose.z(), pose.yaw(), 0));
        }
    }

    /**
     * Shows the stand to the Bedrock players this thread owns, and hides it again from those who may not see the
     * original (a plugin hid it from them) or are beyond a text display's view range. Players of other regions get
     * it on a later pass, once their region and the entity's merge.
     */
    private void updateViewers(Tracked t, ArmorStand stand, boolean hideOriginal) {
        Entity original = t.original;
        for (Player player : support.players()) {
            try {
                if (!plugin.getServer().isOwnedByCurrentRegion(player) || !player.isOnline()
                        || !player.getWorld().equals(stand.getWorld())) {
                    continue;
                }
                boolean hiddenByUs = t.hiddenFrom.contains(player.getUniqueId());
                // A plugin that shows a display to chosen players only (hologram plugins) decides who sees the mirror.
                boolean allowed = (hiddenByUs || player.canSee(original)) && inRange(t, player);
                if (allowed && !player.canSee(stand)) {
                    player.showEntity(plugin, stand);
                } else if (!allowed && player.canSee(stand)) {
                    player.hideEntity(plugin, stand);
                }
                if (hideOriginal && allowed && !hiddenByUs) {
                    player.hideEntity(plugin, original);
                    t.hiddenFrom.add(player.getUniqueId());
                } else if (hiddenByUs && !(hideOriginal && allowed)) {
                    player.showEntity(plugin, original);
                    t.hiddenFrom.remove(player.getUniqueId());
                }
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.FINE, "Could not show a display mirror to " + player.getName(), e);
            }
        }
    }

    /** Only text displays carry a view range ({@code view_range} times 64 blocks); the others use the stand's tracking range. */
    private static boolean inRange(Tracked t, Player player) {
        if (t.kind != Kind.TEXT) {
            return true;
        }
        double range = Math.max(8, ((TextDisplay) t.original).getViewRange() * VIEW_RANGE_BLOCKS);
        return player.getLocation().distanceSquared(t.original.getLocation()) <= range * range;
    }
}
