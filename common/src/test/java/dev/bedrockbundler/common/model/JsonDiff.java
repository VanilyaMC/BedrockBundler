package dev.bedrockbundler.common.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Semantic JSON comparison with a float tolerance; named arrays may be compared as multisets. */
final class JsonDiff {

    private final double tolerance;
    private final Set<String> unorderedKeys;
    final List<String> differences = new ArrayList<>();

    JsonDiff(double tolerance, Set<String> unorderedKeys) {
        this.tolerance = tolerance;
        this.unorderedKeys = unorderedKeys;
    }

    static List<String> diff(JsonElement expected, JsonElement actual, double tolerance, Set<String> unorderedKeys) {
        JsonDiff diff = new JsonDiff(tolerance, unorderedKeys);
        diff.compare("$", "", expected, actual);
        return diff.differences;
    }

    private void compare(String path, String key, JsonElement expected, JsonElement actual) {
        if (differences.size() > 20) {
            return;
        }
        if (expected == null || actual == null) {
            differences.add(path + ": " + (expected == null ? "missing in expected" : "missing in actual"));
            return;
        }
        if (expected.isJsonObject() && actual.isJsonObject()) {
            JsonObject e = expected.getAsJsonObject();
            JsonObject a = actual.getAsJsonObject();
            Set<String> keys = new TreeSet<>(e.keySet());
            keys.addAll(a.keySet());
            for (String k : keys) {
                compare(path + "." + k, k, e.get(k), a.get(k));
            }
        } else if (expected.isJsonArray() && actual.isJsonArray()) {
            JsonArray e = expected.getAsJsonArray();
            JsonArray a = actual.getAsJsonArray();
            if (e.size() != a.size()) {
                differences.add(path + ": array size " + e.size() + " vs " + a.size());
                return;
            }
            if (unorderedKeys.contains(key)) {
                List<String> es = canonicalList(e);
                List<String> as = canonicalList(a);
                if (!es.equals(as)) {
                    differences.add(path + ": array contents differ (unordered)");
                } else if (key.equals("part_visibility") && !e.isEmpty()
                        && !canonical(e.get(0)).equals(canonical(a.get(0)))) {
                    differences.add(path + ": first part_visibility entry differs");
                }
                return;
            }
            for (int i = 0; i < e.size(); i++) {
                compare(path + "[" + i + "]", key, e.get(i), a.get(i));
            }
        } else if (expected.isJsonPrimitive() && actual.isJsonPrimitive()) {
            JsonPrimitive e = expected.getAsJsonPrimitive();
            JsonPrimitive a = actual.getAsJsonPrimitive();
            if (e.isNumber() && a.isNumber()) {
                if (Math.abs(e.getAsDouble() - a.getAsDouble()) > tolerance) {
                    differences.add(path + ": " + e.getAsDouble() + " vs " + a.getAsDouble());
                }
            } else if (!e.getAsString().equals(a.getAsString()) || e.isNumber() != a.isNumber()
                    || e.isBoolean() != a.isBoolean()) {
                differences.add(path + ": " + e + " vs " + a);
            }
        } else if (!expected.equals(actual)) {
            differences.add(path + ": type " + expected + " vs " + actual);
        }
    }

    private List<String> canonicalList(JsonArray array) {
        List<String> list = new ArrayList<>();
        array.forEach(el -> list.add(canonical(el)));
        list.sort(String::compareTo);
        return list;
    }

    /** Key order and number formatting independent text of a JSON value. */
    static String canonical(JsonElement element) {
        StringBuilder out = new StringBuilder();
        canonical(element, out);
        return out.toString();
    }

    private static void canonical(JsonElement element, StringBuilder out) {
        if (element.isJsonObject()) {
            out.append('{');
            for (String key : new TreeSet<>(element.getAsJsonObject().keySet())) {
                out.append(key).append(':');
                canonical(element.getAsJsonObject().get(key), out);
                out.append(',');
            }
            out.append('}');
        } else if (element.isJsonArray()) {
            out.append('[');
            element.getAsJsonArray().forEach(e -> {
                canonical(e, out);
                out.append(',');
            });
            out.append(']');
        } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            out.append(String.format(Locale.ROOT, "%.4f", element.getAsDouble()));
        } else {
            out.append(element);
        }
    }
}
