package dev.bedrockbundler.common.convert;

import dev.bedrockbundler.common.bedrock.Textures;
import dev.bedrockbundler.common.input.ConversionInput;
import dev.bedrockbundler.common.input.IaBlock;
import dev.bedrockbundler.common.input.IaItem;
import dev.bedrockbundler.common.java.JavaPack;
import dev.bedrockbundler.common.spec.BlockSpec;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mirrors the layout ItemsAdder 4.0.18 generates on a 26.2 server: legacy CustomModelData, the real item
 * definitions inside {@code ia_overlay_*} directories, and models that reference short sprite names
 * ({@code ia:N}) which the atlases map to the real textures.
 */
class ItemsAdderPackLayoutTest {

    @Test
    void resolvesOverlaysAndShortTextureReferences() throws IOException {
        Map<String, byte[]> pack = new HashMap<>();
        put(pack, "pack.mcmeta", """
                {"pack": {"pack_format": 84, "description": "ItemsAdder"}, "overlays": {"entries": [
                  {"directory": "ia_overlay_1_21_4_plus", "formats": [46, 64], "min_format": 46, "max_format": 87},
                  {"directory": "ia_overlay_26_2_plus", "formats": [88, 9999], "min_format": 88, "max_format": 9999}
                ]}}""");
        // Base: legacy overrides and a stale model that must lose against the overlay
        put(pack, "assets/minecraft/models/item/paper.json", """
                {"parent": "minecraft:item/generated", "textures": {"layer0": "ia:21"},
                 "overrides": [{"predicate": {"custom_model_data": 10007}, "model": "test:item/ia_auto/ruby"},
                               {"predicate": {"custom_model_data": 10008}, "model": "test:item/table"}]}""");
        put(pack, "assets/minecraft/atlases/blocks.json", """
                {"sources": [{"type": "single", "resource": "test:block/ruby_block", "sprite": "ia:0"},
                             {"type": "single", "resource": "test:block/wood", "sprite": "ia:1"},
                             {"type": "single", "resource": "minecraft:block/note_block", "sprite": "ia:5"}]}""");
        put(pack, "assets/minecraft/blockstates/note_block.json", """
                {"variants": {"instrument=harp": {"model": "block/original/note_block"},
                              "instrument=basedrum,note=0,powered=false": {"model": "test:block/ruby_block"}}}""");
        put(pack, "assets/test/models/block/ruby_block.json", "{\"parent\": \"block/cube_all\", \"textures\": {\"all\": \"ia:0\"}}");
        put(pack, "assets/test/models/item/ia_auto/ruby.json", "{\"parent\": \"minecraft:item/generated\", \"textures\": {\"layer0\": \"ia:19\"}}");
        put(pack, "assets/test/models/item/table.json", "{\"textures\": {\"0\": \"ia:1\"}, \"elements\": []}");
        // 26.2 overlay: item definition, atlas and the model the definition points at
        put(pack, "ia_overlay_26_2_plus/assets/minecraft/items/paper.json", """
                {"model": {"type": "range_dispatch", "property": "custom_model_data", "index": 0,
                  "fallback": {"type": "minecraft:model", "model": "minecraft:item/paper"},
                  "entries": [{"threshold": 10007, "model": {"type": "model", "model": "test:item/ia_auto/ruby"}},
                              {"threshold": 10008, "model": {"type": "model", "model": "_iainternal:item_context/test/item/table"}}]}}""");
        put(pack, "ia_overlay_26_2_plus/assets/minecraft/atlases/items.json", """
                {"sources": [{"type": "single", "resource": "test:block/wood", "sprite": "ia:18"},
                             {"type": "single", "resource": "test:item/ruby", "sprite": "ia:19"}]}""");
        put(pack, "ia_overlay_26_2_plus/assets/_iainternal/models/item_context/test/item/table.json", """
                {"textures": {"0": "ia:18"}, "elements": [{"from": [0, 0, 0], "to": [16, 2, 16],
                  "faces": {"up": {"uv": [0, 0, 16, 16], "texture": "#0"}, "north": {"uv": [0, 0, 16, 2], "texture": "#0"}}}]}""");
        // A file from an overlay that does not apply must be ignored
        put(pack, "ia_overlay_1_21_4_plus/assets/minecraft/items/paper.json", "{\"model\": {\"type\": \"model\", \"model\": \"test:item/wrong\"}}");

        png(pack, "assets/test/textures/item/ruby.png", 0xFFE0115F);
        png(pack, "assets/test/textures/block/wood.png", 0xFF9C6B30);
        png(pack, "assets/test/textures/block/ruby_block.png", 0xFF777777);

        List<IaItem> items = List.of(
                new IaItem("test:ruby", "minecraft:paper", null, 10007, "Ruby", null, null, null, null, 0, null, false, false),
                new IaItem("test:table", "minecraft:paper", null, 10008, "Table", null, null, null, null, 0, null, false, false));
        List<IaBlock> blocks = List.of(new IaBlock("test:ruby_block",
                "minecraft:note_block[instrument=basedrum,note=0,powered=false]", "Ruby Block", 3, 5));

        Converter.Result result = Converter.convert(new ConversionInput(pack, items, blocks),
                ConversionContext.Options.DEFAULT, "BedrockBundler");
        assertEquals(List.of(), result.warnings());
        Map<String, byte[]> mcpack = JavaPack.readZip(new ByteArrayInputStream(result.mcpack()));

        // Overlay model (3D) was used for the table, with the overlay's sprite alias ia:18 -> wood
        assertTrue(mcpack.containsKey("attachables/bedrockbundler/test_table.json"));
        BufferedImage atlas = javax.imageio.ImageIO.read(new ByteArrayInputStream(mcpack.get("textures/bedrockbundler/models/test_table.png")));
        assertEquals(0xFF9C6B30, atlas.getRGB(4, 4));

        // Flat ruby: ia:19 -> test:item/ruby
        BufferedImage ruby = javax.imageio.ImageIO.read(new ByteArrayInputStream(mcpack.get("textures/bedrockbundler/items/test_ruby.png")));
        assertEquals(0xFFE0115F, ruby.getRGB(4, 4));

        // Block texture: ia:0 -> test:block/ruby_block
        BlockSpec block = result.bundle().blocks().get(0);
        assertEquals("bedrockbundler.block.test_block_ruby_block", block.materials().get("up").texture());
        assertTrue(mcpack.containsKey("textures/bedrockbundler/blocks/test_block_ruby_block.png"));
    }

    private static void put(Map<String, byte[]> pack, String path, String json) {
        pack.put(path, json.getBytes(StandardCharsets.UTF_8));
    }

    private static void png(Map<String, byte[]> pack, String path, int argb) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, argb);
            }
        }
        pack.put(path, Textures.png(image));
    }
}
