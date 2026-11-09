package dev.bedrockadder.paper.nexo;

import com.nexomc.nexo.api.events.NexoItemsLoadedEvent;
import com.nexomc.nexo.api.events.furniture.NexoFurnitureInteractEvent;
import com.nexomc.nexo.api.events.resourcepack.NexoPostPackGenerateEvent;
import dev.bedrockadder.paper.BedrockAdderPlugin;
import dev.bedrockadder.paper.Scheduling;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Converts after Nexo builds its pack. Nexo writes (and uploads) the zip after the event, so the conversion waits
 * until the zip on disk has changed and stopped growing, then reads it. Items load asynchronously; a pack event
 * that comes first is converted once they are there.
 */
final class NexoListener implements Listener {

    /** How often the zip is checked after a pack event. */
    private static final long POLL_TICKS = 20L;
    /** Give up waiting for a changed zip after this many checks and convert what is there. */
    private static final int MAX_POLLS = 30;
    /**
     * After the first item load, wait this long for Nexo's own pack event before converting the pack already on
     * disk (Nexo may be configured not to generate on start). A pack event in that time takes over.
     */
    private static final long STARTUP_FALLBACK_TICKS = 20L * 15;

    /** Interactions of one Bedrock tap arrive within a tick; a deliberate second tap takes longer. */
    private static final long INTERACT_DEBOUNCE_NANOS = 300_000_000L;

    private record LastInteraction(UUID furniture, long time) {
    }

    /** Bedrock player -> their last furniture interaction. Written from the players' region threads. */
    private final Map<UUID, LastInteraction> lastInteraction = new ConcurrentHashMap<>();
    private final BedrockAdderPlugin plugin;
    private final NexoSource source;
    /** Bumped on every pack event, so only the newest wait converts. */
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean itemsLoaded;
    private volatile boolean pending;
    private volatile boolean packEventSeen;

    NexoListener(BedrockAdderPlugin plugin, NexoSource source) {
        this.plugin = plugin;
        this.source = source;
    }

    /**
     * Geyser turns one Bedrock tap into two Java interactions (interact and interact-at, or both hands), so Nexo
     * toggled a door twice in the same tick: it opened and closed without the animation. A second interaction of
     * the same Bedrock player with the same furniture shortly after the first is dropped before Nexo sees it.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFurnitureInteract(NexoFurnitureInteractEvent event) {
        Player player = event.getPlayer();
        if (!plugin.isBedrock(player)) {
            return;
        }
        long now = System.nanoTime();
        UUID furniture = event.getBaseEntity().getUniqueId();
        LastInteraction last = lastInteraction.get(player.getUniqueId());
        if (last != null && last.furniture().equals(furniture) && now - last.time() < INTERACT_DEBOUNCE_NANOS) {
            event.setCancelled(true);
            return;
        }
        lastInteraction.put(player.getUniqueId(), new LastInteraction(furniture, now));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastInteraction.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemsLoaded(NexoItemsLoadedEvent event) {
        boolean first = !itemsLoaded;
        itemsLoaded = true;
        if (pending) {
            pending = false;
            waitForPack(source.packStamp());
        } else if (first && plugin.autoConvert()) {
            Scheduling.globalLater(plugin, () -> {
                if (!packEventSeen) {
                    plugin.convertFromDisk(false, null);
                }
            }, STARTUP_FALLBACK_TICKS);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPackGenerated(NexoPostPackGenerateEvent event) {
        packEventSeen = true;
        String before = source.packStamp();
        if (!itemsLoaded) {
            pending = true; // converted once the items are there
            return;
        }
        waitForPack(before);
    }

    private void waitForPack(String before) {
        if (!plugin.autoConvert()) {
            return;
        }
        poll(generation.incrementAndGet(), before, null, 0);
    }

    /** Converts once the zip differs from {@code before} and is unchanged since the previous check. */
    private void poll(long id, String before, String previous, int attempt) {
        Scheduling.globalLater(plugin, () -> {
            if (id != generation.get()) {
                return; // a newer pack event is waiting instead
            }
            String now = source.packStamp();
            boolean written = now != null && !now.equals(before) && now.equals(previous);
            if (written || attempt >= MAX_POLLS) {
                plugin.convertFromDisk(false, null);
            } else {
                poll(id, before, Objects.requireNonNullElse(now, ""), attempt + 1);
            }
        }, POLL_TICKS);
    }
}
