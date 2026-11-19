package dev.bedrockbundler.paper;

import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What every mirror stand needs: the list of online Bedrock players (so region threads never ask Floodgate about
 * everyone), stand setup and Folia-safe stand removal. The same rules as {@link FurnitureMirror}.
 */
final class MirrorSupport implements Listener {

    private final BedrockBundlerPlugin plugin;
    private final BedrockPlayers bedrock;
    private final Map<java.util.UUID, Player> bedrockPlayers = new ConcurrentHashMap<>();

    MirrorSupport(BedrockBundlerPlugin plugin, BedrockPlayers bedrock) {
        this.plugin = plugin;
        this.bedrock = bedrock;
    }

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.getScheduler().run(plugin, t -> {
                if (bedrock.isBedrock(player)) {
                    bedrockPlayers.put(player.getUniqueId(), player);
                }
            }, null);
        }
    }

    void stop() {
        HandlerList.unregisterAll(this);
        bedrockPlayers.clear();
    }

    Collection<Player> players() {
        return bedrockPlayers.values();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Floodgate may need a moment to register the session; the mirror tasks show their stands on their next run.
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

    /** Makes a freshly spawned stand an invisible, unsaved, unclickable carrier that Java players never see. */
    void configureStand(ArmorStand stand, String tag) {
        stand.setVisibleByDefault(false); // Java players never see it
        stand.setPersistent(false);       // never saved with the chunk
        stand.addScoreboardTag(tag);
        stand.setInvisible(true);
        stand.setMarker(true);            // no hitbox
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
     * Removes a stand. On Folia always one tick later on the stand's own thread: when the owner leaves the world
     * because its chunk unloads, removing the stand right away is refused. A stand that unloaded meanwhile is not
     * persistent and goes with its chunk; while the plugin disables no task can be scheduled any more.
     */
    void removeStand(Entity stand) {
        if (!Scheduling.isFolia()) {
            stand.remove();
            return;
        }
        try {
            stand.getScheduler().execute(plugin, stand::remove, null, 1L);
        } catch (RuntimeException ignored) {
            // plugin disabled
        }
    }
}
