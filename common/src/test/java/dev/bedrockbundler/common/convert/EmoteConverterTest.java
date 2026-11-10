package dev.bedrockbundler.common.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bedrockbundler.common.Json;
import dev.bedrockbundler.common.input.ConversionInput;
import dev.bedrockbundler.common.input.EmoteAnimation;
import dev.bedrockbundler.common.java.JavaPack;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** ItemsAdder emotes (baked global keyframes) become Bedrock player animations. */
class EmoteConverterTest {

    /** ItemsAdder's player rig: blocks, pivots as in ItemsAdderAdditions' boogie_bomb. */
    private static final double[][] REST = {
            {0, -0.413, 0}, {0, -1.163, 0}, {0.3219, -0.538, 0}, {-0.3281, -0.538, 0}, {0.1125, -1.163, 0}, {-0.1187, -1.163, 0}};
    private static final String[] NAMES = {"phead_0", "pbody_2", "parm_left_3", "parm_right_4", "pleg_left_1", "pleg_right_5"};
    private static final String[][] PARENTS = {{"pbody_2", "main"}, {"main"}, {"pbody_2", "main"}, {"pbody_2", "main"}, {"main"}, {"main"}};

    /**
     * Frame 0 at rest; frame 1 the body turned 30 degrees about y (ItemsAdder's -y), the head and arms carried
     * along: their global rotation is the body's and their pivots move with it, so they are still relative to
     * the body.
     */
    private static String file() {
        double yaw = 30;
        double[][] m = EmoteConverter.mul(EmoteConverter.mul(EmoteConverter.rz(0), EmoteConverter.ry(-yaw)), EmoteConverter.rx(0));
        StringBuilder bones = new StringBuilder();
        StringBuilder anim = new StringBuilder();
        for (int i = 0; i < NAMES.length; i++) {
            double[] rest = REST[i];
            String parents = "[\"" + String.join("\", \"", PARENTS[i]) + "\"]";
            bones.append(i == 0 ? "" : ",").append("{\"name\": \"").append(NAMES[i]).append("\", \"parents\": ")
                    .append(parents).append(", \"pos\": ").append(vec(rest)).append(", \"rot\": [0, 0, 0]}");
            boolean carried = !NAMES[i].startsWith("pleg");
            double[] pos = rest;
            double rot = 0;
            if (carried) {
                double[] rel = {rest[0] - REST[1][0], rest[1] - REST[1][1], rest[2] - REST[1][2]};
                double[] moved = EmoteConverter.apply(m, rel);
                pos = new double[]{REST[1][0] + moved[0], REST[1][1] + moved[1], REST[1][2] + moved[2]};
                rot = yaw;
            }
            anim.append(i == 0 ? "" : ",").append("\"").append(NAMES[i]).append("\": {\"keyframes\": [")
                    .append("{\"pos\": ").append(vec(rest)).append(", \"rot\": [0, 0, 0]},")
                    .append("{\"pos\": ").append(vec(pos)).append(", \"rot\": [0, ").append(rot).append(", 0]}]}");
        }
        return "{\"bones\": [" + bones + "], \"animations\": [{\"name\": \"spin\", \"loopMode\": \"once\", \"length\": 1,"
                + " \"bones\": {" + anim + "}}], \"rotationMode\": \"precise\"}";
    }

    private static String vec(double[] v) {
        return "[" + v[0] + ", " + v[1] + ", " + v[2] + "]";
    }

    @Test
    void bodyTurnCarriesHeadAndArms() throws IOException {
        Converter.Result result = Converter.convert(new ConversionInput(Map.of(), List.of(), List.of(), List.of(),
                List.of(new EmoteAnimation("test", file()))), ConversionContext.Options.DEFAULT, "Test");
        Map<String, byte[]> mcpack = JavaPack.readZip(new ByteArrayInputStream(result.mcpack()));
        JsonObject animations = Json.parse(mcpack.get(EmoteConverter.FILE)).getAsJsonObject().getAsJsonObject("animations");
        assertTrue(animations.has(EmoteConverter.STOP));
        assertFalse(animations.getAsJsonObject(EmoteConverter.STOP).has("bones"),
                "Bedrock rejects an empty bones object (and with it the whole file)");
        JsonObject bones = animations.getAsJsonObject(EmoteConverter.animation("iaadditions:spin")).getAsJsonObject("bones");
        assertEquals(EmoteConverter.animation("spin"), EmoteConverter.animation("iaadditions:spin"));

        for (String bone : List.of("body", "head", "leftArm", "rightArm", "leftLeg", "rightLeg")) {
            JsonObject b = bones.getAsJsonObject(bone);
            assertVec(b.getAsJsonObject("rotation").getAsJsonArray("0.0000"), 0, 0, 0, bone + " at rest");
            assertVec(b.getAsJsonObject("position").getAsJsonArray("0.0000"), 0, 0, 0, bone + " at rest");
        }
        // The body turns about the vertical axis. With the z mirror between the spaces, ItemsAdder's rotation
        // values are Bedrock's (an emote drawn with -30 here was mirrored in game).
        JsonArray body = bones.getAsJsonObject("body").getAsJsonObject("rotation").getAsJsonArray("0.0500");
        assertVec(body, 0, 30, 0, "body");
        // Turning about y moves no point of the vertical axis, so the neck pivot needs no offset.
        assertVec(bones.getAsJsonObject("body").getAsJsonObject("position").getAsJsonArray("0.0500"), 0, 0, 0, "body offset");
        for (String bone : List.of("head", "leftArm", "rightArm")) {
            assertVec(bones.getAsJsonObject(bone).getAsJsonObject("rotation").getAsJsonArray("0.0500"), 0, 0, 0, bone);
            assertVec(bones.getAsJsonObject(bone).getAsJsonObject("position").getAsJsonArray("0.0500"), 0, 0, 0, bone);
        }
        assertVec(bones.getAsJsonObject("leftLeg").getAsJsonObject("rotation").getAsJsonArray("0.0500"), 0, 0, 0, "leg");
    }

    /** A looping emote keeps its time inside the loop until ended, then plays its end animation. */
    @Test
    void loopingEmoteHasEnd() throws IOException {
        String looping = file().replace("\"loopMode\": \"once\"", "\"loopMode\": \"loop\", \"loopStartTime\": 0, \"loopEndTime\": 0.05");
        Converter.Result result = Converter.convert(new ConversionInput(Map.of(), List.of(), List.of(), List.of(),
                List.of(new EmoteAnimation("test", looping))), ConversionContext.Options.DEFAULT, "Test");
        Map<String, byte[]> mcpack = JavaPack.readZip(new ByteArrayInputStream(result.mcpack()));
        JsonObject animations = Json.parse(mcpack.get(EmoteConverter.FILE)).getAsJsonObject().getAsJsonObject("animations");
        JsonObject main = animations.getAsJsonObject(EmoteConverter.animation("spin"));
        assertTrue(main.get("anim_time_update").getAsString().contains("> 0.0500"), main.toString());
        assertFalse(main.has("loop"), "a whole-animation loop would finish every round");
        JsonObject end = animations.getAsJsonObject(EmoteConverter.end("spin"));
        assertNotNull(end);
        assertFalse(end.has("bones"), "nothing after the loop: an empty end");
    }

    private static void assertVec(JsonArray v, double x, double y, double z, String what) {
        assertEquals(x, v.get(0).getAsDouble(), 0.01, what + " x");
        assertEquals(y, v.get(1).getAsDouble(), 0.01, what + " y");
        assertEquals(z, v.get(2).getAsDouble(), 0.01, what + " z");
    }
}
