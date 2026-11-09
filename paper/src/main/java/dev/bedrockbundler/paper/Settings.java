package dev.bedrockbundler.paper;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;

record Settings(
        boolean autoConvert,
        String packName,
        int iconSize,
        boolean fonts,
        int fontCellUnits,
        int guiOffsetX,
        boolean furniture,
        float furnitureYawOffset,
        double furnitureYOffset,
        SyncType syncType,
        String syncFolder,
        String httpBind,
        int httpPort,
        String httpToken
) {

    enum SyncType {
        AUTO, LOCAL, FOLDER, HTTP, OFF
    }

    static Settings load(FileConfiguration config) {
        SyncType syncType;
        try {
            syncType = SyncType.valueOf(config.getString("sync.type", "AUTO").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            syncType = SyncType.AUTO;
        }
        return new Settings(
                config.getBoolean("auto-convert", true),
                config.getString("pack-name", "BedrockBundler"),
                Math.max(16, Math.min(256, config.getInt("icon-size", 64))),
                config.getBoolean("fonts.enabled", true),
                Math.max(4, Math.min(256, config.getInt("fonts.cell-size", 32))),
                config.getInt("fonts.gui-offset-x", -16),
                config.getBoolean("furniture.enabled", true),
                (float) config.getDouble("furniture.yaw-offset", 0),
                config.getDouble("furniture.y-offset", 0),
                syncType,
                config.getString("sync.folder", ""),
                config.getString("sync.http.bind", "0.0.0.0"),
                config.getInt("sync.http.port", 8765),
                config.getString("sync.http.token", ""));
    }
}
