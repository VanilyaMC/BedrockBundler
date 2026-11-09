package dev.bedrockadder.common.java;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.bedrockadder.common.Json;
import dev.bedrockadder.common.ResourceId;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Read-only view of a Java Edition resource pack held in memory.
 *
 * <p>Two things ItemsAdder does to its generated pack are undone here so the rest of the converter can
 * look files up by their plain path:
 * <ul>
 *   <li><b>Overlays</b>: version-specific files live under {@code ia_overlay_*} directories listed in
 *       {@code pack.mcmeta}. The overlays for the newest pack format are applied on top of the base.</li>
 *   <li><b>Short texture references</b>: models refer to sprites like {@code ia:18}, which atlas files map
 *       to real textures with {@code "single"} sources.</li>
 * </ul>
 */
public final class JavaPack {

    private final Map<String, byte[]> files;
    private final Map<String, JsonObject> jsonCache = new HashMap<>();
    private final Map<String, String> spriteAliases = new HashMap<>();

    public JavaPack(Map<String, byte[]> rawFiles) {
        Map<String, byte[]> normalized = new HashMap<>(rawFiles.size());
        rawFiles.forEach((path, bytes) -> normalized.put(normalize(path), bytes));
        List<String> overlays = activeOverlays(normalized);
        this.files = new HashMap<>(normalized);
        for (String overlay : overlays) {
            String prefix = overlay + "/";
            normalized.forEach((path, bytes) -> {
                if (path.startsWith(prefix)) {
                    files.put(path.substring(prefix.length()), bytes);
                }
            });
        }
        // Aliases from the effective atlases win; atlases of other overlays only fill gaps.
        files.forEach(this::collectSpriteAliases);
        normalized.forEach((path, bytes) -> {
            int assets = path.indexOf("/assets/");
            if (assets > 0) {
                collectSpriteAliases(path.substring(assets + 1), bytes);
            }
        });
    }

    /** Reads a zip from a stream (local headers). Use {@link #readZip(Path)} for files on disk. */
    public static Map<String, byte[]> readZip(InputStream in) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                buffer.reset();
                zip.transferTo(buffer);
                files.put(normalize(entry.getName()), buffer.toByteArray());
            }
        }
        return files;
    }

    /**
     * Reads a zip through its central directory. ItemsAdder's zip protection scrambles the local file
     * headers, which breaks stream readers but not this.
     */
    public static Map<String, byte[]> readZip(Path path) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    files.put(normalize(entry.getName()), in.readAllBytes());
                }
            }
        }
        return files;
    }

    private static String normalize(String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    // ---------------------------------------------------------------- overlays

    private record Overlay(String directory, int min, int max) {
    }

    /** Overlays that apply to the newest pack format any overlay targets, in pack.mcmeta order. */
    private static List<String> activeOverlays(Map<String, byte[]> files) {
        byte[] mcmeta = files.get("pack.mcmeta");
        if (mcmeta == null) {
            return List.of();
        }
        JsonObject overlaysJson;
        try {
            overlaysJson = Json.object(Json.parse(mcmeta).getAsJsonObject(), "overlays");
        } catch (RuntimeException e) {
            return List.of();
        }
        JsonArray entries = Json.array(overlaysJson, "entries");
        if (entries == null) {
            return List.of();
        }
        List<Overlay> overlays = new ArrayList<>();
        for (JsonElement element : entries) {
            JsonObject entry = element.getAsJsonObject();
            String directory = Json.string(entry, "directory", null);
            int[] range = formatRange(entry);
            if (directory != null && range != null) {
                overlays.add(new Overlay(directory, range[0], range[1]));
            }
        }
        int target = overlays.stream().mapToInt(Overlay::min).max().orElse(0);
        List<String> active = new ArrayList<>();
        for (Overlay overlay : overlays) {
            if (overlay.min() <= target && target <= overlay.max()) {
                active.add(overlay.directory());
            }
        }
        return active;
    }

    /** Supports {@code min_format/max_format}, {@code formats: [a, b]}, {@code formats: n} and the object form. */
    private static int[] formatRange(JsonObject entry) {
        if (entry.has("min_format") && entry.has("max_format")) {
            return new int[]{majorFormat(entry.get("min_format")), majorFormat(entry.get("max_format"))};
        }
        JsonElement formats = entry.get("formats");
        if (formats == null) {
            return null;
        }
        if (formats.isJsonPrimitive()) {
            int value = formats.getAsInt();
            return new int[]{value, value};
        }
        if (formats.isJsonArray() && formats.getAsJsonArray().size() == 2) {
            return new int[]{formats.getAsJsonArray().get(0).getAsInt(), formats.getAsJsonArray().get(1).getAsInt()};
        }
        if (formats.isJsonObject()) {
            JsonObject object = formats.getAsJsonObject();
            return new int[]{(int) Json.number(object, "min_inclusive", 0), (int) Json.number(object, "max_inclusive", Integer.MAX_VALUE)};
        }
        return null;
    }

    /** Pack formats can be {@code 88} or {@code [88, 1]} (major, minor). */
    private static int majorFormat(JsonElement element) {
        return element.isJsonArray() ? element.getAsJsonArray().get(0).getAsInt() : element.getAsInt();
    }

    // ---------------------------------------------------------------- atlases

    private void collectSpriteAliases(String path, byte[] bytes) {
        if (!path.startsWith("assets/") || !path.contains("/atlases/") || !path.endsWith(".json")) {
            return;
        }
        JsonArray sources;
        try {
            sources = Json.array(Json.parse(bytes).getAsJsonObject(), "sources");
        } catch (RuntimeException e) {
            return;
        }
        if (sources == null) {
            return;
        }
        for (JsonElement element : sources) {
            JsonObject atlasSource = element.isJsonObject() ? element.getAsJsonObject() : null;
            if (atlasSource == null || !Json.string(atlasSource, "type", "").replace("minecraft:", "").equals("single")) {
                continue;
            }
            String resource = Json.string(atlasSource, "resource", null);
            String sprite = Json.string(atlasSource, "sprite", null);
            if (resource != null && sprite != null) {
                String from = ResourceId.parse(sprite).toString();
                String to = ResourceId.parse(resource).toString();
                if (!from.equals(to)) {
                    spriteAliases.putIfAbsent(from, to);
                }
            }
        }
    }

    /** Maps an atlas sprite name (e.g. {@code ia:18}) to the texture it stands for; other ids pass through. */
    public String resolveSprite(String textureId) {
        String id = ResourceId.parse(textureId).toString();
        for (int i = 0; i < 8 && spriteAliases.containsKey(id); i++) {
            id = spriteAliases.get(id);
        }
        return id;
    }

    // ---------------------------------------------------------------- access

    public byte[] bytes(String path) {
        return files.get(path);
    }

    /** All file paths of the effective pack (overlays applied). */
    public java.util.Set<String> paths() {
        return java.util.Collections.unmodifiableSet(files.keySet());
    }

    public boolean has(String path) {
        return files.containsKey(path);
    }

    /** Parses a JSON file once; returns null when it is missing or not an object. */
    public JsonObject json(String path) {
        if (jsonCache.containsKey(path)) {
            return jsonCache.get(path);
        }
        JsonObject result = null;
        byte[] bytes = files.get(path);
        if (bytes != null) {
            try {
                JsonElement element = Json.parse(bytes);
                result = element.isJsonObject() ? element.getAsJsonObject() : null;
            } catch (RuntimeException ignored) {
                // Broken JSON in a pack is reported by the caller as a missing file.
            }
        }
        jsonCache.put(path, result);
        return result;
    }

    /** Stable hash of the pack contents, used to skip unchanged conversions. */
    public static String hash(Map<String, byte[]> files, String extra) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, byte[]> entry : new TreeMap<>(files).entrySet()) {
                digest.update(entry.getKey().getBytes());
                digest.update(entry.getValue());
            }
            digest.update(extra.getBytes());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
