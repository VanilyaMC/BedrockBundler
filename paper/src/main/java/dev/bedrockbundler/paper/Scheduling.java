package dev.bedrockbundler.paper;

import org.bukkit.Server;
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

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
