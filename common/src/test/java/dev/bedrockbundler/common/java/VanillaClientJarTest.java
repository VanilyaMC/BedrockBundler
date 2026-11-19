package dev.bedrockbundler.common.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class VanillaClientJarTest {

    private static final String STONE = "assets/minecraft/textures/block/stone.png";

    private static byte[] clientJar() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(STONE));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static VanillaClientJar.Http mojang(byte[] jar, String sha1, List<URI> requests) {
        return uri -> {
            requests.add(uri);
            String body = switch (uri.toString()) {
                case "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json" ->
                        "{\"versions\": [{\"id\": \"1.21.10\", \"url\": \"https://example.invalid/old.json\"},"
                                + " {\"id\": \"1.21.11\", \"url\": \"https://example.invalid/1.21.11.json\"}]}";
                case "https://example.invalid/1.21.11.json" ->
                        "{\"downloads\": {\"client\": {\"sha1\": \"" + sha1 + "\", \"url\": \"https://example.invalid/client.jar\"}}}";
                case "https://example.invalid/client.jar" -> null;
                default -> throw new IOException("unexpected " + uri);
            };
            return body == null ? jar : body.getBytes(StandardCharsets.UTF_8);
        };
    }

    @Test
    void downloadsOnceAndReadsTextures(@TempDir Path cache) throws Exception {
        byte[] jar = clientJar();
        String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(jar));
        List<URI> requests = new ArrayList<>();
        List<String> log = new ArrayList<>();
        try (VanillaAssets assets = VanillaClientJar.open("1.21.11", cache, log::add, mojang(jar, sha1, requests))) {
            assertEquals("1.21.11", assets.version());
            assertArrayEquals(new byte[]{1, 2, 3}, assets.bytes(STONE));
            assertNull(assets.bytes("assets/minecraft/textures/block/missing.png"));
        }
        assertEquals(3, requests.size());
        assertTrue(Files.isRegularFile(cache.resolve("client-1.21.11.jar")));

        requests.clear();
        try (VanillaAssets assets = VanillaClientJar.open("1.21.11", cache, log::add, mojang(jar, sha1, requests))) {
            assertNotNull(assets.bytes(STONE));
        }
        assertTrue(requests.isEmpty(), "the cached jar is used without a download");
    }

    @Test
    void wrongChecksumIsNotCached(@TempDir Path cache) throws Exception {
        List<String> log = new ArrayList<>();
        VanillaAssets assets = VanillaClientJar.open("1.21.11", cache, log::add, mojang(clientJar(), "00", new ArrayList<>()));
        assertSame(VanillaAssets.NONE, assets);
        assertFalse(Files.exists(cache.resolve("client-1.21.11.jar")));
        assertTrue(log.getLast().contains("sha1"), log::toString);
    }

    @Test
    void offlineFailsSoftWithOneLine(@TempDir Path cache) {
        List<String> log = new ArrayList<>();
        VanillaAssets assets = VanillaClientJar.open("1.21.11", cache, log::add, uri -> {
            throw new IOException("no network");
        });
        assertSame(VanillaAssets.NONE, assets);
        assertEquals(1, log.size());
        assertTrue(log.getFirst().startsWith("Vanilla textures are off"), log::toString);
    }

    @Test
    void unknownVersionFailsSoft(@TempDir Path cache) {
        List<String> log = new ArrayList<>();
        VanillaAssets assets = VanillaClientJar.open("9.9", cache, log::add, mojang(new byte[0], "", new ArrayList<>()));
        assertSame(VanillaAssets.NONE, assets);
        assertTrue(log.getLast().contains("not in the version manifest"), log::toString);
    }
}
