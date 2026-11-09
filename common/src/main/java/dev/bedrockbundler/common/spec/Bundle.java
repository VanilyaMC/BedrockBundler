package dev.bedrockbundler.common.spec;

import dev.bedrockbundler.common.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The unit that moves from the Paper plugin to the Geyser extension: registry specs plus the Bedrock pack.
 *
 * <pre>
 * bundle/
 *   bundle.json            this record
 *   BedrockBundler.mcpack    Bedrock resource pack
 *   geyser_mappings.json   the same items as Geyser v2 JSON mappings (for servers without the extension)
 *   geyser_block_mappings.json  the same blocks as Geyser format 1 JSON block mappings
 * </pre>
 */
public record Bundle(int formatVersion, int converterVersion, String hash, List<ItemSpec> items, List<BlockSpec> blocks) {

    public static final int FORMAT_VERSION = 1;
    public static final String SPEC_FILE = "bundle.json";
    public static final String PACK_FILE = "BedrockBundler.mcpack";
    public static final String MAPPINGS_FILE = "geyser_mappings.json";
    public static final String BLOCK_MAPPINGS_FILE = "geyser_block_mappings.json";
    private static final List<String> FILES = List.of(PACK_FILE, MAPPINGS_FILE, BLOCK_MAPPINGS_FILE, SPEC_FILE);

    public static Bundle read(Path directory) throws IOException {
        Path spec = directory.resolve(SPEC_FILE);
        if (!Files.isRegularFile(spec)) {
            return null;
        }
        return Json.GSON.fromJson(Files.readString(spec, StandardCharsets.UTF_8), Bundle.class);
    }

    /** Writes the bundle atomically enough that a reader never sees a spec without its pack. */
    public void write(Path directory, byte[] mcpack, byte[] mappings) throws IOException {
        write(directory, mcpack, mappings, null);
    }

    /** Writes the bundle atomically enough that a reader never sees a spec without its pack. */
    public void write(Path directory, byte[] mcpack, byte[] mappings, byte[] blockMappings) throws IOException {
        Files.createDirectories(directory);
        Files.write(directory.resolve(PACK_FILE), mcpack);
        Files.write(directory.resolve(MAPPINGS_FILE), mappings);
        if (blockMappings != null) {
            Files.write(directory.resolve(BLOCK_MAPPINGS_FILE), blockMappings);
        } else {
            Files.deleteIfExists(directory.resolve(BLOCK_MAPPINGS_FILE));
        }
        Path tmp = directory.resolve(SPEC_FILE + ".tmp");
        Files.writeString(tmp, Json.PRETTY.toJson(this), StandardCharsets.UTF_8);
        Files.move(tmp, directory.resolve(SPEC_FILE), StandardCopyOption.REPLACE_EXISTING);
    }

    public static byte[] zip(Path directory) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String name : FILES) {
                Path file = directory.resolve(name);
                if (Files.isRegularFile(file)) {
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write(Files.readAllBytes(file));
                    zip.closeEntry();
                }
            }
        }
        return out.toByteArray();
    }

    public static void unzip(InputStream in, Path directory) throws IOException {
        Files.createDirectories(directory);
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                // Only the known flat files; never trust paths from the network.
                if (!FILES.contains(name)) {
                    continue;
                }
                Path target = directory.resolve(name + ".tmp");
                Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        // Move the spec last so a half-written bundle is never picked up.
        for (String name : FILES) {
            Path tmp = directory.resolve(name + ".tmp");
            if (Files.exists(tmp)) {
                Files.move(tmp, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Copies a bundle directory (used by folder sync). */
    public static void copy(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        for (String name : FILES) {
            Path source = from.resolve(name);
            if (Files.isRegularFile(source)) {
                Files.copy(source, to.resolve(name + ".tmp"), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        for (String name : FILES) {
            Path tmp = to.resolve(name + ".tmp");
            if (Files.exists(tmp)) {
                Files.move(tmp, to.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
