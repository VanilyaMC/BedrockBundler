package dev.bedrockbundler.paper;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * The {@code displays:} section of the config: which display entities and item frames get a mirror armor stand for
 * Bedrock players.
 *
 * @param enabled              master switch
 * @param maxMirrors           most mirror stands alive at once (a safety net against plugins that spawn thousands of displays)
 * @param items                mirror item displays of custom items that are not furniture
 * @param frames               mirror item frames holding a custom item
 * @param hideInvisibleFrames  hide an invisible frame from Bedrock players while its mirror shows (else its flat icon shows too)
 * @param blocks               mirror block displays as worn blocks (approximate)
 * @param text                 mirror text displays as name plates (Geyser already draws them; off by default)
 * @param textHideOriginal     hide the text display from Bedrock players while its mirror shows, so text is not drawn twice
 * @param textYOffset          fine-tuning of the name plate height, in blocks
 * @param range                a mirror exists only while a Bedrock player of the same world is this close (blocks); 0 = always
 * @param vanillaBlocks        also mirror item displays that show a vanilla block item (Geyser draws no item displays at all)
 */
record DisplaySettings(
        boolean enabled,
        int maxMirrors,
        boolean items,
        boolean frames,
        boolean hideInvisibleFrames,
        boolean blocks,
        boolean text,
        boolean textHideOriginal,
        double textYOffset,
        double range,
        boolean vanillaBlocks
) {

    static DisplaySettings load(FileConfiguration config) {
        return new DisplaySettings(
                config.getBoolean("displays.enabled", true),
                Math.max(1, Math.min(100_000, config.getInt("displays.max-mirrors", 2000))),
                config.getBoolean("displays.items.enabled", true),
                config.getBoolean("displays.frames.enabled", true),
                config.getBoolean("displays.frames.hide-invisible-original", true),
                config.getBoolean("displays.blocks.enabled", false),
                config.getBoolean("displays.text.enabled", false),
                config.getBoolean("displays.text.hide-original", true),
                config.getDouble("displays.text.y-offset", 0),
                Math.max(0, config.getDouble("displays.range", 64)),
                config.getBoolean("displays.items.vanilla-blocks", true));
    }

    /** Whether any mirror kind is on, so the whole feature can stay unloaded otherwise. */
    boolean any() {
        return enabled && (items || frames || blocks || text);
    }
}
