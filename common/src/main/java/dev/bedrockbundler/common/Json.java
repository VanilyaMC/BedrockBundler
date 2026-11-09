package dev.bedrockbundler.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;

public final class Json {

    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    public static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private Json() {
    }

    public static JsonElement parse(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        // Some tools write a UTF-8 BOM, which Gson rejects.
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        return JsonParser.parseString(text);
    }

    public static byte[] bytes(JsonElement element) {
        return PRETTY.toJson(element).getBytes(StandardCharsets.UTF_8);
    }

    public static String string(JsonObject object, String key, String fallback) {
        JsonElement element = object == null ? null : object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
    }

    public static float number(JsonObject object, String key, float fallback) {
        JsonElement element = object == null ? null : object.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsFloat() : fallback;
    }

    public static JsonObject object(JsonObject object, String key) {
        JsonElement element = object == null ? null : object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    public static JsonArray array(JsonObject object, String key) {
        JsonElement element = object == null ? null : object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    public static float[] floats(JsonArray array, int size, float fallback) {
        float[] result = new float[size];
        for (int i = 0; i < size; i++) {
            result[i] = array != null && i < array.size() ? array.get(i).getAsFloat() : fallback;
        }
        return result;
    }

    public static JsonArray array(float... values) {
        JsonArray array = new JsonArray();
        for (float value : values) {
            array.add(round(value));
        }
        return array;
    }

    /** Avoids noise like 0.30000001 in generated files. */
    public static float round(float value) {
        return Math.round(value * 10000f) / 10000f;
    }
}
