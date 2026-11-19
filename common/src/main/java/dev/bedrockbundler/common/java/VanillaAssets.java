package dev.bedrockbundler.common.java;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Files of the vanilla Java client ({@code assets/minecraft/...}) that a custom pack references without carrying
 * them, such as {@code minecraft:block/oak_planks} on a furniture model. Read from the client jar on demand, so only
 * the referenced files are copied into the Bedrock pack.
 */
public interface VanillaAssets extends AutoCloseable {

    /** No vanilla files: references to them stay unresolved. */
    VanillaAssets NONE = new VanillaAssets() {
        @Override
        public byte[] bytes(String path) {
            return null;
        }

        @Override
        public String version() {
            return "none";
        }
    };

    /** The file at a pack path such as {@code assets/minecraft/textures/block/stone.png}, or null. */
    byte[] bytes(String path);

    /** The Minecraft version the files come from; part of the conversion hash. */
    String version();

    @Override
    default void close() {
    }

    /** Files held in memory, keyed by pack path (for tests and callers that already have them). */
    static VanillaAssets of(String version, Map<String, byte[]> files) {
        Map<String, byte[]> copy = Map.copyOf(files);
        return new VanillaAssets() {
            @Override
            public byte[] bytes(String path) {
                return copy.get(path);
            }

            @Override
            public String version() {
                return version;
            }
        };
    }

    /** A vanilla client jar on disk. Close it after the conversion. */
    static VanillaAssets ofJar(String version, Path clientJar) throws IOException {
        ZipFile zip = new ZipFile(clientJar.toFile());
        return new VanillaAssets() {
            @Override
            public synchronized byte[] bytes(String path) {
                ZipEntry entry = zip.getEntry(path);
                if (entry == null || entry.isDirectory()) {
                    return null;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    return in.readAllBytes();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public String version() {
                return version;
            }

            @Override
            public synchronized void close() {
                try {
                    zip.close();
                } catch (IOException ignored) {
                    // Nothing was written; a failed close loses nothing.
                }
            }
        };
    }
}
