package dev.bedrockbundler.paper;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
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
                    } else if (entity instanceof Trident trident) {
                        trackTrident(trident);
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
        } else if (event.getEntity() instanceof Trident trident) {
            trackTrident(trident);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof ItemDisplay || event.getEntity() instanceof Trident) {
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
     * Thrown custom tridents. The content plugin draws them with its own (packet) item display, which Bedrock
     * cannot show, so a stand wearing the trident follows it every tick. Nexo turns that display by the trident's
     * yaw and pitch (arrow angles: yaw = atan2(vx, vz), pitch positive upwards). The stand faces the same way
     * (an entity yaw is the negated arrow yaw) and carries the trident's pitch, which the item's attachable reads
     * ({@code q.body_x_rotation}) to tilt the model; Bedrock does not tilt the stand itself.
     */
    private void trackTrident(Trident trident) {
        UUID id = trident.getUniqueId();
        if (!running || tasks.containsKey(id) || !source.isCustomItem(trident.getItemStack())) {
            return;
        }
        ScheduledTask task = trident.getScheduler().runAtFixedRate(plugin, t -> {
            if (!trident.isValid()) {
                untrack(id);
                return;
            }
            Location location = trident.getLocation();
            Location target = location.clone().add(0, yOffset - 0.5, 0);
            target.setYaw(-location.getYaw() + yawOffset);
            target.setPitch(location.getPitch());
            ArmorStand mirror = mirrors.get(id);
            if (mirror == null || !mirror.isValid()) {
                try {
                    mirror = trident.getWorld().spawn(target, ArmorStand.class, this::configureStand);
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.FINE, "Could not mirror trident " + id, e);
                    return;
                }
                mirror.getEquipment().setHelmet(trident.getItemStack());
                mirrors.put(id, mirror);
            } else if (mirror.getLocation().distanceSquared(target) > 1.0E-6
                    || Math.abs(mirror.getLocation().getYaw() - target.getYaw()) > 0.01f
                    || Math.abs(mirror.getLocation().getPitch() - target.getPitch()) > 0.01f) {
                mirror.teleportAsync(target);
            }
            showToBedrock(mirror);
        }, () -> untrack(id), 1, 1);
        if (task != null && tasks.putIfAbsent(id, task) != null) {
            task.cancel();
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
     * Where the stand's feet go: the furniture pose draws the model centre half a block (times the stand's
     * scale) above the feet, and an item_display draws it at its position plus the transformation translation.
     */
    private Location target(ItemDisplay display) {
        Location location = display.getLocation();
        Transformation transformation = source.transformation(display);
        // The translation is in the display's local frame, which is turned by the entity's yaw.
        Vector3f translation = new Vector3f(transformation.getTranslation())
                .rotateY((float) Math.toRadians(-location.getYaw()));
        Vector3f angles = transformation.getLeftRotation().getEulerAnglesYXZ(new Vector3f());
        float scale = transformation.getScale().y();

        Location target = location.clone().add(translation.x(), translation.y() + yOffset - 0.5 * scale, translation.z());
        target.setYaw(location.getYaw() - (float) Math.toDegrees(angles.y()) + yawOffset);
        target.setPitch(0);
        return target;
    }
}
