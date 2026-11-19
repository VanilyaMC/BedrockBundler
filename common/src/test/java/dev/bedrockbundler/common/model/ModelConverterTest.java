package dev.bedrockbundler.common.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.bedrockbundler.common.ConversionLog;
import dev.bedrockbundler.common.Json;
import dev.bedrockbundler.common.bedrock.BedrockPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class ModelConverterTest {

    private static final String NS = "bedrockbundler";

    private static ModelInput input(String id, JsonObject model) {
        return new ModelInput(id, ModelFixtures.bytes(model));
    }

    private record Run(BedrockPack pack, List<ModelSpec> specs, ConversionLog log) {
        JsonObject json(String path) {
            byte[] bytes = pack.packedFiles().get(path);
            assertNotNull(bytes, "missing " + path + " in " + pack.packedFiles().keySet());
            return Json.parse(bytes).getAsJsonObject();
        }
    }

    private static Run run(ModelInput... inputs) {
        BedrockPack pack = new BedrockPack();
        ConversionLog log = new ConversionLog();
        List<ModelSpec> specs = ModelConverter.convert(List.of(inputs), pack, log);
        return new Run(pack, specs, log);
    }

    private static JsonObject bone(JsonObject geometry, String name) {
        for (JsonElement b : geometry.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones")) {
            if (b.getAsJsonObject().get("name").getAsString().equals(name)) {
                return b.getAsJsonObject();
            }
        }
        return fail("no bone " + name);
    }

    private static List<Double> numbers(JsonElement array) {
        List<Double> list = new ArrayList<>();
        array.getAsJsonArray().forEach(e -> list.add(e.getAsDouble()));
        return list;
    }

    private static String geometryPath(Run run, String id) {
        return run.pack.packedFiles().keySet().stream()
                .filter(p -> p.startsWith("models/entity/bedrockbundler/") && !p.endsWith("_tint.json")).findFirst().orElseThrow();
    }

    // ---- geometry ------------------------------------------------------------------------------------------

    @Test
    void geometryFollowsBlockbenchBedrockCodec() {
        BedrockModel model = BbmodelReader.read("fixture", ModelFixtures.bytes(ModelFixtures.standard()), m -> { });
        JsonObject geometry = model.geometry();
        assertEquals("1.12.0", geometry.get("format_version").getAsString());
        JsonObject body = bone(geometry, "body");
        assertEquals("root", body.get("parent").getAsString());
        assertEquals(List.of(0.0, 8.0, 0.0), numbers(body.get("pivot")));
        assertEquals(List.of(0.0, -90.0, 15.0), numbers(body.get("rotation")), "X and Y rotation change sign");
        JsonObject cube = body.getAsJsonArray("cubes").get(0).getAsJsonObject();
        assertEquals(List.of(-5.0, 0.0, 2.0), numbers(cube.get("origin")), "X is mirrored: -to.x");
        assertEquals(List.of(4.0, 8.0, 4.0), numbers(cube.get("size")));
        assertEquals(List.of(-3.0, 4.0, 5.0), numbers(cube.get("pivot")));
        assertEquals(List.of(-10.0, -20.0, 30.0), numbers(cube.get("rotation")));
        JsonObject uv = cube.getAsJsonObject("uv");
        assertEquals(List.of(0.0, 0.0), numbers(uv.getAsJsonObject("north").get("uv")));
        assertEquals(List.of(4.0, 4.0), numbers(uv.getAsJsonObject("north").get("uv_size")));
        assertEquals(List.of(4.0, 4.0), numbers(uv.getAsJsonObject("up").get("uv")), "up face UV is flipped");
        assertEquals(List.of(-4.0, -4.0), numbers(uv.getAsJsonObject("down").get("uv_size")));
        assertEquals("geometry.fixture", geometry.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                .getAsJsonObject("description").get("identifier").getAsString());
        assertEquals(1, model.textures().size());
        assertEquals(Set.of("body", "h_head"), model.textures().get(0).bindingBones());
    }

    @Test
    void faceRotationSelectsNewerFormat() {
        JsonObject m = ModelFixtures.standard();
        m.getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("faces").getAsJsonObject("north")
                .addProperty("rotation", 90);
        BedrockModel model = BbmodelReader.read("fixture", ModelFixtures.bytes(m), x -> { });
        assertEquals("1.21.0", model.geometry().get("format_version").getAsString());
        assertEquals(90, bone(model.geometry(), "body").getAsJsonArray("cubes").get(0).getAsJsonObject()
                .getAsJsonObject("uv").getAsJsonObject("north").get("uv_rotation").getAsInt());
    }

    // ---- animations ----------------------------------------------------------------------------------------

    @Test
    void animationsKeepChannelsSignsInterpolationAndLoopModes() {
        Run run = run(input("fixture", ModelFixtures.standard()));
        JsonObject animations = run.json("animations/bedrockbundler/fixture.json").getAsJsonObject("animations");
        JsonObject walk = animations.getAsJsonObject("animation.bb_fixture.walk_cycle");
        assertTrue(walk.get("loop").getAsBoolean());
        assertEquals(1, walk.get("animation_length").getAsInt());
        JsonObject body = walk.getAsJsonObject("bones").getAsJsonObject("body");
        JsonObject rotation = body.getAsJsonObject("rotation");
        assertEquals("catmullrom", rotation.getAsJsonObject("0").get("lerp_mode").getAsString());
        assertEquals(List.of(0.0, -10.0, 5.0), numbers(rotation.getAsJsonObject("0").get("post")), "rotation X/Y negated");
        assertEquals(List.of(-45.0, 20.0, 0.0), numbers(rotation.getAsJsonObject("0.5").get("post")));
        JsonObject position = body.getAsJsonObject("position");
        assertEquals(List.of(-1.0, 2.0, 3.0), numbers(position.get("0")), "plain linear keyframes are bare arrays");
        assertEquals("-(math.sin(q.life_time * 90))", position.getAsJsonArray("0.5").get(0).getAsString(),
                "Molang is negated as text");
        assertEquals(0.0, position.getAsJsonArray("0.5").get(1).getAsDouble(), "an empty component is 0");
        assertEquals(List.of(1.0, 1.5, 1.0), numbers(body.getAsJsonObject("scale").get("0.5")));

        JsonObject wave = animations.getAsJsonObject("animation.bb_fixture.wave").getAsJsonObject("bones")
                .getAsJsonObject("body").getAsJsonObject("rotation");
        assertEquals("hold_on_last_frame", animations.getAsJsonObject("animation.bb_fixture.wave").get("loop").getAsString());
        assertEquals("catmullrom", wave.getAsJsonObject("0").get("lerp_mode").getAsString());
        assertEquals("linear", wave.getAsJsonObject("1").get("lerp_mode").getAsString(),
                "the held last keyframe must not curve");

        JsonObject emote = animations.getAsJsonObject("animation.bb_fixture.emote");
        assertEquals("hold_on_last_frame", emote.get("loop").getAsString(), "an override animation holds its last frame");
        assertFalse(emote.has("override_previous_animation"));

        JsonObject head = animations.getAsJsonObject("animation.bb_fixture.look_at_target");
        assertTrue(head.getAsJsonObject("bones").has("h_head"), "h_ bones follow the head rotation");
    }

    @Test
    void animationControllersUseSortedBitOrder() {
        Run run = run(input("fixture", ModelFixtures.standard()));
        ModelSpec spec = run.specs.get(0);
        assertEquals(List.of("emote", "walk_cycle", "wave"), spec.animations());
        JsonObject controllers = run.json("animation_controllers/bedrockbundler/fixture.json")
                .getAsJsonObject("animation_controllers");
        assertEquals(3, controllers.size());
        JsonObject walk = controllers.getAsJsonObject("controller.animation.bb_fixture.walk_cycle");
        assertEquals("stop", walk.get("initial_state").getAsString());
        JsonObject play = walk.getAsJsonObject("states").getAsJsonObject("play");
        assertEquals("walk_cycle", play.getAsJsonArray("animations").get(0).getAsString());
        assertEquals("math.mod(math.floor(query.property('bedrockbundler:anim0') / 2), 2) == 0",
                play.getAsJsonArray("transitions").get(0).getAsJsonObject().get("stop").getAsString());
        assertEquals("math.mod(math.floor(query.property('bedrockbundler:anim0') / 4), 2) != 0",
                controllers.getAsJsonObject("controller.animation.bb_fixture.wave").getAsJsonObject("states")
                        .getAsJsonObject("stop").getAsJsonArray("transitions").get(0).getAsJsonObject().get("play").getAsString());
    }

    // ---- entity, render controller, spec -------------------------------------------------------------------

    @Test
    void entityAndRenderControllersReferenceEachOther() {
        Run run = run(input("fixture", ModelFixtures.standard()));
        assertEquals(1, run.specs.size());
        assertTrue(run.log.warnings().isEmpty(), run.log.warnings().toString());
        JsonObject description = run.json("entity/bedrockbundler/fixture.json").getAsJsonObject("minecraft:client_entity")
                .getAsJsonObject("description");
        assertEquals("bedrockbundler:fixture", description.get("identifier").getAsString());
        String texture = ShortHash.textureName("fixture", "body");
        String geometry = ShortHash.modelId("fixture_16_16");
        assertEquals("textures/bedrockbundler/entity/" + texture, description.getAsJsonObject("textures").get(texture).getAsString());
        assertEquals("geometry." + geometry, description.getAsJsonObject("geometry").get(geometry).getAsString());
        assertEquals("geometry." + geometry + "_tint", description.getAsJsonObject("geometry").get(geometry + "_tint").getAsString());
        assertEquals("animation.bb_fixture.look_at_target", description.getAsJsonObject("animations").get("look_at_target").getAsString());
        assertEquals("controller.animation.bb_fixture.wave", description.getAsJsonObject("animations").get("wave_control").getAsString());
        assertEquals("animation.bb_fixture.manual_root", description.getAsJsonObject("animations").get("manual_root").getAsString());
        JsonArray animate = description.getAsJsonObject("scripts").getAsJsonArray("animate");
        assertEquals("look_at_target", animate.get(0).getAsString());
        assertEquals("emote_control", animate.get(1).getAsString());
        assertEquals("query.property('bedrockbundler:manual_on') == 1",
                animate.get(animate.size() - 1).getAsJsonObject().get("manual_root").getAsString());
        assertEquals(List.of("controller.render.bb_fixture_body", "controller.render.bb_fixture_body_tint", "controller.render.bb_fixture_body_tintblend"),
                strings(description.getAsJsonArray("render_controllers")));

        // every referenced file exists
        Map<String, byte[]> files = run.pack.packedFiles();
        assertTrue(files.containsKey("textures/bedrockbundler/entity/" + texture + ".png"));
        assertTrue(files.containsKey("textures/bedrockbundler/entity/" + texture + "_tintlum.png"));
        assertTrue(files.containsKey("models/entity/bedrockbundler/" + geometry + ".json"));
        assertTrue(files.containsKey("models/entity/bedrockbundler/" + geometry + "_tint.json"));
        assertTrue(files.containsKey("materials/entity.material"));
        assertTrue(files.containsKey("animations/bedrockbundler/fixture_manual.json"));

        JsonObject controllers = run.json("render_controllers/bedrockbundler/fixture.json").getAsJsonObject("render_controllers");
        JsonObject base = controllers.getAsJsonObject("controller.render.bb_fixture_body");
        assertEquals("Geometry." + geometry, base.get("geometry").getAsString());
        assertEquals("Texture." + texture, base.getAsJsonArray("textures").get(0).getAsString());
        assertEquals("Material.default", base.getAsJsonArray("materials").get(0).getAsJsonObject().get("*").getAsString());
        JsonArray visibility = base.getAsJsonArray("part_visibility");
        assertFalse(visibility.get(0).getAsJsonObject().get("*").getAsBoolean());
        assertEquals(3, visibility.size(), "star, body and h_head (root has no cubes)");
        assertEquals("(math.mod(math.floor(query.property('bedrockbundler:bone0') / 1), 2) == 0) && !(math.mod(math.floor(query.property('bedrockbundler:tintmask0') / 1), 2) == 1)",
                visibility.get(1).getAsJsonObject().get("body").getAsString());
        assertEquals("(math.mod(math.floor(query.property('bedrockbundler:bone0') / 2), 2) == 0) && !(math.mod(math.floor(query.property('bedrockbundler:tintmask0') / 2), 2) == 1)",
                visibility.get(2).getAsJsonObject().get("h_head").getAsString());
        JsonObject tint = controllers.getAsJsonObject("controller.render.bb_fixture_body_tint");
        assertEquals(0.0, tint.getAsJsonObject("overlay_color").get("r").getAsDouble());
        assertTrue(tint.getAsJsonArray("part_visibility").get(1).getAsJsonObject().get("body").getAsString().contains("&& (math.mod("));
        JsonObject blend = controllers.getAsJsonObject("controller.render.bb_fixture_body_tintblend");
        assertEquals("Geometry." + geometry + "_tint", blend.get("geometry").getAsString());
        assertEquals("Texture." + texture + "_tintlum", blend.getAsJsonArray("textures").get(0).getAsString());
        assertEquals("Material.tintblend", blend.getAsJsonArray("materials").get(0).getAsJsonObject().get("*").getAsString());
        assertTrue(blend.getAsJsonObject("overlay_color").get("r").getAsString().startsWith("math.floor(query.property('bedrockbundler:tintcolor')"));

        JsonObject tintGeometry = run.json("models/entity/bedrockbundler/" + geometry + "_tint.json");
        JsonObject tintCube = bone(tintGeometry, "body").getAsJsonArray("cubes").get(0).getAsJsonObject();
        assertEquals(0.1, tintCube.get("inflate").getAsDouble(), 1e-9, "tint geometry is inflated a little");
    }

    @Test
    void specListsBitOrderAndProperties() {
        Run run = run(input("fixture", ModelFixtures.standard()));
        ModelSpec spec = run.specs.get(0);
        assertEquals("fixture", spec.id());
        assertEquals("bedrockbundler:fixture", spec.entity());
        assertEquals(List.of("body", "h_head", "root"), spec.bones(), "sorted, cube-less bones keep their bit");
        assertEquals(0.02f, spec.width());
        assertEquals(0.02f, spec.height());
        List<String> names = spec.properties().stream().map(ModelSpec.Property::name).toList();
        assertEquals(List.of("bedrockbundler:bone0", "bedrockbundler:anim0", "bedrockbundler:tintmask0",
                "bedrockbundler:tintcolor", "bedrockbundler:manual_on", "bedrockbundler:manual_rx",
                "bedrockbundler:manual_ry", "bedrockbundler:manual_rz", "bedrockbundler:manual_px",
                "bedrockbundler:manual_py", "bedrockbundler:manual_pz"), names);
        ModelSpec.Property bone0 = spec.properties().get(0);
        assertEquals(0, bone0.min());
        assertEquals((1 << ModelSpec.BITS_PER_PROPERTY) - 1, bone0.max(), "a full mask fits");
        assertEquals(0, bone0.defaultValue());
        assertTrue(spec.properties().size() <= 32);
        // Gson round trip (the spec goes into bundle.json)
        ModelSpec back = Json.GSON.fromJson(Json.GSON.toJson(spec), ModelSpec.class);
        assertEquals(spec, back);
        assertEquals("head_bone", ModelSpec.boneKey("Head Bone"));
        assertEquals("walk_cycle", ModelSpec.animationKey("walk cycle"));
    }

    @Test
    void modelsWithoutRootBoneAndAnimationsGetFewerProperties() {
        JsonObject m = ModelFixtures.model(new JsonObject[]{ModelFixtures.texture("t.png", ModelFixtures.png(255, 0xAA0000))},
                new JsonObject[]{ModelFixtures.cube("c", new double[]{0, 0, 0}, new double[]{4, 4, 4}, 0)},
                new JsonObject[]{ModelFixtures.group("g", "Main Part", new double[]{0, 0, 0}, "c")});
        Run run = run(input("simple", m));
        ModelSpec spec = run.specs.get(0);
        assertEquals(List.of("main_part"), spec.bones(), "bone names are normalised");
        assertTrue(spec.animations().isEmpty());
        assertEquals(List.of("bedrockbundler:bone0", "bedrockbundler:tintmask0", "bedrockbundler:tintcolor"),
                spec.properties().stream().map(ModelSpec.Property::name).toList());
        assertFalse(run.pack.has("animations/bedrockbundler/simple.json"));
        assertFalse(run.pack.packedFiles().keySet().stream().anyMatch(p -> p.startsWith("animation_controllers/")));
        JsonObject description = run.json("entity/bedrockbundler/simple.json").getAsJsonObject("minecraft:client_entity")
                .getAsJsonObject("description");
        assertEquals("animation.common.look_at_target", description.getAsJsonObject("animations").get("look_at_target").getAsString());
    }

    // ---- textures: translucency and several textures -------------------------------------------------------

    @Test
    void semiTransparentTextureUsesBlendMaterial() {
        JsonObject m = ModelFixtures.model(new JsonObject[]{ModelFixtures.texture("glass.png", ModelFixtures.png(100, 0x88CCFF))},
                new JsonObject[]{ModelFixtures.cube("c", new double[]{0, 0, 0}, new double[]{4, 4, 4}, 0)},
                new JsonObject[]{ModelFixtures.group("g", "pane", new double[]{0, 0, 0}, "c")});
        Run run = run(input("glass", m));
        JsonObject controller = run.json("render_controllers/bedrockbundler/glass.json").getAsJsonObject("render_controllers")
                .getAsJsonObject("controller.render.bb_glass");
        assertEquals("Material.blend", controller.getAsJsonArray("materials").get(0).getAsJsonObject().get("*").getAsString());
    }

    @Test
    void glassCubesOfAMixedBoneMoveToATranslucentChildBone() {
        JsonObject opaque = ModelFixtures.cube("a", new double[]{0, 0, 0}, new double[]{4, 4, 4}, 0);
        JsonObject glass = ModelFixtures.cube("b", new double[]{4, 0, 0}, new double[]{8, 4, 4}, 0);
        for (String face : ModelFixtures.FACES) {
            glass.getAsJsonObject("faces").getAsJsonObject(face).add("uv", ModelFixtures.array(new double[]{10, 0, 14, 4}));
        }
        JsonObject m = ModelFixtures.model(new JsonObject[]{ModelFixtures.texture("mix.png", ModelFixtures.halfPng(100))},
                new JsonObject[]{opaque, glass}, new JsonObject[]{ModelFixtures.group("g", "lamp", new double[]{0, 0, 0}, "a", "b")});
        Run run = run(input("mix", m));
        JsonObject geometry = run.json(geometryPath(run, "mix"));
        JsonObject lamp = bone(geometry, "lamp");
        JsonObject lampGlass = bone(geometry, "lamp_t");
        assertEquals(1, lamp.getAsJsonArray("cubes").size());
        assertEquals(1, lampGlass.getAsJsonArray("cubes").size());
        assertEquals("lamp", lampGlass.get("parent").getAsString());
        JsonObject controller = run.json("render_controllers/bedrockbundler/mix.json").getAsJsonObject("render_controllers")
                .getAsJsonObject("controller.render.bb_mix");
        assertEquals("Material.blend", controller.getAsJsonArray("materials").get(1).getAsJsonObject().get("lamp_t").getAsString());
        String lampExpression = null;
        String glassExpression = null;
        for (JsonElement e : controller.getAsJsonArray("part_visibility")) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("lamp")) lampExpression = o.get("lamp").getAsString();
            if (o.has("lamp_t")) glassExpression = o.get("lamp_t").getAsString();
        }
        assertNotNull(glassExpression, "the glass bone follows its parent's visibility");
        assertEquals(lampExpression, glassExpression);
        assertEquals(List.of("lamp"), run.specs.get(0).bones(), "the split bone has no bit of its own");
    }

    @Test
    void cubeWithTwoTexturesSplitsIntoUvBonesThatFollowTheirParent() {
        JsonObject cube = ModelFixtures.cube("c", new double[]{0, 0, 0}, new double[]{4, 4, 4}, 0);
        for (String face : new String[]{"up", "down", "west"}) {
            cube.getAsJsonObject("faces").getAsJsonObject(face).addProperty("texture", 1);
        }
        JsonObject second = ModelFixtures.texture("second.png", ModelFixtures.png(255, 0x00AA00));
        second.addProperty("uv_width", 32);
        second.addProperty("uv_height", 32);
        JsonObject m = ModelFixtures.model(
                new JsonObject[]{ModelFixtures.texture("first.png", ModelFixtures.png(255, 0xAA0000)), second},
                new JsonObject[]{cube}, new JsonObject[]{ModelFixtures.group("g", "zombie", new double[]{0, 0, 0}, "c")});
        Run run = run(input("two", m));
        assertEquals(List.of("zombie"), run.specs.get(0).bones());
        List<String> geometries = run.pack.packedFiles().keySet().stream()
                .filter(p -> p.startsWith("models/entity/") && !p.endsWith("_tint.json")).toList();
        assertEquals(2, geometries.size(), "one geometry per texture UV size");
        JsonObject description = run.json("entity/bedrockbundler/two.json").getAsJsonObject("minecraft:client_entity")
                .getAsJsonObject("description");
        assertEquals(6, description.getAsJsonArray("render_controllers").size());
        JsonObject controllers = run.json("render_controllers/bedrockbundler/two.json").getAsJsonObject("render_controllers");
        JsonObject firstUv = null;
        for (JsonElement e : controllers.getAsJsonObject("controller.render.bb_two_first").getAsJsonArray("part_visibility")) {
            if (e.getAsJsonObject().has("uv_zombie0")) {
                firstUv = e.getAsJsonObject();
            }
        }
        assertNotNull(firstUv);
        assertTrue(firstUv.get("uv_zombie0").getAsString().contains("bedrockbundler:bone0') / 1)"),
                "the uv bone shows with its parent's bit");
        JsonObject secondGeometry = run.json("models/entity/bedrockbundler/" + ShortHash.modelId("two_32_32") + ".json");
        assertEquals(32, secondGeometry.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                .getAsJsonObject("description").get("texture_width").getAsInt());
        assertEquals(1, bone(secondGeometry, "uv_zombie1").getAsJsonArray("cubes").size());
    }

    // ---- bad input -----------------------------------------------------------------------------------------

    @Test
    void badModelsAreSkippedWithAWarning() {
        JsonObject noCubes = ModelFixtures.model(new JsonObject[]{ModelFixtures.texture("t.png", ModelFixtures.png(255, 0))},
                new JsonObject[]{}, new JsonObject[]{ModelFixtures.group("g", "empty", new double[]{0, 0, 0})});
        JsonObject noTexture = ModelFixtures.model(new JsonObject[]{},
                new JsonObject[]{ModelFixtures.cube("c", new double[]{0, 0, 0}, new double[]{1, 1, 1}, 0)},
                new JsonObject[]{ModelFixtures.group("g", "x", new double[]{0, 0, 0}, "c")});
        Run run = run(input("good", ModelFixtures.standard()), input("no_cubes", noCubes), input("no_texture", noTexture),
                input("Bad-Id", ModelFixtures.standard()), new ModelInput("broken", "{nope".getBytes()),
                input("good", ModelFixtures.standard()));
        assertEquals(List.of("good"), run.specs.stream().map(ModelSpec::id).toList());
        List<String> warnings = run.log.warnings();
        assertEquals(5, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("no_cubes") && w.contains("no cubes")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("no_texture") && w.contains("no texture")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Bad-Id")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("broken")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("duplicate")));
        assertFalse(run.pack.packedFiles().keySet().stream().anyMatch(p -> p.contains("no_cubes")), "nothing written for a skipped model");
    }

    @Test
    void tooManyPropertiesRejectsTheModelAndTintsFallBack() {
        int bits = ModelSpec.BITS_PER_PROPERTY;
        // far too many bones: two masks per 'bits' bones plus the animation words exceed 32
        assertEquals(0, run(input("huge", manyBones(bits * 17, false))).specs.size());
        // 14 words of bones with a root bone: bone masks alone fit, bone masks + tint masks do not
        Run run = run(input("big", manyBones(bits * 13 + 1, true)));
        assertEquals(1, run.specs.size());
        assertTrue(run.log.warnings().stream().anyMatch(w -> w.contains("tints disabled")));
        assertFalse(run.specs.get(0).properties().stream().anyMatch(p -> p.name().contains("tint")));
        assertTrue(run.specs.get(0).properties().size() <= 32);
        assertFalse(run.pack.packedFiles().keySet().stream().anyMatch(p -> p.endsWith("_tint.json")));
    }

    private static JsonObject manyBones(int count, boolean withRoot) {
        JsonObject[] elements = new JsonObject[count];
        JsonObject[] groups = new JsonObject[count];
        for (int i = 0; i < count; i++) {
            elements[i] = ModelFixtures.cube("c" + i, new double[]{0, 0, 0}, new double[]{1, 1, 1}, 0);
            groups[i] = ModelFixtures.group("g" + i, withRoot && i == 0 ? "root" : "b" + i, new double[]{0, 0, 0}, "c" + i);
        }
        return ModelFixtures.model(new JsonObject[]{ModelFixtures.texture("t.png", ModelFixtures.png(255, 0x123456))},
                elements, groups, ModelFixtures.animation("a", "loop", false, 1, "g1", "b1",
                        ModelFixtures.keyframe("rotation", 0, "linear", "0", "0", "0")));
    }

    // ---- determinism and path length ------------------------------------------------------------------------

    @Test
    void sameInputGivesIdenticalBytes() {
        ModelInput a = input("fixture", ModelFixtures.standard());
        ModelInput b = input("another", ModelFixtures.standard());
        Run first = run(a, b);
        Run second = run(b, a);
        Map<String, byte[]> x = first.pack.packedFiles();
        Map<String, byte[]> y = second.pack.packedFiles();
        assertEquals(x.keySet(), y.keySet());
        x.forEach((path, bytes) -> assertArrayEquals(bytes, y.get(path), path));
        assertEquals(first.specs, second.specs);
        byte[] zip1 = first.pack.build("models", "test", new int[]{1, 0, 0});
        byte[] zip2 = second.pack.build("models", "test", new int[]{1, 0, 0});
        assertArrayEquals(zip1, zip2, "the whole .mcpack is reproducible");
    }

    @Test
    void definitionFilePathsStayUnder80Characters() throws IOException {
        String id = "a_very_long_model_identifier_".repeat(2) + "end";
        assertTrue(id.length() > 55);
        Run run = run(input(id, ModelFixtures.standard()));
        assertEquals(1, run.specs.size());
        for (String path : run.pack.packedFiles().keySet()) {
            assertTrue(path.length() < 80, path.length() + " chars: " + path);
        }
        assertTrue(run.pack.packedFiles().keySet().stream().anyMatch(p -> p.startsWith("entity/") && !p.contains("bedrockbundler")),
                "the long client entity path is replaced by a hash");
        // and the references inside still point at what is in the pack
        byte[] zip = run.pack.build("models", "test", new int[]{1, 0, 0});
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                assertTrue(e.getName().length() < 80, e.getName());
            }
        }
    }

    @Test
    void gmeLayoutMatchesTheExtensionsFileNames() {
        BedrockPack pack = new BedrockPack();
        ModelConverter.convert(List.of(input("fixture", ModelFixtures.standard())), pack, new ConversionLog(), ModelOptions.gmeReference());
        Set<String> paths = pack.packedFiles().keySet();
        assertTrue(paths.contains("entity/fixture.json"));
        assertTrue(paths.contains("render_controllers/fixture.json"));
        assertTrue(paths.contains("animation_controllers/fixture.json"));
        assertTrue(paths.contains("animations/fixture.json"));
        assertTrue(paths.contains("animations/fixture_manual.json"));
        assertTrue(paths.stream().anyMatch(p -> p.matches("textures/entity/gm_[a-z0-9_]{7}\\.png")));
        JsonObject description = Json.parse(pack.packedFiles().get("entity/fixture.json")).getAsJsonObject()
                .getAsJsonObject("minecraft:client_entity").getAsJsonObject("description");
        assertEquals("modelengine:fixture", description.get("identifier").getAsString());
        assertEquals("animation.fixture.look_at_target", description.getAsJsonObject("animations").get("look_at_target").getAsString());
    }

    @Test
    void convertToDirectoryDumpsPackAndIntermediateFiles(@TempDir Path dir) throws IOException {
        List<ModelSpec> specs = ModelConverter.convertToDirectory(List.of(input("fixture", ModelFixtures.standard())), dir,
                new ConversionLog(), ModelOptions.defaults());
        assertEquals(1, specs.size());
        assertTrue(Files.exists(dir.resolve("pack/entity/bedrockbundler/fixture.json")));
        assertTrue(Files.exists(dir.resolve("intermediate/fixture/fixture.geo.json")));
        assertTrue(Files.exists(dir.resolve("intermediate/fixture/fixture.animation.json")));
        assertTrue(Files.exists(dir.resolve("intermediate/fixture/config.json")));
        assertTrue(Files.exists(dir.resolve("intermediate/fixture/body.png")));
        assertTrue(Files.exists(dir.resolve("specs.json")));
        JsonObject config = Json.parse(Files.readAllBytes(dir.resolve("intermediate/fixture/config.json"))).getAsJsonObject();
        assertFalse(config.get("disable_part_visibility").getAsBoolean());
        assertEquals(Arrays.asList(16.0, 16.0), numbers(config.getAsJsonObject("per_texture_uv_size").get("body")));
    }

    @Test
    void jsonDiffNoticesDifferencesAndToleratesNoise() {
        JsonObject a = Json.parse("{\"x\":[1,2.000001],\"list\":[{\"n\":1},{\"n\":2}]}".getBytes()).getAsJsonObject();
        JsonObject b = Json.parse("{\"x\":[1,2.000002],\"list\":[{\"n\":2},{\"n\":1}]}".getBytes()).getAsJsonObject();
        assertTrue(JsonDiff.diff(a, b, 1e-5, Set.of("list")).isEmpty());
        assertFalse(JsonDiff.diff(a, b, 1e-5, Set.of()).isEmpty(), "order matters unless allowed");
        JsonObject c = Json.parse("{\"x\":[1,2.1],\"list\":[{\"n\":1},{\"n\":2}]}".getBytes()).getAsJsonObject();
        assertFalse(JsonDiff.diff(a, c, 1e-5, Set.of("list")).isEmpty());
    }

    private static List<String> strings(JsonArray array) {
        List<String> list = new ArrayList<>();
        array.forEach(e -> list.add(e.getAsString()));
        return list;
    }
}
