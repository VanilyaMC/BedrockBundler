package dev.bedrockbundler.paper;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrowableProjectile;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import dev.bedrockbundler.common.convert.PaintingConverter;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Makes furniture (item displays of the content plugin) visible on Bedrock.
 *
 * <p>Bedrock has no item display entity. For every furniture display this keeps an invisible marker armor stand
 * wearing the same item, shown only to Bedrock players. Geyser renders the item on the stand's head through the
 * item's attachable, whose "furniture" pose (see the converter) draws the model like the display would.
 *
 * <p>Region-threading safe (Folia, Canvas): each display is handled by a task on the display's own entity
 * scheduler, the stand is spawned at the display's position (same region) and moved with
 * {@code teleportAsync}, and a stand is shown only to Bedrock players in the display's own region (players
 * elsewhere get it once their region and the display's merge). On Paper the same schedulers run on the main
 * thread.
 */
final class FurnitureMirror implements Listener {

    private static final String TAG = "bedrockbundler_furniture_mirror";
    /** Tag of the stands of BedrockAdder, the former name; they are not saved, but a crash could leave some. */
    private static final String LEGACY_TAG = "bedrockadder_furniture_mirror";
    private static final long SYNC_TICKS = 10;
    /** Displays get their item a moment after spawning; keep checking new ones this many times. */
    private static final int PENDING_ATTEMPTS = 6;
    /**
     * Blocks a stand keeps off the surface its model touches (an item frame's wall, the floor under furniture):
     * Bedrock lights an entity from the block at its position, and exactly on the surface that can be the solid
     * block, which draws the model black.
     */
    private static final double LIGHT_NUDGE = 0.002;

    private final BedrockBundlerPlugin plugin;
    private final BedrockPlayers bedrock;
    private final ContentSource source;
    private final float yawOffset;
    private final double yOffset;
    /** Display UUID -> mirror stand. Written from many region threads. */
    private final Map<UUID, ArmorStand> mirrors = new ConcurrentHashMap<>();
    /** Display UUID -> its sync task, so a display is never tracked twice. */
    private final Map<UUID, ScheduledTask> tasks = new ConcurrentHashMap<>();
    /** Online Bedrock players, so region threads never have to ask Floodgate about everyone. */
    private final Map<UUID, Player> bedrockPlayers = new ConcurrentHashMap<>();
    private volatile boolean running;

    FurnitureMirror(BedrockBundlerPlugin plugin, BedrockPlayers bedrock, ContentSource source, float yawOffset, double yOffset) {
        this.plugin = plugin;
        this.bedrock = bedrock;
        this.source = source;
        this.yawOffset = yawOffset;
        this.yOffset = yOffset;
    }

    void start() {
        running = true;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.getScheduler().run(plugin, t -> {
                if (bedrock.isBedrock(player)) {
                    bedrockPlayers.put(player.getUniqueId(), player);
                }
            }, null);
        }
        if (!Scheduling.isFolia()) {
            // Displays already loaded (plugin reload). Folia has no global entity list; there, chunks loading
            // later fire EntityAddToWorldEvent, and mirrors are never saved, so nothing is left to clean up.
            for (World world : plugin.getServer().getWorlds()) {
                for (Entity entity : world.getEntities()) {
                    if (entity.getScoreboardTags().contains(TAG) || entity.getScoreboardTags().contains(LEGACY_TAG)) {
                        entity.remove();
                    } else if (entity instanceof ItemDisplay display) {
                        track(display);
                    } else if (entity instanceof Projectile projectile) {
                        trackProjectile(projectile);
                    } else if (entity instanceof ItemFrame frame) {
                        trackFrame(frame);
                    } else if (entity instanceof Painting painting) {
                        trackPainting(painting);
                    }
                }
            }
        }
    }

    void stop() {
        running = false;
        HandlerList.unregisterAll(this);
        tasks.values().forEach(ScheduledTask::cancel);
        tasks.clear();
        for (ArmorStand mirror : mirrors.values()) {
            removeMirror(mirror);
        }
        mirrors.clear();
        bedrockPlayers.clear();
    }

    int count() {
        return mirrors.size();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdd(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof ItemDisplay display) {
            track(display);
        } else if (event.getEntity() instanceof Projectile projectile) {
            trackProjectile(projectile);
        } else if (event.getEntity() instanceof ItemFrame frame) {
            trackFrame(frame);
        } else if (event.getEntity() instanceof Painting painting) {
            trackPainting(painting);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof ItemDisplay || event.getEntity() instanceof Projectile
                || event.getEntity() instanceof ItemFrame || event.getEntity() instanceof Painting) {
            untrack(event.getEntity().getUniqueId());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Floodgate may need a moment to register the session. The display tasks show the stands to the player
        // on their next run, each on the thread that owns that stand.
        player.getScheduler().runDelayed(plugin, t -> {
            if (player.isOnline() && bedrock.isBedrock(player)) {
                bedrockPlayers.put(player.getUniqueId(), player);
            }
        }, null, 5);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        bedrockPlayers.remove(event.getPlayer().getUniqueId());
    }

    /** Starts the per-display task on the display's own thread. */
    private void track(ItemDisplay display) {
        UUID id = display.getUniqueId();
        if (!running || tasks.containsKey(id)) {
            return;
        }
        int[] attempts = {0};
        ScheduledTask task = display.getScheduler().runAtFixedRate(plugin, t -> {
            ArmorStand mirror = mirrors.get(id);
            if (mirror == null) {
                boolean created = false;
                if (source.isFurniture(display)) {
                    try {
                        createMirror(display);
                        created = true;
                    } catch (RuntimeException e) {
                        // e.g. a translation that reaches into a chunk owned by another region
                        plugin.getLogger().log(Level.FINE, "Could not mirror furniture " + display.getUniqueId(), e);
                    }
                }
                if (!created && ++attempts[0] >= PENDING_ATTEMPTS) {
                    t.cancel();
                    tasks.remove(id);
                }
                return;
            }
            if (!mirror.isValid()) {
                mirrors.remove(id);
                return; // recreated on the next run
            }
            update(display, mirror);
            showToBedrock(mirror);
        }, () -> untrack(id), 1, SYNC_TICKS);
        if (task != null && tasks.putIfAbsent(id, task) != null) {
            task.cancel(); // tracked concurrently by another event
        }
    }

    private void untrack(UUID displayId) {
        ScheduledTask task = tasks.remove(displayId);
        if (task != null) {
            task.cancel();
        }
        ArmorStand mirror = mirrors.remove(displayId);
        if (mirror != null) {
            removeMirror(mirror);
        }
    }

    private void removeMirror(ArmorStand mirror) {
        if (!Scheduling.isFolia() || plugin.getServer().isOwnedByCurrentRegion(mirror)) {
            mirror.remove();
            return;
        }
        // The stand sits in another region; remove it on its own thread. While the plugin is disabling no task
        // can be scheduled any more; the stand is not persistent, so it is gone with its chunk.
        try {
            mirror.getScheduler().execute(plugin, mirror::remove, null, 1L);
        } catch (RuntimeException ignored) {
            // plugin disabled
        }
    }

    /**
     * Thrown custom items: tridents, and snowballs, eggs, ender pearls and the like (ItemsAdder's Boogie Bomb is a
     * snowball). Java draws them with the custom model (the content plugin's packet item display for a trident, the
     * item itself for the others); Bedrock shows the vanilla projectile. A stand wearing the item follows the
     * projectile every tick, and the vanilla look of a thrown item is hidden from Bedrock players. For a trident,
     * Nexo turns its display by the trident's yaw and pitch (arrow angles: yaw = atan2(vx, vz), pitch positive
     * upwards). The stand faces the same way (an entity yaw is the negated arrow yaw) and carries the trident's
     * pitch, which the item's attachable reads ({@code q.body_x_rotation}) to tilt the model; Bedrock does not
     * tilt the stand itself.
     */
    private void trackProjectile(Projectile projectile) {
        ItemStack item = projectile instanceof Trident trident ? trident.getItemStack()
                : projectile instanceof ThrowableProjectile thrown ? thrown.getItem() : null;
        UUID id = projectile.getUniqueId();
        if (!running || tasks.containsKey(id) || !source.isCustomItem(item)) {
            return;
        }
        boolean hide = !(projectile instanceof Trident);
        if (hide) {
            hideFromBedrock(projectile); // before the spawn packet goes out on the next tick
        }
        ScheduledTask task = projectile.getScheduler().runAtFixedRate(plugin, t -> {
            if (!projectile.isValid()) {
                untrack(id);
                return;
            }
            Location location = projectile.getLocation();
            Location target = location.clone().add(0, yOffset, 0);
            target.setYaw(-location.getYaw() + yawOffset);
            target.setPitch(location.getPitch());
            ArmorStand mirror = mirrors.get(id);
            if (mirror == null || !mirror.isValid()) {
                try {
                    mirror = projectile.getWorld().spawn(target, ArmorStand.class, this::configureStand);
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.FINE, "Could not mirror projectile " + id, e);
                    return;
                }
                mirror.getEquipment().setHelmet(item);
                mirrors.put(id, mirror);
            } else if (mirror.getLocation().distanceSquared(target) > 1.0E-6
                    || Math.abs(mirror.getLocation().getYaw() - target.getYaw()) > 0.01f
                    || Math.abs(mirror.getLocation().getPitch() - target.getPitch()) > 0.01f) {
                mirror.teleportAsync(target);
            }
            if (hide) {
                hideFromBedrock(projectile);
            }
            showToBedrock(mirror);
        }, () -> untrack(id), 1, 1);
        if (task != null && tasks.putIfAbsent(id, task) != null) {
            task.cancel();
        }
    }

    /**
     * Furniture in an invisible item frame (ItemsAdder's {@code entity: item_frame}). Bedrock has no invisible
     * item frame and draws the item as a flat icon in a visible frame, so the frame is hidden from Bedrock players
     * and a stand shows the model. Java's ItemFrameRenderer draws the item with its fixed display at half size,
     * centred on the wall surface (1/32 block behind the frame position) and turned like an item display with the
     * frame's yaw and pitch; the stand gets scale 0.5 and the frame's yaw. Floor and ceiling frames are drawn
     * upright (a stand's pitch cannot tilt furniture models), and the item's rotation inside the frame is not
     * shown.
     */
    private void trackFrame(ItemFrame frame) {
        UUID id = frame.getUniqueId();
        if (!running || tasks.containsKey(id)) {
            return;
        }
        int[] attempts = {0};
        ScheduledTask task = frame.getScheduler().runAtFixedRate(plugin, t -> {
            ItemStack item = frame.getItem();
            ArmorStand mirror = mirrors.get(id);
            if (mirror == null && (frame.isVisible() || !source.isCustomItem(item))) {
                // Frames get their item a moment after spawning.
                if (++attempts[0] >= PENDING_ATTEMPTS) {
                    t.cancel();
                    tasks.remove(id);
                }
                return;
            }
            if (mirror != null && !mirror.isValid()) {
                mirrors.remove(id);
                return; // recreated on the next run
            }
            BlockFace facing = frame.getFacing();
            Location location = frame.getLocation();
            // Exactly on the wall surface Bedrock may light the stand from the wall block (drawn dark); a hair in
            // front of it, the light comes from the block the frame hangs in.
            double depth = 1 / 32.0 - LIGHT_NUDGE;
            Location target = location.clone().add(-facing.getModX() * depth, -facing.getModY() * depth + yOffset,
                    -facing.getModZ() * depth);
            target.setYaw(location.getYaw() + yawOffset);
            target.setPitch(0);
            if (mirror == null) {
                try {
                    mirror = frame.getWorld().spawn(target, ArmorStand.class, this::configureStand);
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.FINE, "Could not mirror item frame " + id, e);
                    return;
                }
                AttributeInstance scale = mirror.getAttribute(Attribute.SCALE);
                if (scale != null) {
                    scale.setBaseValue(0.5);
                }
                mirrors.put(id, mirror);
            } else if (mirror.getLocation().distanceSquared(target) > 1.0E-6
                    || Math.abs(mirror.getLocation().getYaw() - target.getYaw()) > 0.01f
                    || Math.abs(mirror.getLocation().getPitch() - target.getPitch()) > 0.01f) {
                mirror.teleportAsync(target);
            }
            if (!Objects.equals(mirror.getEquipment().getHelmet(), item)) {
                mirror.getEquipment().setHelmet(item);
            }
            hideFromBedrock(frame);
            showToBedrock(mirror);
        }, () -> untrack(id), 1, SYNC_TICKS);
        if (task != null && tasks.putIfAbsent(id, task) != null) {
            task.cancel();
        }
    }

    /**
     * Paintings. Custom ones (a variant outside the minecraft namespace, e.g. ItemsAdderAdditions'): Bedrock only
     * has the vanilla motives. Vanilla ones: Geyser moves them half a block off (see PaintingConverter). The
     * painting is hidden from Bedrock players and a stand wears the item the converter made of it: a board
     * centred on the painting's position, front outwards when the stand takes the painting's yaw (like an item
     * frame). Bedrock players cannot break the painting.
     */
    private void trackPainting(Painting painting) {
        UUID id = painting.getUniqueId();
        if (!running || tasks.containsKey(id)) {
            return;
        }
        if (customPaintingItem(painting) != null) {
            hideFromBedrock(painting); // before the spawn packet goes out on the next tick
        }
        int[] attempts = {0};
        ScheduledTask task = painting.getScheduler().runAtFixedRate(plugin, t -> {
            // Plugins may set the custom variant right after spawning a vanilla one (ItemsAdderAdditions does).
            ItemStack item = customPaintingItem(painting);
            ArmorStand mirror = mirrors.get(id);
            if (item == null) {
                if (mirror == null && ++attempts[0] >= PENDING_ATTEMPTS) {
                    t.cancel();
                    tasks.remove(id);
                }
                return;
            }
            Location location = painting.getLocation();
            Location target = location.clone().add(0, yOffset, 0);
            target.setYaw(location.getYaw() + yawOffset);
            target.setPitch(0);
            if (mirror == null || !mirror.isValid()) {
                try {
                    mirror = painting.getWorld().spawn(target, ArmorStand.class, this::configureStand);
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.FINE, "Could not mirror painting " + id, e);
                    return;
                }
                mirrors.put(id, mirror);
            } else if (mirror.getLocation().distanceSquared(target) > 1.0E-6
                    || Math.abs(mirror.getLocation().getYaw() - target.getYaw()) > 0.01f) {
                mirror.teleportAsync(target);
            }
            if (!Objects.equals(mirror.getEquipment().getHelmet(), item)) {
                mirror.getEquipment().setHelmet(item);
            }
            hideFromBedrock(painting);
            showToBedrock(mirror);
        }, () -> untrack(id), 1, SYNC_TICKS);
        if (task != null && tasks.putIfAbsent(id, task) != null) {
            task.cancel();
        }
    }

    /** The item a stand wears for a painting (see PaintingConverter), or null when Geyser draws it. */
    private static ItemStack customPaintingItem(Painting painting) {
        String variant;
        try {
            variant = painting.getArt().getKey().toString();
        } catch (RuntimeException e) {
            return null;
        }
        NamespacedKey model = PaintingConverter.mirrored(variant)
                ? NamespacedKey.fromString(PaintingConverter.itemModel(variant)) : null;
        if (model == null) {
            return null;
        }
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(model);
        item.setItemMeta(meta);
        return item;
    }

    /** Hides an entity from the Bedrock players this thread owns (see {@link #showToBedrock}). */
    private void hideFromBedrock(Entity entity) {
        for (Player player : bedrockPlayers.values()) {
            try {
                if (plugin.getServer().isOwnedByCurrentRegion(player) && player.isOnline()
                        && player.getWorld().equals(entity.getWorld()) && player.canSee(entity)) {
                    player.hideEntity(plugin, entity);
                }
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.FINE, "Could not hide an entity from " + player.getName(), e);
            }
        }
    }

    /** Runs on the display's thread; the stand spawns at the display's position, so in the same region. */
    private void createMirror(ItemDisplay display) {
        ArmorStand mirror = display.getWorld().spawn(target(display), ArmorStand.class, this::configureStand);
        mirrors.put(display.getUniqueId(), mirror);
        update(display, mirror);
        showToBedrock(mirror);
    }

    private void configureStand(ArmorStand stand) {
        stand.setVisibleByDefault(false); // Java players never see it
        stand.setPersistent(false);       // never saved with the chunk
        stand.addScoreboardTag(TAG);
        stand.setInvisible(true);
        stand.setMarker(true);            // no hitbox: clicks reach the plugin's own hitbox entities
        stand.setGravity(false);
        stand.setSilent(true);
        stand.setInvulnerable(true);
        stand.setBasePlate(false);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            try {
                stand.addEquipmentLock(slot, ArmorStand.LockType.REMOVING_OR_CHANGING);
            } catch (IllegalArgumentException ignored) {
                // slot that armor stands do not have
            }
        }
    }

    /**
     * Shows the stand to the Bedrock players this thread owns. Runs on every sync, so players who join later or
     * whose region merges with the display's get the stand too; touching players of other regions is not allowed.
     */
    private void showToBedrock(ArmorStand mirror) {
        for (Player player : bedrockPlayers.values()) {
            try {
                if (plugin.getServer().isOwnedByCurrentRegion(player) && player.isOnline()
                        && player.getWorld().equals(mirror.getWorld()) && !player.canSee(mirror)) {
                    player.showEntity(plugin, mirror);
                }
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.FINE, "Could not show a furniture mirror to " + player.getName(), e);
            }
        }
    }

    private void update(ItemDisplay display, ArmorStand mirror) {
        ItemStack item = source.displayItem(display);
        if (item != null && !Objects.equals(mirror.getEquipment().getHelmet(), item)) {
            mirror.getEquipment().setHelmet(item);
        }
        float scale = source.transformation(display).getScale().y();
        AttributeInstance scaleAttribute = mirror.getAttribute(Attribute.SCALE);
        if (scaleAttribute != null && Math.abs(scaleAttribute.getBaseValue() - scale) > 0.001) {
            scaleAttribute.setBaseValue(Math.max(0.0625, Math.min(16, scale)));
        }
        Location target = target(display);
        Location current = mirror.getLocation();
        if (current.distanceSquared(target) > 1.0E-6 || Math.abs(current.getYaw() - target.getYaw()) > 0.01f) {
            mirror.teleportAsync(target);
        }
    }

    /**
     * Where the stand's feet go: the furniture pose draws the model centre at the feet, and an item_display draws
     * it at its position plus the transformation translation. Bedrock lights the stand from the block at its feet,
     * so that is the block Java lights the display from.
     */
    private Location target(ItemDisplay display) {
        Location location = display.getLocation();
        Transformation transformation = source.transformation(display);
        // The translation is in the display's local frame, which is turned by the entity's yaw.
        Vector3f translation = new Vector3f(transformation.getTranslation())
                .rotateY((float) Math.toRadians(-location.getYaw()));
        Vector3f angles = transformation.getLeftRotation().getEulerAnglesYXZ(new Vector3f());
        // A display exactly on the floor would be lit from the block below; a hair above it is not.
        Location target = location.clone().add(translation.x(),
                translation.y() + yOffset + LIGHT_NUDGE, translation.z());
        target.setYaw(location.getYaw() - (float) Math.toDegrees(angles.y()) + yawOffset);
        target.setPitch(0);
        return target;
    }
}
