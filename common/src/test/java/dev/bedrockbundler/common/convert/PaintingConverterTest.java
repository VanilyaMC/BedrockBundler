package dev.bedrockbundler.common.convert;

import com.google.gson.JsonObject;
import dev.bedrockbundler.common.Json;
import dev.bedrockbundler.common.bedrock.Textures;
import dev.bedrockbundler.common.input.ConversionInput;
import dev.bedrockbundler.common.input.PaintingVariant;
import dev.bedrockbundler.common.java.JavaPack;
import dev.bedrockbundler.common.spec.ItemSpec;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** ItemsAdderAdditions' custom painting: a 3x3 variant becomes a board item a stand can wear. */
class PaintingConverterTest {

    @Test
    void customPaintingBecomesBoardItem() throws IOException {
        Map<String, byte[]> pack = new HashMap<>();
        BufferedImage texture = new BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB);
        texture.setRGB(1, 1, 0xFF00FF00);
        pack.put("assets/iaadditions/textures/painting/iaadditions.png", Textures.png(texture));

        Converter.Result result = Converter.convert(new ConversionInput(Map.copyOf(pack), List.of(), List.of(), List.of(
                new PaintingVariant("iaadditions:iaadditions", "iaadditions:iaadditions", 3, 3),
                new PaintingVariant("other:missing", "other:missing", 1, 1))),
                ConversionContext.Options.DEFAULT, "Test");

        ItemSpec spec = result.bundle().items().stream().filter(i -> i.bedrockId().contains("painting")).findFirst().orElseThrow();
        assertEquals(1, result.bundle().items().size(), "texture-less variants are skipped");
        assertEquals(PaintingConverter.itemModel("iaadditions:iaadditions"), spec.model());
        assertEquals("bedrockbundler:painting/iaadditions/iaadditions", spec.model());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("other:missing")));

        Map<String, byte[]> mcpack = JavaPack.readZip(new ByteArrayInputStream(result.mcpack()));
        String geometry = mcpack.keySet().stream().filter(p -> p.startsWith("models/entity/"))
                .findFirst().orElseThrow(() -> new AssertionError(mcpack.keySet().toString()));
        JsonObject cube = Json.parse(mcpack.get(geometry)).getAsJsonObject()
                .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones").asList().stream()
                .map(b -> b.getAsJsonObject()).filter(b -> b.has("cubes")).findFirst().orElseThrow()
                .getAsJsonArray("cubes").get(0).getAsJsonObject();
        assertEquals(48f, cube.getAsJsonArray("size").get(0).getAsFloat(), 1e-4, "three blocks wide");
        assertEquals(1f, cube.getAsJsonArray("size").get(2).getAsFloat(), 1e-4, "one pixel thick");
    }

    /** Vanilla motives are cut from the Bedrock client's own painting textures, which are not copied. */
    @Test
    void vanillaPaintingUsesBedrockTexture() throws IOException {
        Converter.Result result = Converter.convert(new ConversionInput(Map.of(), List.of(), List.of(), List.of(
                new PaintingVariant("minecraft:donkey_kong", "minecraft:donkey_kong", 4, 3),
                new PaintingVariant("minecraft:orb", "minecraft:orb", 4, 4),
                new PaintingVariant("minecraft:future_motive", "minecraft:future_motive", 1, 1))),
                ConversionContext.Options.DEFAULT, "Test");
        assertEquals(2, result.bundle().items().size(), "unknown vanilla motives are left to Geyser");
        assertTrue(PaintingConverter.mirrored("minecraft:orb") && !PaintingConverter.mirrored("minecraft:future_motive"));

        Map<String, byte[]> mcpack = JavaPack.readZip(new ByteArrayInputStream(result.mcpack()));
        String attachable = new String(mcpack.get("attachables/bedrockbundler/minecraft_painting_donkey_kong.json"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(attachable.contains("\"textures/painting/kz\""), attachable);
        assertFalse(mcpack.keySet().stream().anyMatch(p -> p.startsWith("textures/bedrockbundler/models/")),
                "no copy of a Bedrock texture");

        JsonObject geometry = mcpack.entrySet().stream()
                .filter(e -> e.getKey().startsWith("models/entity/") && new String(e.getValue()).contains("donkey_kong"))
                .map(e -> Json.parse(e.getValue()).getAsJsonObject()).findFirst().orElseThrow()
                .getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        assertEquals(256, geometry.getAsJsonObject("description").get("texture_width").getAsInt());
        JsonObject north = geometry.getAsJsonArray("bones").asList().stream().map(b -> b.getAsJsonObject())
                .filter(b -> b.has("cubes")).findFirst().orElseThrow().getAsJsonArray("cubes").get(0).getAsJsonObject()
                .getAsJsonObject("uv").getAsJsonObject("north");
        assertEquals(192f, north.getAsJsonArray("uv").get(0).getAsFloat(), 1e-3);
        assertEquals(112f, north.getAsJsonArray("uv").get(1).getAsFloat(), 1e-3);
        assertEquals(64f, north.getAsJsonArray("uv_size").get(0).getAsFloat(), 1e-3);
        assertEquals(48f, north.getAsJsonArray("uv_size").get(1).getAsFloat(), 1e-3);
    }
}
