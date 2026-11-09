package dev.bedrockbundler.geyser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/** {@code extensions/bedrockbundler/config.properties}. */
record ExtensionConfig(Source source, String folder, String url, String token, int timeoutSeconds) {

    enum Source {
        /** The BedrockBundler Paper plugin on this server writes the bundle into ./bundle. */
        LOCAL,
        /** Copy the bundle from a shared folder the Paper plugin writes to (sync.type FOLDER). */
        FOLDER,
        /** Download the bundle from the Paper plugin's HTTP endpoint (sync.type HTTP). */
        HTTP
    }

    private static final String DEFAULT = """
            # BedrockBundler Geyser extension
            #
            # Where to get the converted bundle (Nexo or ItemsAdder content) when Geyser starts:
            #   LOCAL  - the BedrockBundler plugin runs on this same server and writes it here (default)
            #   FOLDER - copy it from 'folder' (BedrockBundler plugin config: sync.type FOLDER)
            #   HTTP   - download it from 'url' (BedrockBundler plugin config: sync.type HTTP)
            source=LOCAL

            # FOLDER: absolute path of the shared folder
            folder=

            # HTTP: http://<backend-address>:<sync.http.port>/bedrockbundler/bundle
            url=
            # HTTP: the value of sync.http.token in the plugin's config.yml
            token=
            timeout-seconds=30
            """;

    static ExtensionConfig load(Path dataFolder) throws IOException {
        Path file = dataFolder.resolve("config.properties");
        // BedrockBundler was called BedrockAdder (extension id bedrockadder): keep its sync settings.
        Path legacy = dataFolder.resolveSibling("bedrockadder").resolve("config.properties");
        if (!Files.exists(file)) {
            Files.createDirectories(dataFolder);
            if (Files.isRegularFile(legacy)) {
                Files.copy(legacy, file);
            } else {
                Files.writeString(file, DEFAULT, StandardCharsets.UTF_8);
            }
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        Source source;
        try {
            source = Source.valueOf(properties.getProperty("source", "LOCAL").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            source = Source.LOCAL;
        }
        int timeout;
        try {
            timeout = Integer.parseInt(properties.getProperty("timeout-seconds", "30").trim());
        } catch (NumberFormatException e) {
            timeout = 30;
        }
        return new ExtensionConfig(source,
                properties.getProperty("folder", "").trim(),
                properties.getProperty("url", "").trim(),
                properties.getProperty("token", "").trim(),
                timeout);
    }
}
