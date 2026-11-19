package dev.bedrockbundler.common.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.bedrockbundler.common.Json;
import dev.bedrockbundler.common.input.EmoteAnimation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ItemsAdder emotes as Bedrock player animations.
 *
 * <p>ItemsAdder bakes an emote into keyframes at 20 per second ({@code .player_advanced_animations}): for every
 * bone of its player model the pivot's position (blocks) and the bone's rotation, both global (model space, not
 * relative to the parent). The rotation matrix is {@code Rz(-z) Ry(-y) Rx(x)}: with it, every child pivot is
 * its parent's pivot moved by the parent's rotation, to 0.0001 block in ItemsAdderAdditions' boogie_bomb.
 *
 * <p>Bedrock's player geometry has the same bones and hierarchy (root > waist > body > head, arms; legs under
 * root), but its body pivot is the neck, where ItemsAdder's is the hips. Each bone's global motion is turned into
 * a rotation about the Bedrock pivot relative to the Bedrock parent, plus a position offset making up for the
 * pivot difference. Bedrock bone rotations are {@code Rz(-z) Ry(y) Rx(-x)} in geometry space (see ItemConverter).
 * ItemsAdder's space is the world's (facing +z, left arm at +x); Bedrock's geometry has the face at -z with the left
 * arm still at +x, so it is ItemsAdder's space mirrored in z. Taking them as the same drew every emote mirrored in
 * game. Mirrored in z, ItemsAdder's rotation formula becomes exactly Bedrock's.
 *
 * <p>The paper module's EmotePlayer plays {@link #animation(String)} on the player for Bedrock viewers with an
 * AnimateEntityPacket, and {@link #STOP} to end it early.
 */
public final class EmoteConverter {

    static final String FILE = "animations/" + ConversionContext.NAMESPACE + "/emotes.animation.json";
    private static final String PREFIX = "animation." + ConversionContext.NAMESPACE + ".emote.";
    /** An empty animation that replaces a playing emote. */
    public static final String STOP = PREFIX + "stop";
    /** Suffix of an emote's end animation (what ItemsAdder plays after the loop when the emote is ended). */
    private static final String END = ".end";
    private static final double FPS = 20;

    /** ItemsAdder bone name prefix -> Bedrock bone, its parent (null: root) and pivot in pixels. */
    private record Bone(String prefix, String bedrock, String parent, double[] pivot) {
    }

    private static final List<Bone> BONES = List.of(
            new Bone("pbody", "body", null, new double[]{0, 24, 0}),
            new Bone("phead", "head", "pbody", new double[]{0, 24, 0}),
            new Bone("parm_left", "leftArm", "pbody", new double[]{5, 22, 0}),
            new Bone("parm_right", "rightArm", "pbody", new double[]{-5, 22, 0}),
            new Bone("pleg_left", "leftLeg", null, new double[]{1.9, 12, 0}),
            new Bone("pleg_right", "rightLeg", null, new double[]{-1.9, 12, 0}));

    private EmoteConverter() {
    }

    /** The Bedrock animation an emote plays (the ItemsAdder emote's name, with or without namespace). */
    public static String animation(String emote) {
        String name = emote.substring(emote.indexOf(':') + 1).toLowerCase(Locale.ROOT);
        return PREFIX + name.replaceAll("[^a-z0-9_]", "_");
    }

    /** The animation EmotePlayer plays when the content plugin ends an emote. */
    public static String end(String emote) {
        return animation(emote) + END;
    }

    static void convert(List<EmoteAnimation> files, ConversionContext ctx) {
        if (files.isEmpty()) {
            return;
        }
        JsonObject animations = new JsonObject();
        // No "bones": an empty bones object is an error that makes the client drop the whole file.
        JsonObject stop = new JsonObject();
        stop.addProperty("animation_length", 0.05);
        animations.add(STOP, stop);
        for (EmoteAnimation file : files) {
            try {
                JsonObject root = Json.parse(file.json().getBytes(java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                Map<String, double[]> rest = new LinkedHashMap<>();
                for (JsonElement bone : root.getAsJsonArray("bones")) {
                    JsonObject b = bone.getAsJsonObject();
                    rest.put(b.get("name").getAsString(), doubles(b.getAsJsonArray("pos")));
                }
                for (JsonElement element : root.getAsJsonArray("animations")) {
                    JsonObject animation = element.getAsJsonObject();
                    String name = animation(animation.get("name").getAsString());
                    if (animations.has(name)) {
                        ctx.log().warn("Emote " + name + " of " + file.source() + " is defined twice; the first one is kept");
                        continue;
                    }
                    JsonObject[] converted = convert(animation, rest);
                    if (converted == null) {
                        ctx.log().warn("Emote " + animation.get("name").getAsString() + " of " + file.source()
                                + " has no player bones; skipped");
                    } else {
                        animations.add(name, converted[0]);
                        animations.add(name + END, converted[1]);
                    }
                }
            } catch (RuntimeException e) {
                ctx.log().warn("Emotes of " + file.source() + " could not be read: " + e);
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("format_version", "1.10.0"); // anim_time_update
        out.add("animations", animations);
        ctx.bedrockPack().json(FILE, out);
    }

    /**
     * The emote's main animation and its end. ItemsAdder plays a looping emote in three parts: the start up to
     * {@code loopStartTime}, the part up to {@code loopEndTime} over and over until the player ends the emote
     * (sneaking), then the rest. The main animation plays the start once and then keeps its time inside the loop
     * ({@code anim_time_update}), so it never finishes on its own; the end animation is the rest, which EmotePlayer
     * plays when ItemsAdder ends the emote. Emotes without such a loop get an empty end.
     *
     * @return {main, end}, or null without player bones
     */
    private static JsonObject[] convert(JsonObject animation, Map<String, double[]> rest) {
        JsonObject bones = animation.getAsJsonObject("bones");
        Map<String, String> names = new LinkedHashMap<>(); // our prefix -> ItemsAdder bone name
        for (Bone bone : BONES) {
            for (String name : bones.keySet()) {
                if (name.startsWith(bone.prefix()) && rest.containsKey(name)) {
                    names.put(bone.prefix(), name);
                    break;
                }
            }
        }
        String head = names.get("phead");
        if (head == null || !names.containsKey("pbody")) {
            return null;
        }
        // ItemsAdder blocks -> Bedrock pixels, the neck (head pivot) at 0, 24, 0.
        double[] neck = mirror(rest.get(head));
        double[] offset = {-neck[0] * 16, 24 - neck[1] * 16, -neck[2] * 16};

        int frames = Integer.MAX_VALUE;
        for (String name : names.values()) {
            frames = Math.min(frames, bones.getAsJsonObject(name).getAsJsonArray("keyframes").size());
        }
        // Bedrock bone -> per frame {rotation, position}
        Map<String, List<double[][]>> keys = new LinkedHashMap<>();
        Map<String, double[]> previous = new LinkedHashMap<>();
        for (int f = 0; f < frames; f++) {
            Map<String, Rigid> global = new LinkedHashMap<>();
            for (Bone bone : BONES) {
                String name = names.get(bone.prefix());
                if (name == null) {
                    continue;
                }
                JsonObject key = bones.getAsJsonObject(name).getAsJsonArray("keyframes").get(f).getAsJsonObject();
                double[] pos = pixels(mirror(doubles(key.getAsJsonArray("pos"))), offset);
                double[] restPos = pixels(mirror(rest.get(name)), offset);
                double[] rot = doubles(key.getAsJsonArray("rot"));
                double[][] m = mirror(mul(mul(rz(-rot[2]), ry(-rot[1])), rx(rot[0])));
                // x -> pos + m (x - restPos)
                global.put(bone.prefix(), new Rigid(m, sub(pos, apply(m, restPos))));
            }
            for (Bone bone : BONES) {
                Rigid g = global.get(bone.prefix());
                if (g == null) {
                    continue;
                }
                Rigid parent = bone.parent() == null ? null : global.get(bone.parent());
                Rigid local = parent == null ? g : parent.inverse().then(g);
                // Bedrock: x -> P + d + A (x - P)  =>  d = t - P + A P
                double[] p = bone.pivot();
                double[] d = add(sub(local.t(), p), apply(local.m(), p));
                double[] euler = closest(local.m(), previous.get(bone.bedrock()));
                previous.put(bone.bedrock(), euler);
                keys.computeIfAbsent(bone.bedrock(), k -> new java.util.ArrayList<>()).add(new double[][]{euler, d});
            }
        }

        double length = (frames - 1) / FPS;
        JsonObject main = new JsonObject();
        JsonObject end = new JsonObject();
        String mode = animation.has("loopMode") ? animation.get("loopMode").getAsString() : "once";
        if (mode.equals("loop")) {
            double loopStart = animation.has("loopStartTime") ? animation.get("loopStartTime").getAsDouble() : 0;
            double loopEnd = animation.has("loopEndTime") ? animation.get("loopEndTime").getAsDouble() : length;
            loopEnd = Math.min(loopEnd, length);
            if (loopEnd - loopStart >= 1 / FPS) {
                String t = "q.anim_time + q.delta_time";
                main.addProperty("anim_time_update", String.format(Locale.ROOT, "%s > %s ? %s - %s : %s",
                        t, number(loopEnd), t, number(loopEnd - loopStart), t));
                int first = (int) Math.ceil(loopEnd * FPS - 1e-6);
                if (frames - 1 - first >= 1) {
                    end.addProperty("animation_length", (frames - 1 - first) / FPS);
                    end.add("bones", bones(keys, first, frames - 1));
                }
            } else {
                main.addProperty("loop", true);
            }
        } else if (mode.equals("hold")) {
            main.addProperty("loop", "hold_on_last_frame");
        }
        main.addProperty("animation_length", Math.max(0.05, length));
        main.add("bones", bones(keys, 0, frames - 1));
        if (!end.has("animation_length")) {
            // Nothing after the loop: an end that only replaces the main animation. No "bones" (see STOP).
            end.addProperty("animation_length", 0.05);
        }
        return new JsonObject[]{main, end};
    }

    /** Keyframes {@code from..to} (frame indices), timed from {@code from}. */
    private static JsonObject bones(Map<String, List<double[][]>> keys, int from, int to) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, List<double[][]>> bone : keys.entrySet()) {
            JsonObject rotation = new JsonObject();
            JsonObject position = new JsonObject();
            for (int f = from; f <= to; f++) {
                String time = String.format(Locale.ROOT, "%.4f", (f - from) / FPS);
                rotation.add(time, array(bone.getValue().get(f)[0]));
                position.add(time, array(bone.getValue().get(f)[1]));
            }
            JsonObject b = new JsonObject();
            b.add("rotation", rotation);
            b.add("position", position);
            out.add(bone.getKey(), b);
        }
        return out;
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    /** A rigid motion x -> m x + t. */
    record Rigid(double[][] m, double[] t) {

        Rigid inverse() {
            double[][] mt = transpose(m);
            return new Rigid(mt, scale(apply(mt, t), -1));
        }

        /** The motion x -> this(next(x)): {@code next} first. */
        Rigid then(Rigid next) {
            return new Rigid(mul(m, next.m()), add(apply(m, next.t()), t));
        }
    }

    /** Bedrock bone rotation [x, y, z] for matrix a = Rz(-z) Ry(y) Rx(-x). */
    static double[] bedrockEuler(double[][] a) {
        double pitch = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, -a[2][0])))); // Ry angle
        double roll = Math.toDegrees(Math.atan2(a[2][1], a[2][2]));                    // Rx angle
        double yaw = Math.toDegrees(Math.atan2(a[1][0], a[0][0]));                     // Rz angle
        return new double[]{-roll, pitch, -yaw};
    }

    /**
     * The Bedrock rotation of {@code m} nearest the previous frame's. A rotation has two Euler triples (and any
     * multiple of 360 degrees); jumping between them makes Bedrock's interpolation whirl the bone around.
     */
    static double[] closest(double[][] m, double[] previous) {
        double[] first = bedrockEuler(m);
        // The other solution of Rz(c) Ry(b) Rx(a): a + 180, 180 - b, c + 180, in Bedrock signs.
        double[] second = {first[0] - 180, 180 - first[1], first[2] - 180};
        if (previous == null) {
            return first;
        }
        first = unwrap(first, previous);
        second = unwrap(second, previous);
        return distance(second, previous) < distance(first, previous) ? second : first;
    }

    private static double distance(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);
    }

    /** Keeps each angle within 180 degrees of the previous frame, so interpolation does not spin around. */
    private static double[] unwrap(double[] angles, double[] previous) {
        if (previous == null) {
            return angles;
        }
        double[] out = angles.clone();
        for (int i = 0; i < 3; i++) {
            while (out[i] - previous[i] > 180) {
                out[i] -= 360;
            }
            while (out[i] - previous[i] < -180) {
                out[i] += 360;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- math

    /** ItemsAdder's space (the world's: facing +z, left at +x) -> Bedrock geometry space (face at -z): z mirrored. */
    static double[] mirror(double[] v) {
        return new double[]{v[0], v[1], -v[2]};
    }

    /** A rotation seen in the z-mirrored space: T m T with T = diag(1, 1, -1). */
    static double[][] mirror(double[][] m) {
        double[] t = {1, 1, -1};
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i][j] = m[i][j] * t[i] * t[j];
            }
        }
        return r;
    }

    static double[][] rx(double deg) {
        double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a);
        return new double[][]{{1, 0, 0}, {0, c, -s}, {0, s, c}};
    }

    static double[][] ry(double deg) {
        double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a);
        return new double[][]{{c, 0, s}, {0, 1, 0}, {-s, 0, c}};
    }

    static double[][] rz(double deg) {
        double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a);
        return new double[][]{{c, -s, 0}, {s, c, 0}, {0, 0, 1}};
    }

    static double[][] mul(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
            }
        }
        return r;
    }

    private static double[][] transpose(double[][] a) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i][j] = a[j][i];
            }
        }
        return r;
    }

    static double[] apply(double[][] m, double[] v) {
        return new double[]{
                m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
    }

    private static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] scale(double[] a, double s) {
        return new double[]{a[0] * s, a[1] * s, a[2] * s};
    }

    private static double[] pixels(double[] blocks, double[] offset) {
        return new double[]{blocks[0] * 16 + offset[0], blocks[1] * 16 + offset[1], blocks[2] * 16 + offset[2]};
    }

    private static double[] doubles(JsonArray array) {
        return new double[]{array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble()};
    }

    private static JsonArray array(double[] v) {
        JsonArray a = new JsonArray();
        for (double d : v) {
            a.add(Math.round(d * 1000) / 1000.0);
        }
        return a;
    }
}
