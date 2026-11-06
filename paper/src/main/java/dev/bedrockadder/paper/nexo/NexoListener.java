package dev.bedrockadder.paper.nexo;

import com.nexomc.nexo.api.events.NexoItemsLoadedEvent;
import com.nexomc.nexo.api.events.resourcepack.NexoPostPackGenerateEvent;
import dev.bedrockadder.paper.BedrockAdderPlugin;
import dev.bedrockadder.paper.Scheduling;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Converts after Nexo builds its pack. Nexo writes and uploads the zip after the event, so the conversion waits a
 * moment and then reads the built pack. Items load asynchronously; the first conversion waits for them too.
 */
final class NexoListener implements Listener {

    /** Delay after Nexo's pack event before the pack is read, so the built zip exists. */
    private static final long PACK_DELAY_TICKS = 20L * 3;

    private final BedrockAdderPlugin plugin;
    private volatile boolean itemsLoaded;
    private volatile boolean pending;

    NexoListener(BedrockAdderPlugin plugin, NexoSource source) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemsLoaded(NexoItemsLoadedEvent event) {
        boolean first = !itemsLoaded;
        itemsLoaded = true;
        if (first || pending) {
            schedule();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPackGenerated(NexoPostPackGenerateEvent event) {
        if (!itemsLoaded) {
            pending = true; // converted once the items are there
            return;
        }
        schedule();
    }

    private void schedule() {
        if (!plugin.autoConvert()) {
            return;
        }
        pending = false;
        Scheduling.globalLater(plugin, () -> plugin.convertFromDisk(false, null), PACK_DELAY_TICKS);
    }
}
