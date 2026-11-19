package dev.bedrockbundler.paper.model;

import dev.bedrockbundler.common.channel.ModelMessages;
import dev.bedrockbundler.common.model.ModelSpec;
import dev.bedrockbundler.common.spec.Bundle;
import dev.bedrockbundler.paper.Scheduling;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Shows 3D models of a model plugin (BetterModel) to Bedrock players.
 *
 * <p>Models are display entities on Java, which Geyser cannot translate. For every model this keeps one invisible
 * marker armor stand, shown only to Bedrock players near it. Just before a player gets the stand, an ASSIGN message
 * ({@link ModelMessages}) tells the Geyser extension which converted model the stand stands for, and the extension
 * swaps the stand for that custom Bedrock entity. While the model runs, STATE messages carry its bone visibility and
 * animation bits, which the Bedrock entity's animation controllers read as entity properties.
 *
 * <p>The stand follows the model, so it is driven by a task on its own entity scheduler (Folia/Canvas safe).
 * It is a real server entity (it ticks and counts in entity statistics), unlike the packet-only entities other
 * Geyser model plugins use; with a few hundred models in loaded chunks that is negligible.
 */
public final class ModelMirror implements Listener {

    private static final String TAG = "bedrockbundler_model_mirror";
    /** Ticks between syncs of position, viewers and state. */
    private static final long PERIOD = 2;
    /** Ticks between full state resends (heals a spawn that raced the first state). */
    private static final int RESEND_TICKS = 20;
    private static final int PENDING_ATTEMPTS = 100;

    private final JavaPlugin plugin;
    private final java.util.function.Predicate<Player> bedrock;
    private final ModelSource source;
    private final double range;
    private final Map<UUID, Player> bedrockPlayers = new ConcurrentHashMap<>();
    private final Map<ModelTracking, Tracked> tracked = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Set<String> unknownLogged = ConcurrentHashMap.newKeySet();
    private volatile Map<String, ModelSpec> specs = Map.of();
    private volatile String bundleHash = "";
    private volatile boolean running;
    private volatile boolean enabled;

    public ModelMirror(JavaPlugin plugin, java.util.function.Predicate<Player> bedrock, ModelSource source, double range) {
        this.plugin = plugin;
        this.bedrock = bedrock;
        this.source = source;
        this.range = range;
    }

    /** One tracked model and its stand. Touched by the stand's own thread, except creation. */
    private final class Tracked {
        final ModelTracking model;
        final ModelSpec spec;
        ArmorStand stand;
        ScheduledTask task;
        final Set<UUID> viewers = new HashSet<>();
        List<ModelMessages.Value> lastValues = List.of();
        float lastScale = -1;
        int sinceSend;
        /** Periods during which the state is sent every time: a new viewer's spawn can arrive after the first state. */
        int burst;
        boolean removed;

        Tracked(ModelTracking model, ModelSpec spec) {
            this.model = model;
            this.spec = spec;
        }
    }

    public void start() {
        running = true;
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, ModelMessages.CHANNEL);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.getScheduler().run(plugin, t -> {
                if (bedrock.test(player)) {
                    bedrockPlayers.put(player.getUniqueId(), player);
                }
            }, null);
        }
        source.start(plugin, this::track);
    }

    public void stop() {
        running = false;
        source.stop();
        HandlerList.unregisterAll(this);
        List<Tracked> all;
        synchronized (tracked) {
            all = new ArrayList<>(tracked.values());
            tracked.clear();
        }
        for (Tracked t : all) {
            removeStand(t);
        }
        bedrockPlayers.clear();
    }

    /** Called after every conversion; models are matched to the converted specs by id. */
    public void bundle(Bundle bundle) {
        Map<String, ModelSpec> map = new HashMap<>();
        for (ModelSpec spec : bundle.models()) {
            map.put(spec.id(), spec);
        }
        this.specs = map;
        this.bundleHash = bundle.hash().substring(0, Math.min(12, bundle.hash().length()));
        plugin.getLogger().info("Bedrock models: " + map.size() + " converted");
    }

    /** Kill switch (config {@code models.enabled}); false hides every model from Bedrock at once. */
    public void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int count() {
        return tracked.size();
    }

    // ------------------------------------------------------------------ tracking

    private void track(ModelTracking model) {
        if (!running) {
            return;
        }
        ModelSpec spec = specs.get(model.model());
        if (spec == null) {
            if (unknownLogged.add(model.model())) {
                plugin.getLogger().fine("No converted Bedrock model for " + model.model());
            }
            return;
        }
        Tracked t = new Tracked(model, spec);
        synchronized (tracked) {
            if (tracked.containsKey(model)) {
                return;
            }
            tracked.put(model, t);
        }
        int[] attempts = {0};
        spawnWhenPlaced(t, attempts);
    }

    /** The model's position can be unknown for a moment after it is created. */
    private void spawnWhenPlaced(Tracked t, int[] attempts) {
        if (!running || t.removed) {
            return;
        }
        Location location = t.model.location();
        if (location == null || location.getWorld() == null) {
            if (++attempts[0] > PENDING_ATTEMPTS) {
                tracked.remove(t.model);
                return;
            }
            Scheduling.globalLater(plugin, () -> spawnWhenPlaced(t, attempts), 2);
            return;
        }
        plugin.getServer().getRegionScheduler().run(plugin, location, task -> {
            if (!running || t.removed) {
                return;
            }
            try {
                t.stand = location.getWorld().spawn(location, ArmorStand.class, this::configureStand);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.FINE, "Could not mirror model " + t.model.model(), e);
                tracked.remove(t.model);
                return;
            }
            t.task = t.stand.getScheduler().runAtFixedRate(plugin, st -> tick(t), () -> untrack(t), 1, PERIOD);
            if (t.task == null) {
                untrack(t);
            }
        });
    }

    private void configureStand(ArmorStand stand) {
        stand.setVisibleByDefault(false); // Java players never see it
        stand.setPersistent(false);
        stand.addScoreboardTag(TAG);
        // Not invisible: Geyser would carry the invisible flag over to the Bedrock model entity and nothing would
        // be drawn. Where the model does not replace the stand (no matching Geyser bundle) a tiny stand shows.
        stand.setMarker(true);
        stand.setSmall(true);
        stand.setBasePlate(false);
        stand.setGravity(false);
        stand.setSilent(true);
        stand.setInvulnerable(true);
    }

    private void untrack(Tracked t) {
        tracked.remove(t.model);
        removeStand(t);
    }

    private void removeStand(Tracked t) {
        t.removed = true;
        ScheduledTask task = t.task;
        if (task != null) {
            task.cancel();
        }
        ArmorStand stand = t.stand;
        if (stand == null) {
            return;
        }
        byte[] remove = ModelMessages.remove(stand.getEntityId());
        for (UUID id : new ArrayList<>(t.viewers)) {
            Player player = bedrockPlayers.get(id);
            if (player != null) {
                send(player, remove);
            }
        }
        t.viewers.clear();
        try {
            if (!Scheduling.isFolia()) {
                stand.remove();
            } else {
                stand.getScheduler().execute(plugin, stand::remove, null, 1L);
            }
        } catch (RuntimeException ignored) {
            // plugin disabling; the stand is not persistent and goes with its chunk
        }
    }

    // ------------------------------------------------------------------ per-stand task

    private void tick(Tracked t) {
        if (!running || t.removed || !t.stand.isValid()) {
            untrack(t);
            return;
        }
        if (t.model.gone()) {
            untrack(t);
            return;
        }
        Location target = t.model.location();
        if (target != null && target.getWorld() != null) {
            Location current = t.stand.getLocation();
            if (current.getWorld() != target.getWorld() || current.distanceSquared(target) > 1.0E-6
                    || Math.abs(current.getYaw() - target.getYaw()) > 0.01f) {
                t.stand.teleportAsync(target);
            }
        }

        boolean newViewer = syncViewers(t);
        if (t.viewers.isEmpty()) {
            return;
        }
        sendState(t, newViewer);
    }

    /** Shows the stand to Bedrock players who should see it, hides it from the others. Returns true if one is new. */
    private boolean syncViewers(Tracked t) {
        boolean added = false;
        Location at = t.stand.getLocation();
        for (Player player : bedrockPlayers.values()) {
            try {
                if (!plugin.getServer().isOwnedByCurrentRegion(player)) {
                    continue;
                }
                boolean should = enabled && player.isOnline() && player.getWorld().equals(at.getWorld())
                        && player.getLocation().distanceSquared(at) <= range * range && t.model.visibleTo(player);
                boolean viewing = t.viewers.contains(player.getUniqueId());
                if (should && !viewing) {
                    send(player, ModelMessages.assign(t.stand.getEntityId(), t.spec.id(), bundleHash, t.model.scale()));
                    player.showEntity(plugin, t.stand);
                    t.viewers.add(player.getUniqueId());
                    added = true;
                } else if (!should && viewing) {
                    player.hideEntity(plugin, t.stand);
                    send(player, ModelMessages.remove(t.stand.getEntityId()));
                    t.viewers.remove(player.getUniqueId());
                }
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.FINE, "Could not update a model viewer " + player.getName(), e);
            }
        }
        t.viewers.removeIf(id -> !bedrockPlayers.containsKey(id));
        return added;
    }

    private void sendState(Tracked t, boolean newViewer) {
        Set<String> hidden = new HashSet<>();
        Set<String> animations = new HashSet<>();
        t.model.sample(hidden, animations);
        List<ModelMessages.Value> values = values(t.spec, hidden, animations);
        float scale = t.model.scale();
        t.sinceSend++;
        boolean changed = !values.equals(t.lastValues) || scale != t.lastScale;
        if (newViewer) {
            t.burst = 3;
        }
        if (!changed && t.burst <= 0 && t.sinceSend < RESEND_TICKS / PERIOD) {
            return;
        }
        if (t.burst > 0) {
            t.burst--;
        }
        t.lastValues = values;
        t.lastScale = scale;
        t.sinceSend = 0;
        byte[] message = ModelMessages.state(t.stand.getEntityId(), scale, values);
        for (UUID id : t.viewers) {
            Player player = bedrockPlayers.get(id);
            if (player != null) {
                send(player, message);
            }
        }
    }

    /** Packs bone and animation flags into the 24-bit integers the entity properties hold. */
    static List<ModelMessages.Value> values(ModelSpec spec, Set<String> hiddenBones, Set<String> animations) {
        List<ModelMessages.Value> values = new ArrayList<>();
        pack(values, ModelMessages.BONE, spec.bones(), hiddenBones, ModelSpec::boneKey);
        pack(values, ModelMessages.ANIMATION, spec.animations(), animations, ModelSpec::animationKey);
        return values;
    }

    private static void pack(List<ModelMessages.Value> out, byte kind, List<String> names, Set<String> active,
                             java.util.function.UnaryOperator<String> key) {
        Set<String> normalized = new HashSet<>();
        for (String name : active) {
            normalized.add(key.apply(name));
        }
        int properties = (names.size() + ModelSpec.BITS_PER_PROPERTY - 1) / ModelSpec.BITS_PER_PROPERTY;
        for (int p = 0; p < properties; p++) {
            int value = 0;
            for (int bit = 0; bit < ModelSpec.BITS_PER_PROPERTY; bit++) {
                int index = p * ModelSpec.BITS_PER_PROPERTY + bit;
                if (index < names.size() && normalized.contains(key.apply(names.get(index)))) {
                    value |= 1 << bit;
                }
            }
            out.add(new ModelMessages.Value(kind, p, value));
        }
    }

    private void send(Player player, byte[] message) {
        try {
            player.sendPluginMessage(plugin, ModelMessages.CHANNEL, message);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.FINE, "Could not send a model message to " + player.getName(), e);
        }
    }

    // ------------------------------------------------------------------ players

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        player.getScheduler().runDelayed(plugin, t -> {
            if (player.isOnline() && bedrock.test(player)) {
                bedrockPlayers.put(player.getUniqueId(), player);
            }
        }, null, 40);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        bedrockPlayers.remove(event.getPlayer().getUniqueId());
    }
}
