package dev.bedrockbundler.common.convert;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.bedrockbundler.common.ConversionLog;
import dev.bedrockbundler.common.Json;
import dev.bedrockbundler.common.bedrock.Textures;
import dev.bedrockbundler.common.input.ConversionInput;
import dev.bedrockbundler.common.input.IaItem;
import dev.bedrockbundler.common.java.JavaPack;
import dev.bedrockbundler.common.java.VanillaAssets;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Furniture models often use vanilla block textures that the custom pack does not carry. */
class VanillaTextureTest {

    private static final String PLANKS = "assets/minecraft/textures/block/oak_planks.png";

    private static Map<String, byte[]> pack() {
        Map<String, byte[]> pack = new HashMap<>();
        pack.put("assets/test/items/bench.json",
                "{\"model\": {\"type\": \"model\", \"model\": \"test:item/bench\"}}".getBytes(StandardCharsets.UTF_8));
        pack.put("assets/test/models/item/bench.json", """
                {"textures": {"0": "test:block/cloth", "1": "minecraft:block/oak_planks", "particle": "minecraft:block/oak_planks"},
                 "elements": [
                   {"from": [0, 8, 0], "to": [16, 10, 16], "faces": {"up": {"texture": "#0"}, "north": {"texture": "#0"}}},
                   {"from": [0, 0, 0], "to": [16, 8, 16], "faces": {"up": {"texture": "#1"}, "north": {"texture": "#1"},
                     "south": {"texture": "#1"}}}
                 ]}""".getBytes(StandardCharsets.UTF_8));
        pack.put("assets/test/textures/block/cloth.png", Textures.png(image(0xFFB02020)));
        return pack;
    }

    private static BufferedImage image(int argb) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    private static Converter.Result convert(VanillaAssets vanilla) {
        IaItem bench = new IaItem("test:bench", "minecraft:paper", "test:bench", null, "Bench", null, null, null, null, 0,
                null, false, false);
        return Converter.convert(new ConversionInput(pack(), List.of(bench), List.of()), ConversionContext.Options.DEFAULT,
                "Test", vanilla);
    }

    /** Cubes per bone that have no face UVs: Bedrock does not draw them. */
    private static List<Boolean> textured(Converter.Result result) throws IOException {
        Map<String, byte[]> mcpack = JavaPack.readZip(new ByteArrayInputStream(result.mcpack()));
        JsonObject geometry = Json.parse(mcpack.get("models/entity/bedrockbundler/test_bench.geo.json")).getAsJsonObject()
                .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        List<Boolean> textured = new ArrayList<>();
        for (JsonElement bone : geometry.getAsJsonArray("bones")) {
            if (bone.getAsJsonObject().has("cubes")) {
                for (JsonElement cube : bone.getAsJsonObject().getAsJsonArray("cubes")) {
                    JsonObject uv = cube.getAsJsonObject().getAsJsonObject("uv");
                    textured.add(uv != null && !uv.isEmpty());
                }
            }
        }
        return textured;
    }

    @Test
    void vanillaTextureFromTheClientAssetsIsDrawn() throws IOException {
        Converter.Result result = convert(VanillaAssets.of("1.21.11", Map.of(PLANKS, Textures.png(image(0xFF9C7F4E)))));
        List<Boolean> textured = textured(result);
        assertEquals(2, textured.size());
        assertFalse(textured.contains(false), "every cube has UVs: " + textured);
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("oak_planks")), result.warnings()::toString);
    }

    @Test
    void missingVanillaTextureIsReported() throws IOException {
        Converter.Result result = convert(VanillaAssets.NONE);
        assertTrue(textured(result).contains(false));
        assertTrue(result.warnings().stream().anyMatch(w -> w.startsWith("Missing texture minecraft:block/oak_planks")),
                result.warnings()::toString);
    }

    @Test
    void vanillaVersionIsPartOfTheHash() {
        ConversionInput input = new ConversionInput(pack(), List.of(), List.of());
        assertNotEquals(Converter.hash(input, ConversionContext.Options.DEFAULT, VanillaAssets.NONE),
                Converter.hash(input, ConversionContext.Options.DEFAULT, VanillaAssets.of("1.21.11", Map.of())));
    }

    @Test
    void fontSheetsStayOutOfTheVanillaFallback() {
        ConversionLog log = new ConversionLog();
        VanillaAssets vanilla = VanillaAssets.of("1.21.11", Map.of(
                "assets/minecraft/textures/font/ascii.png", Textures.png(image(0xFFFFFFFF)),
                PLANKS, Textures.png(image(0xFF9C7F4E))));
        Textures textures = new Textures(new JavaPack(Map.of()), vanilla, log);
        assertNull(textures.load("minecraft:font/ascii"));
        assertNotNull(textures.load("minecraft:block/oak_planks"));
    }
}
