package dev.bedrockbundler.common.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.bedrockbundler.common.Json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Blockbench {@code .bbmodel} to Bedrock geometry, animation and texture data.
 *
 * <p>Port of {@code tools/bedrock/bbmodel_gme.py} (MrMirhan), which follows the rules of Blockbench's Bedrock codec
 * (Blockbench, GPL-3.0, only its published behaviour is reproduced, no code): X is mirrored, X/Y rotations change
 * sign, the up and down face UVs are flipped, a face rotation selects format 1.21.0, animation position X and
 * rotation X/Y are negated, {@code catmullrom} keyframes keep their interpolation, loop modes map to
 * {@code loop}/{@code hold_on_last_frame}. Cubes that use several textures are split into one {@code uv_} bone per
 * texture so each texture can be drawn by its own render controller. Texture names drop their image extensions like
 * the Blockbench packer of GeyserModelEngine does (MIT, copyright zimzaza4 and contributors, see
 * THIRD_PARTY_NOTICES.md).
 *
 * @author MrMirhan
 */
final class BbmodelReader {

    private static final Pattern ID = Pattern.compile("[a-z0-9_]+");
    private static final Pattern TEXTURE_EXTENSION = Pattern.compile("(\\.(png|jpe?g|webp|tga))+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");
    private static final String[] FACES = {"north", "east", "south", "west", "up", "down"};
    private static final int MAX_PNG = 1 << 20;
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};

    private final JsonObject model;
    private final String id;
    private final Consumer<String> warn;
    private final int resWidth;
    private final int resHeight;
    private final Map<String, JsonObject> elements = new LinkedHashMap<>();
    private final JsonArray bones = new JsonArray();
    private final Set<String> usedNames = new HashSet<>();
    private final Map<String, String> uuidToName = new LinkedHashMap<>();
    private final Map<Integer, Set<String>> textureBones = new TreeMap<>();
    private double minY;
    private double maxY;
    private double radius;


    private BbmodelReader(JsonObject model, String id, Consumer<String> warn) {
        this.model = model;
        this.id = id;
        this.warn = warn;
        JsonObject resolution = obj(model, "resolution");
        this.resWidth = orDefault((int) num(resolution, "width", 0), 16);
        this.resHeight = orDefault((int) num(resolution, "height", 0), 16);
    }

    static BedrockModel read(String id, byte[] bytes, Consumer<String> warn) {
        if (!ID.matcher(id).matches()) {
            throw new ModelException("model id must be lower case [a-z0-9_]");
        }
        JsonObject root;
        try {
            JsonElement parsed = Json.parse(bytes);
            if (!parsed.isJsonObject()) {
                throw new ModelException("not a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            if (e instanceof ModelException) {
                throw e;
            }
            throw new ModelException("not valid JSON: " + e.getMessage(), e);
        }
        return new BbmodelReader(root, id, warn).run();
    }

    private BedrockModel run() {
        JsonArray elementList = arr(model, "elements");
        if (elementList != null) {
            for (JsonElement e : elementList) {
                if (e.isJsonObject() && e.getAsJsonObject().has("uuid")) {
                    elements.put(e.getAsJsonObject().get("uuid").getAsString(), e.getAsJsonObject());
                }
            }
        }
        List<RawTexture> rawTextures = readTextures();
        JsonObject geometry = geometry();
        if (rawTextures.isEmpty()) {
            throw new ModelException("no texture");
        }
        JsonObject animations = animations();

        List<BedrockModel.Texture> textures = new ArrayList<>();
        for (RawTexture t : rawTextures) {
            Set<String> bound = textureBones.getOrDefault(t.index, Set.of());
            textures.add(new BedrockModel.Texture(t.name, t.png, t.uvWidth, t.uvHeight, new TreeSet<>(bound)));
        }
        int boneCount = geometry.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones").size();
        int animationCount = animations.size();
        int properties = ((boneCount + 23) / 24) * 2 + (animationCount + 23) / 24 + 1;
        if (properties > 32) {
            throw new ModelException("entity properties " + properties + " > 32 (" + boneCount + " bones, "
                    + animationCount + " animations): reduce the bone or animation count");
        }
        return new BedrockModel(id, geometry, animations, textures);
    }

    // ---- textures ----------------------------------------------------------------------------------------------

    private record RawTexture(int index, String name, byte[] png, int uvWidth, int uvHeight) {
    }

    private List<RawTexture> readTextures() {
        List<RawTexture> result = new ArrayList<>();
        JsonArray list = arr(model, "textures");
        if (list == null) {
            return result;
        }
        Set<String> names = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            if (!list.get(i).isJsonObject()) {
                continue;
            }
            JsonObject t = list.get(i).getAsJsonObject();
            String source = str(t, "source");
            if (source == null || !source.contains(",")) {
                continue;
            }
            byte[] data;
            try {
                data = Base64.getMimeDecoder().decode(source.substring(source.indexOf(',') + 1));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!startsWith(data, PNG_MAGIC)) {
                warn.accept("texture " + str(t, "name") + " is not a PNG");
                continue;
            }
            String rawName = str(t, "name");
            String name = TEXTURE_EXTENSION.matcher(rawName == null || rawName.isEmpty() ? "texture_" + i : rawName).replaceAll("");
            name = name.replaceAll("[^A-Za-z0-9_]+", "_");
            if (name.isEmpty()) {
                name = "texture_" + i;
            }
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                String unique = name + "_" + i;
                warn.accept("texture name " + name + " is used twice, renamed to " + unique);
                name = unique;
                names.add(name.toLowerCase(Locale.ROOT));
            }
            if (data.length > MAX_PNG) {
                warn.accept("texture " + name + " is " + data.length / 1024 + " KB (large)");
            }
            int uw = orDefault((int) num(t, "uv_width", 0), resWidth);
            int uh = orDefault((int) num(t, "uv_height", 0), resHeight);
            result.add(new RawTexture(i, name, data, uw, uh));
        }
        return result;
    }

    // ---- geometry ----------------------------------------------------------------------------------------------

    private JsonObject cube(JsonObject e, Set<Integer> usedTextures) {
        double[] from = vec(e.get("from"));
        double[] to = vec(e.get("to"));
        double[] size = {to[0] - from[0], to[1] - from[1], to[2] - from[2]};
        JsonObject cube = new JsonObject();
        cube.add("origin", numbers(-to[0], from[1], from[2]));
        cube.add("size", numbers(size[0], size[1], size[2]));
        double inflate = num(e, "inflate", 0);
        if (inflate != 0) {
            cube.add("inflate", JsonNum.of(inflate));
        }
        double[] rotation = e.has("rotation") && e.get("rotation").isJsonArray() ? vec(e.get("rotation")) : new double[3];
        if (rotation[0] != 0 || rotation[1] != 0 || rotation[2] != 0) {
            double[] origin = e.has("origin") && e.get("origin").isJsonArray() ? vec(e.get("origin")) : new double[3];
            cube.add("pivot", numbers(-origin[0], origin[1], origin[2]));
            cube.add("rotation", numbers(-rotation[0], -rotation[1], rotation[2]));
        }
        JsonObject uv = new JsonObject();
        JsonObject faces = obj(e, "faces");
        for (String face : FACES) {
            JsonObject fc = obj(faces, face);
            if (fc == null || !fc.has("texture") || fc.get("texture").isJsonNull()) {
                continue;
            }
            double[] box = fc.has("uv") && fc.get("uv").isJsonArray() ? vec4(fc.get("uv")) : new double[4];
            double u1 = box[0];
            double v1 = box[1];
            double u2 = box[2];
            double v2 = box[3];
            double uvU = u1;
            double uvV = v1;
            double sizeU = u2 - u1;
            double sizeV = v2 - v1;
            if (face.equals("up") || face.equals("down")) {
                // Blockbench keeps the up and down face UV flipped
                uvU = u1 + (u2 - u1);
                uvV = v1 + (v2 - v1);
                sizeU = -(u2 - u1);
                sizeV = -(v2 - v1);
            }
            JsonObject entry = new JsonObject();
            entry.add("uv", numbers(uvU, uvV));
            entry.add("uv_size", numbers(sizeU, sizeV));
            double faceRotation = num(fc, "rotation", 0);
            if (faceRotation != 0) {
                entry.addProperty("uv_rotation", (int) faceRotation);
            }
            uv.add(face, entry);
            usedTextures.add((int) num(fc, "texture", 0));
        }
        if (uv.size() == 0) {
            return null;
        }
        cube.add("uv", uv);
        return cube;
    }

    /** Keeps only the faces of {@code cube} that use texture {@code index} (cubes with several textures). */
    private static JsonObject faceFilter(JsonObject cube, JsonObject element, int index) {
        JsonObject copy = cube.deepCopy();
        JsonObject uv = copy.getAsJsonObject("uv");
        JsonObject faces = obj(element, "faces");
        for (String face : new ArrayList<>(uv.keySet())) {
            JsonObject fc = obj(faces, face);
            if (fc == null || (int) num(fc, "texture", -1) != index) {
                uv.remove(face);
            }
        }
        return copy;
    }

    private void group(JsonObject g, String parent) {
        if (g.has("export") && g.get("export").isJsonPrimitive() && !g.get("export").getAsBoolean()) {
            return;
        }
        String name = ModelSpec.boneKey(strOr(g, "name", "bone"));
        String base = name;
        int n = 2;
        while (usedNames.contains(name)) {
            name = base + "_" + n++;
        }
        usedNames.add(name);
        uuidToName.put(strOr(g, "uuid", name), name);
        double[] origin = g.has("origin") && g.get("origin").isJsonArray() ? vec(g.get("origin")) : new double[3];
        JsonObject bone = new JsonObject();
        bone.addProperty("name", name);
        bone.add("pivot", numbers(-origin[0], origin[1], origin[2]));
        if (parent != null) {
            bone.addProperty("parent", parent);
        }
        double[] rotation = g.has("rotation") && g.get("rotation").isJsonArray() ? vec(g.get("rotation")) : new double[3];
        if (rotation[0] != 0 || rotation[1] != 0 || rotation[2] != 0) {
            bone.add("rotation", numbers(-rotation[0], -rotation[1], rotation[2]));
        }
        bones.add(bone);

        Map<Integer, JsonArray> cubesByTexture = new TreeMap<>();
        JsonArray children = arr(g, "children");
        if (children != null) {
            for (JsonElement child : children) {
                if (child.isJsonObject()) {
                    group(child.getAsJsonObject(), name);
                    continue;
                }
                JsonObject e = child.isJsonPrimitive() ? elements.get(child.getAsString()) : null;
                if (e == null || !"cube".equals(strOr(e, "type", "cube")) || isFalse(e, "export")) {
                    if (e != null && !"cube".equals(strOr(e, "type", "cube"))) {
                        warn.accept(name + ": '" + str(e, "type") + "' element skipped (cubes only)");
                    }
                    continue;
                }
                Set<Integer> used = new TreeSet<>();
                JsonObject cube = cube(e, used);
                if (cube == null) {
                    continue;
                }
                bounds(cube);
                boolean split = used.size() > 1;
                for (int texture : used) {
                    cubesByTexture.computeIfAbsent(texture, k -> new JsonArray())
                            .add(split ? faceFilter(cube, e, texture) : cube);
                }
            }
        }
        if (cubesByTexture.size() == 1) {
            Map.Entry<Integer, JsonArray> only = cubesByTexture.entrySet().iterator().next();
            bone.add("cubes", only.getValue());
            textureBones.computeIfAbsent(only.getKey(), k -> new TreeSet<>()).add(name);
        } else {
            int order = 0;
            for (Map.Entry<Integer, JsonArray> entry : cubesByTexture.entrySet()) {
                String sub = "uv_" + name + order++;
                usedNames.add(sub);
                JsonObject subBone = new JsonObject();
                subBone.addProperty("name", sub);
                subBone.addProperty("parent", name);
                subBone.add("pivot", numbers(0, 0, 0));
                subBone.add("cubes", entry.getValue());
                bones.add(subBone);
                textureBones.computeIfAbsent(entry.getKey(), k -> new TreeSet<>()).add(sub);
            }
        }
    }

    private void bounds(JsonObject cube) {
        JsonArray o = cube.getAsJsonArray("origin");
        JsonArray s = cube.getAsJsonArray("size");
        double ox = o.get(0).getAsDouble();
        double oy = o.get(1).getAsDouble();
        double oz = o.get(2).getAsDouble();
        minY = Math.min(minY, oy);
        maxY = Math.max(maxY, oy + s.get(1).getAsDouble());
        radius = Math.max(radius, Math.max(Math.max(Math.abs(ox), Math.abs(ox + s.get(0).getAsDouble())),
                Math.max(Math.abs(oz), Math.abs(oz + s.get(2).getAsDouble()))));
    }

    private boolean hasFaceRotation() {
        for (JsonElement bone : bones) {
            JsonArray cubes = bone.getAsJsonObject().getAsJsonArray("cubes");
            if (cubes == null) {
                continue;
            }
            for (JsonElement cube : cubes) {
                for (JsonElement face : cube.getAsJsonObject().getAsJsonObject("uv").asMap().values()) {
                    if (face.getAsJsonObject().has("uv_rotation")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private JsonObject geometry() {
        JsonArray outliner = arr(model, "outliner");
        if (outliner != null) {
            for (JsonElement node : outliner) {
                if (node.isJsonObject()) {
                    group(node.getAsJsonObject(), null);
                    continue;
                }
                JsonObject e = node.isJsonPrimitive() ? elements.get(node.getAsString()) : null;
                if (e == null || !"cube".equals(strOr(e, "type", "cube")) || isFalse(e, "export")) {
                    continue;
                }
                Set<Integer> used = new TreeSet<>();
                JsonObject cube = cube(e, used);
                if (cube == null) {
                    continue;
                }
                bounds(cube);
                JsonObject main = null;
                for (JsonElement b : bones) {
                    if (b.getAsJsonObject().get("name").getAsString().equals("bb_main")) {
                        main = b.getAsJsonObject();
                        break;
                    }
                }
                if (main == null) {
                    main = new JsonObject();
                    main.addProperty("name", "bb_main");
                    main.add("pivot", numbers(0, 0, 0));
                    main.add("cubes", new JsonArray());
                    usedNames.add("bb_main");
                    JsonArray reordered = new JsonArray();
                    reordered.add(main);
                    bones.forEach(reordered::add);
                    while (bones.size() > 0) {
                        bones.remove(0);
                    }
                    reordered.forEach(bones::add);
                }
                main.getAsJsonArray("cubes").add(cube);
                for (int texture : used) {
                    textureBones.computeIfAbsent(texture, k -> new TreeSet<>()).add("bb_main");
                }
            }
        }
        boolean anyCube = false;
        for (JsonElement b : bones) {
            JsonArray cubes = b.getAsJsonObject().getAsJsonArray("cubes");
            if (cubes != null && cubes.size() > 0) {
                anyCube = true;
                break;
            }
        }
        if (!anyCube) {
            throw new ModelException("no cubes");
        }
        int width = Math.max(4, (int) Math.ceil(radius * 2 / 16) + 1);
        int height = Math.max(4, (int) Math.ceil((maxY - minY) / 16) + 1);
        JsonObject description = new JsonObject();
        description.addProperty("identifier", "geometry." + id);
        description.addProperty("texture_width", resWidth);
        description.addProperty("texture_height", resHeight);
        description.addProperty("visible_bounds_width", width);
        description.addProperty("visible_bounds_height", height);
        description.add("visible_bounds_offset", numbers(0, (minY + maxY) / 32, 0));
        JsonObject geo = new JsonObject();
        geo.add("description", description);
        geo.add("bones", bones);
        JsonArray list = new JsonArray();
        list.add(geo);
        JsonObject doc = new JsonObject();
        doc.addProperty("format_version", hasFaceRotation() ? "1.21.0" : "1.12.0");
        doc.add("minecraft:geometry", list);
        return doc;
    }

    // ---- animations --------------------------------------------------------------------------------------------

    private JsonObject animations() {
        JsonObject result = new JsonObject();
        JsonArray list = arr(model, "animations");
        if (list == null) {
            return result;
        }
        for (JsonElement el : list) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject a = el.getAsJsonObject();
            String name = ModelSpec.animationKey(strOr(a, "name", "animation"));
            JsonObject tag = new JsonObject();
            String loop = str(a, "loop");
            if ("loop".equals(loop)) {
                tag.addProperty("loop", true);
            } else if ("hold".equals(loop)) {
                tag.addProperty("loop", "hold_on_last_frame");
            }
            if (truthy(a.get("override"))) {
                tag.addProperty("override_previous_animation", true);
            }
            if (num(a, "length", 0) != 0) {
                tag.add("animation_length", JsonNum.of(num(a, "length", 0)));
            }
            JsonObject boneMap = new JsonObject();
            JsonObject animators = obj(a, "animators");
            if (animators != null) {
                for (Map.Entry<String, JsonElement> entry : animators.entrySet()) {
                    if (!entry.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject animator = entry.getValue().getAsJsonObject();
                    if (!"bone".equals(strOr(animator, "type", "bone"))) {
                        continue;
                    }
                    String boneName = uuidToName.get(entry.getKey());
                    if (boneName == null) {
                        boneName = ModelSpec.boneKey(strOr(animator, "name", ""));
                    }
                    JsonObject channels = new JsonObject();
                    for (JsonObject kf : sortedKeyframes(animator)) {
                        String channel = str(kf, "channel");
                        if (!"rotation".equals(channel) && !"position".equals(channel) && !"scale".equals(channel)) {
                            continue;
                        }
                        JsonObject dp = firstDataPoint(kf);
                        Object[] v = {value(dp.get("x")), value(dp.get("y")), value(dp.get("z"))};
                        if (channel.equals("rotation")) {
                            v[0] = negate(v[0]);
                            v[1] = negate(v[1]);
                        } else if (channel.equals("position")) {
                            v[0] = negate(v[0]);
                        }
                        JsonArray vector = new JsonArray();
                        for (Object o : v) {
                            vector.add(o instanceof Double d ? JsonNum.of(d) : new JsonPrimitive((String) o));
                        }
                        String interpolation = strOr(kf, "interpolation", "linear");
                        JsonElement point;
                        if (interpolation.equals("catmullrom")) {
                            JsonObject smooth = new JsonObject();
                            smooth.add("post", vector);
                            smooth.addProperty("lerp_mode", "catmullrom");
                            point = smooth;
                        } else {
                            if (!interpolation.equals("linear")) {
                                warn.accept("animation " + name + ": '" + interpolation + "' interpolation made linear");
                            }
                            point = vector;
                        }
                        JsonObject channelObject = channels.has(channel) ? channels.getAsJsonObject(channel) : new JsonObject();
                        channels.add(channel, channelObject);
                        channelObject.add(JsonNum.text(num(kf, "time", 0)), point);
                    }
                    if (channels.size() > 0) {
                        boneMap.add(boneName, channels);
                    }
                }
            }
            if (boneMap.size() > 0) {
                tag.add("bones", boneMap);
                result.add(name, tag);
            }
        }
        return result;
    }

    private static List<JsonObject> sortedKeyframes(JsonObject animator) {
        List<JsonObject> keyframes = new ArrayList<>();
        JsonArray list = arr(animator, "keyframes");
        if (list != null) {
            for (JsonElement el : list) {
                if (el.isJsonObject()) {
                    keyframes.add(el.getAsJsonObject());
                }
            }
        }
        keyframes.sort((a, b) -> Double.compare(num(a, "time", 0), num(b, "time", 0)));
        return keyframes;
    }

    private static JsonObject firstDataPoint(JsonObject keyframe) {
        JsonArray points = arr(keyframe, "data_points");
        if (points != null && points.size() > 0 && points.get(0).isJsonObject()) {
            return points.get(0).getAsJsonObject();
        }
        return new JsonObject();
    }

    /** A keyframe component: a number, or Molang text when it is not a plain number. */
    private static Object value(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return 0.0;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return element.getAsDouble();
        }
        String text = element.getAsString().strip();
        if (text.isEmpty()) {
            return 0.0;
        }
        if (NUMBER.matcher(text).matches()) {
            return Double.parseDouble(text);
        }
        return text;
    }

    private static Object negate(Object value) {
        return value instanceof Double d ? (Object) (-d) : "-(" + value + ")";
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private static JsonArray numbers(double... values) {
        JsonArray array = new JsonArray();
        for (double v : values) {
            array.add(JsonNum.of(v));
        }
        return array;
    }

    private static double[] vec(JsonElement element) {
        double[] v = new double[3];
        if (element != null && element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < 3 && i < array.size(); i++) {
                v[i] = array.get(i).getAsDouble();
            }
        }
        return v;
    }

    private static double[] vec4(JsonElement element) {
        double[] v = new double[4];
        JsonArray array = element.getAsJsonArray();
        for (int i = 0; i < 4 && i < array.size(); i++) {
            v[i] = array.get(i).getAsDouble();
        }
        return v;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean truthy(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive p = element.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return p.getAsBoolean();
            }
            if (p.isNumber()) {
                return p.getAsDouble() != 0;
            }
            return !p.getAsString().isEmpty();
        }
        return true;
    }

    private static boolean isFalse(JsonObject object, String key) {
        JsonElement e = object.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && !e.getAsBoolean();
    }

    private static int orDefault(int value, int fallback) {
        return value != 0 ? value : fallback;
    }

    private static JsonObject obj(JsonObject object, String key) {
        return Json.object(object, key);
    }

    private static JsonArray arr(JsonObject object, String key) {
        return Json.array(object, key);
    }

    private static String str(JsonObject object, String key) {
        return Json.string(object, key, null);
    }

    private static String strOr(JsonObject object, String key, String fallback) {
        return Json.string(object, key, fallback);
    }

    private static double num(JsonObject object, String key, double fallback) {
        JsonElement e = object == null ? null : object.get(key);
        if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            return e.getAsDouble();
        }
        return fallback;
    }
}
