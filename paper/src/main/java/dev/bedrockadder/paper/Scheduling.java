package dev.bedrockadder.paper;

import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Thread rules for Paper and Folia-based servers (Folia, Canvas). Paper's region schedulers also exist on plain
 * Paper, where they run on the main thread, so one code path serves both.
 */
public final class Scheduling {

    private static final boolean FOLIA = classExists("io.papermc.paper.threadedregions.RegionizedServer");

    private Scheduling() {
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    /** Runs on the global region (the main thread on Paper). */
    public static void global(Plugin plugin, Runnable task) {
        Server server = plugin.getServer();
        if (!FOLIA && server.isPrimaryThread()) {
            task.run();
        } else {
            server.getGlobalRegionScheduler().execute(plugin, task);
        }
    }

    public static void globalLater(Plugin plugin, Runnable task, long ticks) {
        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), Math.max(1, ticks));
    }

    /** Runs on the thread that owns the entity; does nothing if the entity is gone. */
    public static void entity(Plugin plugin, Entity entity, Runnable task, long delayTicks) {
        entity.getScheduler().runDelayed(plugin, t -> task.run(), null, Math.max(1, delayTicks));
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
