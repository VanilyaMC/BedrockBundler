package dev.bedrockbundler.paper.deploy;

import dev.bedrockbundler.common.spec.Bundle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Geyser-Spigot runs on this server: write the bundle straight into the extension's data folder and keep the
 * bundled extension jar installed in Geyser's {@code extensions/} folder.
 */
public final class LocalGeyserDeployer implements Deployer {

    /** Must match {@code id} in the extension's extension.yml. */
    public static final String EXTENSION_ID = "bedrockbundler";
    public static final String EXTENSION_JAR = "BedrockBundlerExtension.jar";
    /** The extension jar of BedrockAdder, the former name; Geyser would load both. */
    static final String LEGACY_EXTENSION_JAR = "BedrockAdderExtension.jar";

    private static final Pattern CUSTOM_CONTENT_OFF = Pattern.compile(
            "^\\s*(enable-custom-content|add-non-bedrock-items)\\s*:\\s*false", Pattern.MULTILINE);

    private final Path geyserFolder;
    private final Logger logger;

    public LocalGeyserDeployer(Path geyserFolder, Logger logger) {
        this.geyserFolder = geyserFolder;
        this.logger = logger;
    }

    @Override
    public String describe() {
        return "local Geyser at " + geyserFolder;
    }

    public Path bundleFolder() {
        return geyserFolder.resolve("extensions").resolve(EXTENSION_ID).resolve("bundle");
    }

    @Override
    public void deploy(Path bundleDirectory) throws IOException {
        Bundle.copy(bundleDirectory, bundleFolder());
    }

    /**
     * Installs or updates the extension jar shipped inside this plugin.
     *
     * @return true when the jar changed, which needs a restart to take effect
     */
    public boolean installExtension(InputStream bundledJar) throws IOException {
        byte[] jar = bundledJar.readAllBytes();
        Path extensions = geyserFolder.resolve("extensions");
        Files.createDirectories(extensions);
        Path target = extensions.resolve(EXTENSION_JAR);
        boolean legacyRemoved = Files.deleteIfExists(extensions.resolve(LEGACY_EXTENSION_JAR));
        if (legacyRemoved) {
            logger.info("Removed " + LEGACY_EXTENSION_JAR + " (the extension's former name) from " + extensions);
        }
        if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), jar)) {
            return legacyRemoved;
        }
        Files.write(target, jar);
        return true;
    }

    /** Geyser ignores all custom items and blocks unless custom content is enabled in its config. */
    public void checkGeyserConfig() {
        Path config = geyserFolder.resolve("config.yml");
        try {
            if (Files.isRegularFile(config)
                    && CUSTOM_CONTENT_OFF.matcher(Files.readString(config, StandardCharsets.UTF_8)).find()) {
                logger.warning("Geyser has custom content disabled. Set 'enable-custom-content: true' in "
                        + config + " and restart, or Bedrock players will not see custom content.");
            }
        } catch (IOException e) {
            logger.warning("Could not read Geyser config: " + e.getMessage());
        }
    }
}
