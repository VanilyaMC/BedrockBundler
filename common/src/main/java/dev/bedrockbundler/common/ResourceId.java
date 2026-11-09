package dev.bedrockbundler.common;

import java.util.Locale;

/** A Java Edition resource location ({@code namespace:path}). */
public record ResourceId(String namespace, String path) {

    public static ResourceId parse(String value) {
        int colon = value.indexOf(':');
        if (colon < 0) {
            return new ResourceId("minecraft", value);
        }
        return new ResourceId(value.substring(0, colon), value.substring(colon + 1));
    }

    /** Resolves {@code value} the way Java resolves model/texture references. */
    public String asset(String type, String extension) {
        return "assets/" + namespace + "/" + type + "/" + path + extension;
    }

    /**
     * A name that is valid as a Bedrock identifier path, texture key or file name. It always starts with a
     * letter: ItemsAdder's own namespace is {@code _iainternal}, and Bedrock clients are stricter about
     * identifiers than Java.
     */
    public String flat() {
        String flat = sanitize(namespace + "_" + path.replace('/', '_')).replaceFirst("^[^a-z]+", "");
        return flat.isEmpty() ? "x" : flat;
    }

    public static String sanitize(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (char c : value.toLowerCase(Locale.ROOT).toCharArray()) {
            builder.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' ? c : '_');
        }
        return builder.toString();
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
