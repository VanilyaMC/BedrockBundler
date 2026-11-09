package dev.bedrockbundler.common.java;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The vanilla parent models that custom packs build on. ItemsAdder's generated pack does not contain
 * vanilla assets, so the common parents are described here.
 */
final class BuiltinModels {

    static final Set<String> GENERATED = Set.of("builtin/generated", "item/generated");
    static final Set<String> HANDHELD = Set.of("item/handheld", "item/handheld_rod", "item/handheld_mace");

    private static final Map<String, JsonObject> MODELS = new HashMap<>();

    static {
        add("block/block", """
                {"display": {
                  "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
                  "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
                  "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
                  "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
                  "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.40, 0.40, 0.40]},
                  "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [0.40, 0.40, 0.40]}
                }}""");
        add("block/cube", """
                {"parent": "block/block", "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
                  "down":  {"texture": "#down", "cullface": "down"},
                  "up":    {"texture": "#up", "cullface": "up"},
                  "north": {"texture": "#north", "cullface": "north"},
                  "south": {"texture": "#south", "cullface": "south"},
                  "west":  {"texture": "#west", "cullface": "west"},
                  "east":  {"texture": "#east", "cullface": "east"}
                }}]}""");
        add("block/cube_all", """
                {"parent": "block/cube", "textures": {"particle": "#all", "down": "#all", "up": "#all",
                  "north": "#all", "east": "#all", "south": "#all", "west": "#all"}}""");
        add("block/leaves", "{\"parent\": \"block/cube_all\"}");
        add("block/cube_column", """
                {"parent": "block/cube", "textures": {"particle": "#side", "down": "#end", "up": "#end",
                  "north": "#side", "east": "#side", "south": "#side", "west": "#side"}}""");
        add("block/cube_column_horizontal", "{\"parent\": \"block/cube_column\"}");
        add("block/cube_bottom_top", """
                {"parent": "block/cube", "textures": {"particle": "#side", "down": "#bottom", "up": "#top",
                  "north": "#side", "east": "#side", "south": "#side", "west": "#side"}}""");
        add("block/cube_top", """
                {"parent": "block/cube", "textures": {"particle": "#side", "down": "#side", "up": "#top",
                  "north": "#side", "east": "#side", "south": "#side", "west": "#side"}}""");
        add("block/orientable_with_bottom", """
                {"parent": "block/cube", "textures": {"particle": "#front", "down": "#bottom", "up": "#top",
                  "north": "#front", "east": "#side", "south": "#side", "west": "#side"}}""");
        add("block/orientable", "{\"parent\": \"block/orientable_with_bottom\", \"textures\": {\"bottom\": \"#top\"}}");
        add("block/cube_directional", "{\"parent\": \"block/cube\"}");
        add("block/cross", """
                {"textures": {"particle": "#cross"}, "elements": [
                  {"from": [0.8, 0, 8], "to": [15.2, 16, 8], "shade": false,
                   "rotation": {"origin": [8, 8, 8], "axis": "y", "angle": 45, "rescale": true},
                   "faces": {"north": {"uv": [0, 0, 16, 16], "texture": "#cross"},
                             "south": {"uv": [0, 0, 16, 16], "texture": "#cross"}}},
                  {"from": [8, 0, 0.8], "to": [8, 16, 15.2], "shade": false,
                   "rotation": {"origin": [8, 8, 8], "axis": "y", "angle": 45, "rescale": true},
                   "faces": {"west": {"uv": [0, 0, 16, 16], "texture": "#cross"},
                             "east": {"uv": [0, 0, 16, 16], "texture": "#cross"}}}
                ]}""");
        add("block/tinted_cross", "{\"parent\": \"block/cross\"}");
        add("block/crop", """
                {"textures": {"particle": "#crop"}, "elements": [
                  {"from": [4, -1, 0], "to": [4, 15, 16], "shade": false, "faces": {
                    "west": {"uv": [0, 0, 16, 16], "texture": "#crop"}, "east": {"uv": [16, 0, 0, 16], "texture": "#crop"}}},
                  {"from": [12, -1, 0], "to": [12, 15, 16], "shade": false, "faces": {
                    "west": {"uv": [16, 0, 0, 16], "texture": "#crop"}, "east": {"uv": [0, 0, 16, 16], "texture": "#crop"}}},
                  {"from": [0, -1, 4], "to": [16, 15, 4], "shade": false, "faces": {
                    "north": {"uv": [0, 0, 16, 16], "texture": "#crop"}, "south": {"uv": [16, 0, 0, 16], "texture": "#crop"}}},
                  {"from": [0, -1, 12], "to": [16, 15, 12], "shade": false, "faces": {
                    "north": {"uv": [16, 0, 0, 16], "texture": "#crop"}, "south": {"uv": [0, 0, 16, 16], "texture": "#crop"}}}
                ]}""");
    }

    private BuiltinModels() {
    }

    private static void add(String name, String json) {
        MODELS.put(name, JsonParser.parseString(json).getAsJsonObject());
    }

    /** {@code name} is a vanilla model path without namespace, e.g. {@code block/cube_all}. */
    static JsonObject get(String name) {
        return MODELS.get(name);
    }
}
