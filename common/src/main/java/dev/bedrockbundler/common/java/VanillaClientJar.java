package dev.bedrockbundler.common.java;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.bedrockbundler.common.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Consumer;

/**
 * Gets the vanilla Java client jar of one Minecraft version from Mojang's version manifest, once, and keeps it in a
 * cache folder. The jar is only read for the vanilla textures a custom pack references.
 */
public final class VanillaClientJar {

    public static final URI MANIFEST = URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");

    /** Downloads a URL; throws on any failure. */
    @FunctionalInterface
    public interface Http {
        byte[] get(URI uri) throws IOException;
    }

    private VanillaClientJar() {
    }

    /**
     * Opens the vanilla assets of {@code version}, downloading the client jar into {@code cache} when it is not there
     * yet. Never throws: when the jar cannot be had (offline, unknown version) it logs one line and returns
     * {@link VanillaAssets#NONE}.
     */
    public static VanillaAssets open(String version, Path cache, Consumer<String> log) {
        return open(version, cache, log, VanillaClientJar::download);
    }

    static VanillaAssets open(String version, Path cache, Consumer<String> log, Http http) {
        if (version == null || version.isBlank()) {
            log.accept("Vanilla textures are off: the Minecraft version is unknown");
            return VanillaAssets.NONE;
        }
        Path jar = cache.resolve("client-" + version + ".jar");
        try {
            if (!Files.isRegularFile(jar)) {
                fetch(version, jar, log, http);
            }
            return VanillaAssets.ofJar(version, jar);
        } catch (IOException | RuntimeException e) {
            log.accept("Vanilla textures are off: could not get the Minecraft " + version + " client jar ("
                    + e.getMessage() + "). Models that use vanilla textures miss those faces on Bedrock."
                    + " Put the client jar at " + jar + " to use it without a download.");
            return VanillaAssets.NONE;
        }
    }

    private static void fetch(String version, Path jar, Consumer<String> log, Http http) throws IOException {
        JsonObject manifest = Json.parse(http.get(MANIFEST)).getAsJsonObject();
        String versionUrl = null;
        for (JsonElement element : Json.array(manifest, "versions")) {
            JsonObject entry = element.getAsJsonObject();
            if (version.equals(Json.string(entry, "id", null))) {
                versionUrl = Json.string(entry, "url", null);
                break;
            }
        }
        if (versionUrl == null) {
            throw new IOException("version " + version + " is not in the version manifest");
        }
        JsonObject client = Json.object(Json.object(Json.parse(http.get(URI.create(versionUrl))).getAsJsonObject(), "downloads"), "client");
        if (client == null) {
            throw new IOException("version " + version + " has no client download");
        }
        String url = Json.string(client, "url", null);
        String sha1 = Json.string(client, "sha1", null);
        log.accept("Downloading the Minecraft " + version + " client jar for vanilla textures (once)");
        byte[] bytes = http.get(URI.create(url));
        if (sha1 != null && !sha1.equalsIgnoreCase(sha1(bytes))) {
            throw new IOException("the downloaded client jar does not match its sha1");
        }
        Files.createDirectories(jar.getParent());
        Path tmp = jar.resolveSibling(jar.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
    }

    private static byte[] download(URI uri) throws IOException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        try {
            HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " from " + uri.getHost());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
