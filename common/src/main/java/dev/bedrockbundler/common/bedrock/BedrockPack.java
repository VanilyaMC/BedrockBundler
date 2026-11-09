package dev.bedrockbundler.common.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.bedrockbundler.common.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Accumulates the files of a Bedrock resource pack and writes it as an {@code .mcpack}. */
public final class BedrockPack {

    /** Lowest Bedrock version whose custom item/block features the generated pack relies on. */
    private static final int[] MIN_ENGINE_VERSION = {1, 21, 0};

    /** Path length the Bedrock client no longer loads (see {@link #shortPath}). */
    static final int MAX_PATH = 80;
    private static final String[] DEFINITION_FOLDERS = {
            "attachables", "animations", "animation_controllers", "render_controllers", "models/entity", "models/blocks"};

    private final Map<String, byte[]> files = new TreeMap<>();
    private final JsonObject itemTextures = new JsonObject();
    private final JsonObject terrainTextures = new JsonObject();
    private final Map<String, String> lang = new TreeMap<>();

    public void file(String path, byte[] bytes) {
        files.put(path, bytes);
    }

    public void json(String path, JsonElement json) {
        files.put(path, Json.bytes(json));
    }

    public boolean has(String path) {
        return files.containsKey(path);
    }

    /** Registers {@code key} in {@code item_texture.json}; {@code path} is relative to the pack root, without extension. */
    public void itemTexture(String key, String path) {
        itemTextures.add(key, textureEntry(path));
    }

    /** Registers {@code key} in {@code terrain_texture.json}. */
    public void terrainTexture(String key, String path) {
        terrainTextures.add(key, textureEntry(path));
    }

    public boolean hasTerrainTexture(String key) {
        return terrainTextures.has(key);
    }

    private static JsonObject textureEntry(String path) {
        JsonObject entry = new JsonObject();
        entry.addProperty("textures", path);
        return entry;
    }

    public void lang(String key, String value) {
        lang.put(key, value);
    }

    /**
     * Builds the {@code .mcpack}.
     *
     * @param name    pack name; also seeds the pack UUIDs so they stay stable across conversions
     * @param version pack version; must change whenever the content changes so clients re-download it
     */
    public byte[] build(String name, String description, int[] version) {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("format_version", 2);
        JsonObject header = new JsonObject();
        header.addProperty("name", name);
        header.addProperty("description", description);
        header.addProperty("uuid", uuid(name + ":header"));
        header.add("version", ints(version));
        header.add("min_engine_version", ints(MIN_ENGINE_VERSION));
        manifest.add("header", header);
        JsonObject module = new JsonObject();
        module.addProperty("type", "resources");
        module.addProperty("uuid", uuid(name + ":resources"));
        module.add("version", ints(version));
        JsonArray modules = new JsonArray();
        modules.add(module);
        manifest.add("modules", modules);

        Map<String, byte[]> out = new TreeMap<>();
        files.forEach((path, bytes) -> out.put(shortPath(path), bytes));
        out.put("manifest.json", Json.bytes(manifest));
        // The BedrockBundler logo, shown next to the pack in the Bedrock pack list.
        try (java.io.InputStream icon = BedrockPack.class.getResourceAsStream("/pack_icon.png")) {
            if (icon != null) {
                out.putIfAbsent("pack_icon.png", icon.readAllBytes());
            }
        } catch (java.io.IOException e) {
            // no icon then
        }
        out.put("textures/item_texture.json", Json.bytes(atlas(name, "atlas.items", itemTextures)));
        out.put("textures/terrain_texture.json", Json.bytes(atlas(name, "atlas.terrain", terrainTextures)));

        StringBuilder langFile = new StringBuilder();
        lang.forEach((key, value) -> langFile.append(key).append('=').append(value.replace('\n', ' ')).append('\n'));
        out.put("texts/en_US.lang", langFile.toString().getBytes(StandardCharsets.UTF_8));
        JsonArray languages = new JsonArray();
        languages.add("en_US");
        out.put("texts/languages.json", Json.bytes(languages));

        return zip(out);
    }

    /**
     * The Bedrock client skips pack files whose path has 80 or more characters (an 81 character render controller
     * was never loaded, one of 80 was). Definition files are found by the identifier inside them, not by their
     * path, so long ones are renamed to a hash. Textures are referenced by path and keep it.
     */
    static String shortPath(String path) {
        if (path.length() < MAX_PATH) {
            return path;
        }
        for (String folder : DEFINITION_FOLDERS) {
            if (path.startsWith(folder + "/") && path.endsWith(".json")) {
                try {
                    byte[] digest = java.security.MessageDigest.getInstance("SHA-1").digest(path.getBytes(StandardCharsets.UTF_8));
                    return folder + "/" + HexFormat.of().formatHex(digest, 0, 10) + ".json";
                } catch (java.security.NoSuchAlgorithmException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return path;
    }

    private static JsonObject atlas(String packName, String atlasName, JsonObject data) {
        JsonObject atlas = new JsonObject();
        atlas.addProperty("resource_pack_name", packName);
        atlas.addProperty("texture_name", atlasName);
        if (atlasName.equals("atlas.terrain")) {
            atlas.addProperty("padding", 8);
            atlas.addProperty("num_mip_levels", 4);
        }
        atlas.add("texture_data", data);
        return atlas;
    }

    private static String uuid(String seed) {
        return UUID.nameUUIDFromBytes(("BedrockBundler:" + seed).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static JsonArray ints(int[] values) {
        JsonArray array = new JsonArray();
        for (int value : values) {
            array.add(value);
        }
        return array;
    }

    private static byte[] zip(Map<String, byte[]> files) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                    ZipEntry zipEntry = new ZipEntry(entry.getKey());
                    // Fixed timestamps keep the archive byte-identical for identical content.
                    zipEntry.setTime(0);
                    zip.putNextEntry(zipEntry);
                    zip.write(entry.getValue());
                    zip.closeEntry();
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
