package dev.bedrockbundler.common.convert;

import com.google.gson.JsonObject;
import dev.bedrockbundler.common.Json;
import dev.bedrockbundler.common.bedrock.Textures;
import dev.bedrockbundler.common.input.ConversionInput;
import dev.bedrockbundler.common.input.IaBlock;
import dev.bedrockbundler.common.input.IaItem;
import dev.bedrockbundler.common.java.JavaPack;
import dev.bedrockbundler.common.spec.BlockSpec;
import dev.bedrockbundler.common.spec.Bundle;
import dev.bedrockbundler.common.spec.ItemSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ConverterTest {

    private static Converter.Result result;
    private static Map<String, byte[]> mcpack;

    @BeforeAll
    static void convert() throws IOException {
        Map<String, byte[]> pack = new HashMap<>();
        // 2D item via item_model
        json(pack, "assets/test/items/ruby.json", "{\"model\": {\"type\": \"minecraft:model\", \"model\": \"test:item/ruby\"}}");
        json(pack, "assets/test/models/item/ruby.json", "{\"parent\": \"minecraft:item/generated\", \"textures\": {\"layer0\": \"test:item/ruby\"}}");
        png(pack, "assets/test/textures/item/ruby.png", 16, 0xFFE0115F);
        // handheld tool
        json(pack, "assets/test/items/ruby_sword.json", "{\"model\": {\"type\": \"model\", \"model\": \"test:item/ruby_sword\"}}");
        json(pack, "assets/test/models/item/ruby_sword.json", "{\"parent\": \"item/handheld\", \"textures\": {\"layer0\": \"test:item/ruby\"}}");
        // 3D item, two textures, one rotated element
        json(pack, "assets/test/items/table.json", "{\"model\": {\"type\": \"model\", \"model\": \"test:item/table\"}}");
        json(pack, "assets/test/models/item/table.json", """
                {"textures": {"0": "test:block/wood", "1": "test:block/leg"},
                 "elements": [
                   {"from": [0, 12, 0], "to": [16, 14, 16], "faces": {"up": {"texture": "#0"}, "down": {"texture": "#0"},
                     "north": {"texture": "#0"}, "south": {"texture": "#0"}, "east": {"texture": "#0"}, "west": {"texture": "#0"}}},
                   {"from": [7, 0, 7], "to": [9, 12, 9], "rotation": {"origin": [8, 6, 8], "axis": "y", "angle": 22.5},
                    "faces": {"north": {"uv": [0, 0, 2, 12], "texture": "#1"}, "south": {"uv": [0, 0, 2, 12], "texture": "#1"}}}
                 ],
                 "display": {"gui": {"rotation": [30, 225, 0], "scale": [0.6, 0.6, 0.6]},
                             "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]}}}""");
        png(pack, "assets/test/textures/block/wood.png", 16, 0xFF9C6B30);
        png(pack, "assets/test/textures/block/leg.png", 32, 0xFF5A3A1A);
        // legacy CustomModelData on paper
        json(pack, "assets/minecraft/items/paper.json", """
                {"model": {"type": "range_dispatch", "property": "custom_model_data",
                  "fallback": {"type": "model", "model": "minecraft:item/paper"},
                  "entries": [{"threshold": 10001, "model": {"type": "model", "model": "test:item/coin"}}]}}""");
        json(pack, "assets/test/models/item/coin.json", "{\"parent\": \"item/generated\", \"textures\": {\"layer0\": \"test:item/coin\"}}");
        png(pack, "assets/test/textures/item/coin.png", 16, 0xFFFFD700);
        // blocks
        json(pack, "assets/minecraft/blockstates/note_block.json", """
                {"variants": {
                  "instrument=harp": {"model": "block/original/note_block"},
                  "powered=false,note=3,instrument=banjo": {"model": "test:block/ruby_ore"},
                  "instrument=banjo,note=4,powered=false": {"model": "test:block/ruby_slab", "y": 90}
                }}""");
        json(pack, "assets/test/models/block/ruby_ore.json", "{\"parent\": \"block/cube_all\", \"textures\": {\"all\": \"test:block/ruby_ore\"}}");
        png(pack, "assets/test/textures/block/ruby_ore.png", 16, 0xFF777777);
        json(pack, "assets/test/models/block/ruby_slab.json", """
                {"parent": "block/block", "textures": {"top": "test:block/ruby_ore", "side": "test:block/wood"},
                 "elements": [{"from": [0, 0, 0], "to": [16, 8, 16], "faces": {
                   "up": {"texture": "#top"}, "down": {"texture": "#top"}, "north": {"texture": "#side"},
                   "south": {"texture": "#side"}, "east": {"texture": "#side"}, "west": {"texture": "#side"}}}]}""");
        // armor
        json(pack, "assets/test/items/ruby_helmet.json", "{\"model\": {\"type\": \"model\", \"model\": \"test:item/ruby_helmet\"}}");
        json(pack, "assets/test/models/item/ruby_helmet.json", "{\"parent\": \"item/generated\", \"textures\": {\"layer0\": \"test:item/ruby\"}}");
        json(pack, "assets/test/equipment/ruby.json", "{\"layers\": {\"humanoid\": [{\"texture\": \"test:ruby\"}]}}");
        png(pack, "assets/test/textures/entity/equipment/humanoid/ruby.png", 64, 0xFFE0115F);

        List<IaItem> items = List.of(
                item("test:ruby", "minecraft:paper", "test:ruby", null, null, null),
                item("test:ruby_sword", "minecraft:diamond_sword", "test:ruby_sword", null, null, null),
                item("test:table", "minecraft:paper", "test:table", null, null, null),
                item("test:coin", "minecraft:paper", null, 10001, null, null),
                item("test:ruby_helmet", "minecraft:leather_helmet", "test:ruby_helmet", null, "head", "test:ruby"),
                item("test:broken", "minecraft:paper", "test:does_not_exist", null, null, null));
        List<IaBlock> blocks = List.of(
                new IaBlock("test:ruby_ore", "minecraft:note_block[powered=false,instrument=banjo,note=3]", "Ruby Ore", 3, 0),
                new IaBlock("test:ruby_slab", "note_block[instrument=banjo,note=4,powered=false]", "Ruby Slab", 2, 7));

        result = Converter.convert(new ConversionInput(pack, items, blocks), ConversionContext.Options.DEFAULT, "BedrockBundler");
        mcpack = JavaPack.readZip(new ByteArrayInputStream(result.mcpack()));

        // Keep the output around for manual inspection.
        Path out = Path.of("build", "test-output");
        result.bundle().write(out, result.mcpack(), result.mappings());
        for (Map.Entry<String, byte[]> entry : mcpack.entrySet()) {
            Path file = out.resolve("mcpack").resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }
    }

    private static IaItem item(String id, String material, String model, Integer cmd, String slot, String asset) {
        return new IaItem(id, material, model, cmd, id, null, null, slot, asset, slot == null ? 0 : 3, null, false, false);
    }

    private static void json(Map<String, byte[]> pack, String path, String json) {
        pack.put(path, json.getBytes(StandardCharsets.UTF_8));
    }

    private static void png(Map<String, byte[]> pack, String path, int size, int argb) {
        BufferedImage image = new BufferedImage(size, size / (size == 64 ? 2 : 1), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                // a darker border makes faces distinguishable in the rendered icon
                boolean border = x == 0 || y == 0 || x == image.getWidth() - 1 || y == image.getHeight() - 1;
                image.setRGB(x, y, border ? argb & 0xFF7F7F7F : argb);
            }
        }
        pack.put(path, Textures.png(image));
    }

    private static Map<String, ItemSpec> items() {
        return result.bundle().items().stream().collect(Collectors.toMap(ItemSpec::bedrockId, Function.identity()));
    }

    @Test
    void convertsAllResolvableItems() {
        Map<String, ItemSpec> items = items();
        assertEquals(6, items.size(), () -> "items: " + items.keySet() + " warnings: " + result.warnings());
        // Unresolvable models still map, with a placeholder icon, so the problem is visible in game.
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("test:broken")));
        assertEquals(List.of("No model found for item test:broken; it gets a placeholder icon"), result.warnings());
    }

    @Test
    void flatAndHandheldItems() {
        ItemSpec ruby = items().get("bedrockbundler:test_ruby");
        assertEquals("test:ruby", ruby.model());
        assertNull(ruby.customModelData());
        assertFalse(ruby.handheld());
        assertTrue(items().get("bedrockbundler:test_ruby_sword").handheld());
        assertTrue(mcpack.containsKey("textures/bedrockbundler/items/test_ruby.png"));
        JsonObject itemTexture = Json.parse(mcpack.get("textures/item_texture.json")).getAsJsonObject();
        assertTrue(itemTexture.getAsJsonObject("texture_data").has("bedrockbundler.test_ruby"));
    }

    @Test
    void legacyCustomModelDataItem() {
        ItemSpec coin = items().get("bedrockbundler:test_coin");
        assertEquals("minecraft:paper", coin.model());
        assertEquals(10001, coin.customModelData());
        assertTrue(mcpack.containsKey("textures/bedrockbundler/items/test_coin.png"));
    }

    @Test
    void threeDimensionalItemGetsGeometryAttachableAndIcon() throws IOException {
        assertTrue(mcpack.containsKey("models/entity/bedrockbundler/test_table.geo.json"));
        assertTrue(mcpack.containsKey("attachables/bedrockbundler/test_table.json"));
        assertTrue(mcpack.containsKey("animations/bedrockbundler/test_table.animation.json"));
        JsonObject geometry = Json.parse(mcpack.get("models/entity/bedrockbundler/test_table.geo.json")).getAsJsonObject();
        JsonObject description = geometry.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonObject("description");
        assertEquals("geometry.bedrockbundler.test_table", description.get("identifier").getAsString());
        // The root bone must bind to the holding slot, or the model floats at the player's origin.
        JsonObject root = geometry.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                .getAsJsonArray("bones").get(0).getAsJsonObject();
        assertTrue(root.get("binding").getAsString().contains("q.item_slot_to_bone_name"));

        BufferedImage icon = ImageIO.read(new ByteArrayInputStream(mcpack.get("textures/bedrockbundler/items/test_table.png")));
        assertEquals(64, icon.getWidth());
        int opaque = 0;
        for (int y = 0; y < icon.getHeight(); y++) {
            for (int x = 0; x < icon.getWidth(); x++) {
                if ((icon.getRGB(x, y) >>> 24) != 0) {
                    opaque++;
                }
            }
        }
        assertTrue(opaque > 300, "icon should show the model, got " + opaque + " pixels");
    }

    @Test
    void armorAttachable() {
        String attachable = new String(mcpack.get("attachables/bedrockbundler/test_ruby_helmet.json"), StandardCharsets.UTF_8);
        assertTrue(attachable.contains("geometry.player.armor.helmet"));
        assertTrue(mcpack.containsKey("textures/bedrockbundler/armor/test_ruby_helmet.png"));
        assertEquals("equipment", items().get("bedrockbundler:test_ruby_helmet").creativeCategory());
    }

    @Test
    void fullCubeBlock() {
        BlockSpec ore = result.bundle().blocks().stream().filter(b -> b.name().equals("test_ruby_ore")).findFirst().orElseThrow();
        assertEquals("minecraft:note_block[instrument=banjo,note=3,powered=false]", ore.javaState());
        assertNull(ore.geometry());
        assertEquals("bedrockbundler.block.test_block_ruby_ore", ore.materials().get("up").texture());
        assertEquals("opaque", ore.materials().get("up").renderMethod());
        assertTrue(mcpack.containsKey("textures/bedrockbundler/blocks/test_block_ruby_ore.png"));
    }

    @Test
    void customGeometryBlock() {
        BlockSpec slab = result.bundle().blocks().stream().filter(b -> b.name().equals("test_ruby_slab")).findFirst().orElseThrow();
        assertEquals("geometry.bedrockbundler.block.test_ruby_slab", slab.geometry());
        assertEquals(90, slab.rotationY());
        assertEquals(7, slab.lightEmission());
        assertTrue(slab.materials().containsKey("*"));
        assertTrue(mcpack.containsKey("models/blocks/bedrockbundler/test_ruby_slab.geo.json"));
    }

    @Test
    void manifestAndMappings() {
        JsonObject manifest = Json.parse(mcpack.get("manifest.json")).getAsJsonObject();
        assertEquals(2, manifest.get("format_version").getAsInt());
        JsonObject mappings = Json.parse(result.mappings()).getAsJsonObject();
        assertEquals(2, mappings.get("format_version").getAsInt());
        // ruby, table, coin and broken all sit on paper
        assertEquals(4, mappings.getAsJsonObject("items").getAsJsonArray("minecraft:paper").size());
    }

    @Test
    void blockMappings() {
        JsonObject root = Json.parse(result.blockMappings()).getAsJsonObject();
        assertEquals(1, root.get("format_version").getAsInt());
        JsonObject noteBlock = root.getAsJsonObject("blocks").getAsJsonObject("minecraft:note_block");
        // Geyser parses the key as a resource location: the block id only, states go into state_overrides.
        assertEquals("bedrockbundler_note_block", noteBlock.get("name").getAsString());
        assertTrue(noteBlock.get("only_override_states").getAsBoolean());
        JsonObject overrides = noteBlock.getAsJsonObject("state_overrides");
        assertEquals(2, overrides.size());
        JsonObject ore = overrides.getAsJsonObject("instrument=banjo,note=3,powered=false");
        assertEquals("minecraft:geometry.full_block", ore.get("geometry").getAsString());
        assertEquals("Ruby Ore", ore.get("display_name").getAsString());
        JsonObject up = ore.getAsJsonObject("material_instances").getAsJsonObject("up");
        assertEquals("bedrockbundler.block.test_block_ruby_ore", up.get("texture").getAsString());
        JsonObject slab = overrides.getAsJsonObject("instrument=banjo,note=4,powered=false");
        assertEquals("geometry.bedrockbundler.block.test_ruby_slab", slab.get("geometry").getAsString());
        assertEquals(270, slab.getAsJsonObject("transformation").getAsJsonArray("rotation").get(1).getAsInt());
        assertEquals(7, slab.get("light_emission").getAsInt());
    }

    @Test
    void bundleRoundTrip() throws IOException {
        Path dir = Files.createTempDirectory("bundle");
        result.bundle().write(dir, result.mcpack(), result.mappings(), result.blockMappings());
        assertTrue(Files.isRegularFile(dir.resolve(Bundle.BLOCK_MAPPINGS_FILE)));
        byte[] zip = Bundle.zip(dir);
        Path copy = Files.createTempDirectory("bundle-copy");
        Bundle.unzip(new ByteArrayInputStream(zip), copy);
        Bundle read = Bundle.read(copy);
        assertEquals(result.bundle().hash(), read.hash());
        assertEquals(result.bundle().items(), read.items());
        assertArrayEquals(result.mcpack(), Files.readAllBytes(copy.resolve(Bundle.PACK_FILE)));
    }
}
