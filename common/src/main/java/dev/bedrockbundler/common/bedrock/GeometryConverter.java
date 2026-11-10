package dev.bedrockbundler.common.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bedrockbundler.common.Json;
import dev.bedrockbundler.common.java.JavaModel;

import java.util.Map;
import java.util.function.Function;

/**
 * Converts Java model elements to Bedrock geometry.
 *
 * <p>Java models live in a 0-16 cube with the origin at a corner; Bedrock geometry is centred on x/z and
 * mirrored on x. This follows the same conventions Blockbench uses when exporting Bedrock models.
 */
public final class GeometryConverter {

    /** Root bone of item geometry; display transforms animate this bone and its axis children. */
    public static final String ITEM_ROOT = "bedrockbundler";

    /** Bones above the root: binding, right and left arm motion (see {@link #item}). */
    public static final String HOLD = "_hold";
    public static final String ARM_RIGHT = "_arm_r";
    public static final String ARM_LEFT = "_arm_l";
    /** Bone between the root and the display transform bones. */
    public static final String USE = "_use";

    /** Molang binding of the item's top bone to the holder's slot bone. */
    static final String ITEM_BINDING = "c.item_slot == 'head' ? 'head' : q.item_slot_to_bone_name(c.item_slot)";

    private GeometryConverter() {
    }

    /**
     * Geometry for an attachable (3D item held in hand). All faces sample from {@code atlas}.
     * The cubes hang under {@code bedrockbundler > _x > _y > _z} so animations can apply Java display
     * rotations one axis at a time, in Java's X-Y-Z order.
     */
    public static JsonObject item(String identifier, JavaModel model, TextureAtlas atlas) {
        JsonArray cubes = new JsonArray();
        for (JavaModel.Element element : model.elements()) {
            for (JavaModel.Element part : separateFlatFaces(element)) {
                cubes.add(cube(part, face -> {
                    TextureAtlas.Slot slot = face.texture() == null ? null : atlas.slot(face.texture());
                    return slot == null ? null : new float[]{slot.x(), slot.y(), slot.width() / 16f, slot.height() / 16f};
                }, null));
            }
        }

        JsonArray bones = new JsonArray();
        JsonObject hold = bone(ITEM_ROOT + HOLD, null, null);
        // Attach to the bone of the slot holding the item (right/left hand, head). Without a binding the
        // model is drawn at the player's origin: not in the hand and not following the swing animation.
        hold.addProperty("binding", ITEM_BINDING);
        bones.add(hold);
        // The holder's arm motion for poses Bedrock only plays for vanilla items (shield blocking). The grip is
        // where the third person hand pose puts the Java model centre (ItemConverter, pivot [0, 8, 0] moved by
        // [0, 13, -3]); the shoulders sit [+-1, 7, -1] from it, as rightArm/rightItem and leftArm/leftItem in the
        // player geometry.
        bones.add(bone(ITEM_ROOT + ARM_RIGHT, ITEM_ROOT + HOLD, null, 1, 28, -4));
        bones.add(bone(ITEM_ROOT + ARM_LEFT, ITEM_ROOT + ARM_RIGHT, null, -1, 28, -4));
        bones.add(bone(ITEM_ROOT, ITEM_ROOT + ARM_LEFT, null));
        // Motion Java adds between the hand frame and the display transform (first person trident charging).
        bones.add(bone(ITEM_ROOT + USE, ITEM_ROOT, null));
        bones.add(bone(ITEM_ROOT + "_x", ITEM_ROOT + USE, null));
        bones.add(bone(ITEM_ROOT + "_y", ITEM_ROOT + "_x", null));
        bones.add(bone(ITEM_ROOT + "_z", ITEM_ROOT + "_y", cubes));
        return geometryFile(identifier, atlas.width(), atlas.height(), bones);
    }

    /** Java model pixels between the two faces of a flat element once they are pulled apart. */
    static final float FLAT_FACE_GAP = 0.05f;

    /**
     * A flat element (zero size on an axis, like a painting's canvas) with faces on both sides. Java culls the
     * face turned away from the camera; Bedrock's attachable material draws both, at the same depth, so they
     * flicker (z-fighting). Each of the two faces goes into a cube of its own, moved half a gap outwards, so the
     * face on the viewer's side is always in front.
     */
    static java.util.List<JavaModel.Element> separateFlatFaces(JavaModel.Element element) {
        String[][] sides = {{"west", "east"}, {"down", "up"}, {"north", "south"}};
        for (int axis = 0; axis < 3; axis++) {
            String low = sides[axis][0];
            String high = sides[axis][1];
            if (element.to()[axis] != element.from()[axis]
                    || !element.faces().containsKey(low) || !element.faces().containsKey(high)) {
                continue;
            }
            Map<String, JavaModel.Face> lowFaces = new java.util.LinkedHashMap<>(element.faces());
            lowFaces.remove(high);
            float[] lowFrom = element.from().clone();
            float[] lowTo = element.to().clone();
            lowFrom[axis] -= FLAT_FACE_GAP / 2;
            lowTo[axis] -= FLAT_FACE_GAP / 2;
            float[] highFrom = element.from().clone();
            float[] highTo = element.to().clone();
            highFrom[axis] += FLAT_FACE_GAP / 2;
            highTo[axis] += FLAT_FACE_GAP / 2;
            return java.util.List.of(
                    new JavaModel.Element(lowFrom, lowTo, element.rotation(), lowFaces),
                    new JavaModel.Element(highFrom, highTo, element.rotation(), Map.of(high, element.faces().get(high))));
        }
        return java.util.List.of(element);
    }

    /**
     * Geometry for a custom block. Each face points at a material instance named after the Java texture
     * variable, so every texture keeps its own resolution.
     */
    public static JsonObject block(String identifier, JavaModel model, Function<JavaModel.Face, String> materialInstance) {
        JsonArray cubes = new JsonArray();
        for (JavaModel.Element element : model.elements()) {
            cubes.add(cube(element, face -> new float[]{0, 0, 1, 1}, materialInstance));
        }
        JsonArray bones = new JsonArray();
        bones.add(bone("block", null, cubes));
        return geometryFile(identifier, 16, 16, bones);
    }

    private static JsonObject geometryFile(String identifier, int textureWidth, int textureHeight, JsonArray bones) {
        JsonObject description = new JsonObject();
        description.addProperty("identifier", identifier);
        description.addProperty("texture_width", textureWidth);
        description.addProperty("texture_height", textureHeight);
        description.addProperty("visible_bounds_width", 4);
        description.addProperty("visible_bounds_height", 4.5);
        description.add("visible_bounds_offset", Json.array(0, 0.75f, 0));

        JsonObject geometry = new JsonObject();
        geometry.add("description", description);
        geometry.add("bones", bones);
        JsonArray geometries = new JsonArray();
        geometries.add(geometry);

        JsonObject file = new JsonObject();
        file.addProperty("format_version", "1.16.0");
        file.add("minecraft:geometry", geometries);
        return file;
    }

    private static JsonObject bone(String name, String parent, JsonArray cubes) {
        return bone(name, parent, cubes, 0, 8, 0);
    }

    private static JsonObject bone(String name, String parent, JsonArray cubes, float px, float py, float pz) {
        JsonObject bone = new JsonObject();
        bone.addProperty("name", name);
        if (parent != null) {
            bone.addProperty("parent", parent);
        }
        bone.add("pivot", Json.array(px, py, pz));
        if (cubes != null) {
            bone.add("cubes", cubes);
        }
        return bone;
    }

    /**
     * @param uvTransform for a face returns {offsetX, offsetY, scaleX, scaleY} mapping Java 0-16 UVs into
     *                    the geometry's texture space, or null to drop the face
     */
    private static JsonObject cube(JavaModel.Element element, Function<JavaModel.Face, float[]> uvTransform,
                                   Function<JavaModel.Face, String> materialInstance) {
        float[] from = element.from().clone();
        float[] to = element.to().clone();
        JavaModel.Rotation rotation = element.rotation();
        if (rotation != null && rotation.rescale()) {
            rescale(from, to, rotation);
        }

        JsonObject cube = new JsonObject();
        cube.add("origin", Json.array(-to[0] + 8, from[1], from[2] - 8));
        cube.add("size", Json.array(to[0] - from[0], to[1] - from[1], to[2] - from[2]));
        if (rotation != null) {
            float[] o = rotation.origin();
            cube.add("pivot", Json.array(-o[0] + 8, o[1], o[2] - 8));
            float angle = rotation.angle();
            cube.add("rotation", switch (rotation.axis()) {
                case "x" -> Json.array(-angle, 0, 0);
                case "y" -> Json.array(0, -angle, 0);
                default -> Json.array(0, 0, angle);
            });
        }

        JsonObject faces = new JsonObject();
        for (Map.Entry<String, JavaModel.Face> entry : element.faces().entrySet()) {
            JavaModel.Face face = entry.getValue();
            float[] transform = uvTransform.apply(face);
            if (transform == null) {
                continue;
            }
            faces.add(entry.getKey(), face(entry.getKey(), face, transform,
                    materialInstance == null ? null : materialInstance.apply(face)));
        }
        cube.add("uv", faces);
        return cube;
    }

    private static JsonObject face(String name, JavaModel.Face face, float[] transform, String material) {
        float[] uv = face.uv();
        float u1 = transform[0] + uv[0] * transform[2];
        float v1 = transform[1] + uv[1] * transform[3];
        float u2 = transform[0] + uv[2] * transform[2];
        float v2 = transform[1] + uv[3] * transform[3];
        if (face.rotation() == 180) {
            float t = u1; u1 = u2; u2 = t;
            t = v1; v1 = v2; v2 = t;
        }
        // 90/270 degree UV rotation has no equivalent in the 1.16 geometry format and stays unrotated.

        JsonObject json = new JsonObject();
        switch (name) {
            case "up" -> {
                json.add("uv", Json.array(u2, v2));
                json.add("uv_size", Json.array(u1 - u2, v1 - v2));
            }
            case "down" -> {
                json.add("uv", Json.array(u2, v1));
                json.add("uv_size", Json.array(u1 - u2, v2 - v1));
            }
            default -> {
                json.add("uv", Json.array(u1, v1));
                json.add("uv_size", Json.array(u2 - u1, v2 - v1));
            }
        }
        if (material != null) {
            json.addProperty("material_instance", material);
        }
        return json;
    }

    /** Java's {@code rescale} stretches rotated elements back to full size on the two other axes. */
    private static void rescale(float[] from, float[] to, JavaModel.Rotation rotation) {
        float factor = (float) (1 / Math.cos(Math.toRadians(Math.abs(rotation.angle()))));
        int axis = switch (rotation.axis()) {
            case "x" -> 0;
            case "y" -> 1;
            default -> 2;
        };
        for (int i = 0; i < 3; i++) {
            if (i == axis) {
                continue;
            }
            float pivot = rotation.origin()[i];
            from[i] = pivot + (from[i] - pivot) * factor;
            to[i] = pivot + (to[i] - pivot) * factor;
        }
    }
}
