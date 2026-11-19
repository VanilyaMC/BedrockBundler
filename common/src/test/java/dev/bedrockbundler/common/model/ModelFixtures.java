package dev.bedrockbundler.common.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bedrockbundler.common.Json;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Hand made {@code .bbmodel} files for the tests. */
final class ModelFixtures {

    private ModelFixtures() {
    }

    static final String[] FACES = {"north", "east", "south", "west", "up", "down"};

    /** A 16x16 PNG: every pixel has the given alpha (so 255 is opaque). */
    static byte[] png(int alpha, int rgb) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, (alpha << 24) | rgb);
            }
        }
        return encode(image);
    }

    /** A 16x16 PNG whose left half is opaque and whose right half has the given alpha. */
    static byte[] halfPng(int alphaRight) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, ((x < 8 ? 255 : alphaRight) << 24) | 0x3366CC);
            }
        }
        return encode(image);
    }

    private static byte[] encode(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonObject texture(String name, byte[] png) {
        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
        return t;
    }

    static JsonObject cube(String uuid, double[] from, double[] to, int texture) {
        JsonObject cube = new JsonObject();
        cube.addProperty("uuid", uuid);
        cube.addProperty("type", "cube");
        cube.add("from", array(from));
        cube.add("to", array(to));
        JsonObject faces = new JsonObject();
        for (String face : FACES) {
            JsonObject f = new JsonObject();
            f.add("uv", array(new double[]{0, 0, 4, 4}));
            f.addProperty("texture", texture);
            faces.add(face, f);
        }
        cube.add("faces", faces);
        return cube;
    }

    static JsonObject group(String uuid, String name, double[] origin, Object... children) {
        JsonObject g = new JsonObject();
        g.addProperty("uuid", uuid);
        g.addProperty("name", name);
        g.add("origin", array(origin));
        JsonArray list = new JsonArray();
        for (Object child : children) {
            if (child instanceof JsonObject o) {
                list.add(o);
            } else {
                list.add((String) child);
            }
        }
        g.add("children", list);
        return g;
    }

    static JsonObject keyframe(String channel, double time, String interpolation, String x, String y, String z) {
        JsonObject kf = new JsonObject();
        kf.addProperty("channel", channel);
        kf.addProperty("time", time);
        kf.addProperty("interpolation", interpolation);
        JsonObject dp = new JsonObject();
        dp.addProperty("x", x);
        dp.addProperty("y", y);
        dp.addProperty("z", z);
        JsonArray points = new JsonArray();
        points.add(dp);
        kf.add("data_points", points);
        return kf;
    }

    static JsonObject animation(String name, String loop, boolean override, double length, String boneUuid,
                                String boneName, JsonObject... keyframes) {
        JsonObject a = new JsonObject();
        a.addProperty("uuid", "anim-" + name);
        a.addProperty("name", name);
        a.addProperty("loop", loop);
        a.addProperty("override", override);
        a.addProperty("length", length);
        JsonObject animator = new JsonObject();
        animator.addProperty("name", boneName);
        animator.addProperty("type", "bone");
        JsonArray list = new JsonArray();
        for (JsonObject kf : keyframes) {
            list.add(kf);
        }
        animator.add("keyframes", list);
        JsonObject animators = new JsonObject();
        animators.add(boneUuid, animator);
        a.add("animators", animators);
        return a;
    }

    static JsonObject model(JsonObject[] textures, JsonObject[] elements, JsonObject[] groups, JsonObject... animations) {
        JsonObject m = new JsonObject();
        JsonObject resolution = new JsonObject();
        resolution.addProperty("width", 16);
        resolution.addProperty("height", 16);
        m.add("resolution", resolution);
        m.add("textures", list(textures));
        m.add("elements", list(elements));
        m.add("outliner", list(groups));
        m.add("animations", list(animations));
        return m;
    }

    static byte[] bytes(JsonObject model) {
        return Json.GSON.toJson(model).getBytes(StandardCharsets.UTF_8);
    }

    private static JsonArray list(JsonObject[] objects) {
        JsonArray array = new JsonArray();
        for (JsonObject o : objects) {
            array.add(o);
        }
        return array;
    }

    static JsonArray array(double[] values) {
        JsonArray array = new JsonArray();
        for (double v : values) {
            array.add(v);
        }
        return array;
    }

    /**
     * The standard fixture: root group with a body (one cube, rotated, asymmetric so mirroring shows) and a head
     * bone named h_head, a "walk cycle" loop with catmullrom rotation and a Molang position, a held "wave" and an
     * "override" animation, one opaque texture.
     */
    static JsonObject standard() {
        JsonObject cube = cube("cube-1", new double[]{1, 0, 2}, new double[]{5, 8, 6}, 0);
        cube.add("origin", array(new double[]{3, 4, 5}));
        cube.add("rotation", array(new double[]{10, 20, 30}));
        JsonObject headCube = cube("cube-2", new double[]{-2, 8, -2}, new double[]{2, 12, 2}, 0);
        JsonObject body = group("g-body", "body", new double[]{0, 8, 0}, "cube-1");
        body.add("rotation", array(new double[]{0, 90, 15}));
        JsonObject head = group("g-head", "h_head", new double[]{0, 12, 0}, "cube-2");
        JsonObject root = group("g-root", "root", new double[]{0, 0, 0}, body, head);
        JsonObject walk = animation("walk cycle", "loop", false, 1, "g-body", "body",
                keyframe("rotation", 0, "catmullrom", "0", "10", "5"),
                keyframe("rotation", 0.5, "catmullrom", "45", "-20", "0"),
                keyframe("position", 0, "linear", "1", "2", "3"),
                keyframe("position", 0.5, "linear", "math.sin(q.life_time * 90)", "", "0.25"),
                keyframe("scale", 0.5, "linear", "1", "1.5", "1"));
        JsonObject wave = animation("wave", "hold", false, 1, "g-body", "body",
                keyframe("rotation", 0, "catmullrom", "0", "0", "0"),
                keyframe("rotation", 1, "catmullrom", "0", "0", "90"));
        JsonObject over = animation("emote", "once", true, 2, "g-head", "h_head",
                keyframe("rotation", 0, "linear", "0", "0", "0"),
                keyframe("rotation", 2, "linear", "0", "30", "0"));
        return model(new JsonObject[]{texture("body.png", png(255, 0x336699))},
                new JsonObject[]{cube, headCube}, new JsonObject[]{root}, walk, wave, over);
    }
}
