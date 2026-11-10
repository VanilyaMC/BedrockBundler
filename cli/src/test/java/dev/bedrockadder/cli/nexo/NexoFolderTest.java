package dev.bedrockadder.cli.nexo;

import com.google.gson.JsonObject;
import dev.bedrockadder.common.Json;
import dev.bedrockadder.common.convert.ConversionContext;
import dev.bedrockadder.common.convert.Converter;
import dev.bedrockadder.common.input.ConversionInput;
import dev.bedrockadder.common.input.IaBlock;
import dev.bedrockadder.common.input.IaItem;
import dev.bedrockadder.common.java.JavaPack;
import dev.bedrockadder.common.spec.ItemSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NexoFolderTest {

    @TempDir
    static Path nexo;

    private static NexoFolder folder;
    private static ConversionInput input;
    private static Converter.Result result;

    @BeforeAll
    static void setUp() throws IOException {
        write("items/demo/items.yml", """
                ruby:
                  itemname: "<!i><#E0115F>Ruby"
                  material: PAPER
                  Pack:
                    model: "demo:item/ruby"
                  Components:
                    item_model: "demo:ruby"
                    max_stack_size: 16
                ruby_ore:
                  itemname: "<!i>Ruby Ore"
                  material: PAPER
                  Pack:
                    model: "demo:block/ruby_ore"
                  Components:
                    item_model: "demo:ruby_ore"
                  Mechanics:
                    custom_block:
                      type: NOTEBLOCK
                      custom_variation: 77
                      model: ruby_ore
                      hardness: 3
                crop_stage:
                  itemname: "Crop"
                  material: PAPER
                  excludeFromInventory: true
                  Pack:
                    model: "demo:block/crop_stage"
                  Components:
                    item_model: "demo:crop_stage"
                  Mechanics:
                    furniture:
                      farmland_required: true
                straw_hat:
                  itemname: "Straw Hat"
                  material: PAPER
                  Pack:
                    model: "demo:item/straw_hat"
                  Components:
                    item_model: "demo:straw_hat"
                    equippable:
                      slot: HEAD
                no_model:
                  material: STICK
                chair:
                  material: STONE
                  Pack:
                    model: "demo:item/chair"
                  Components:
                    item_model: "demo:chair"
                  Mechanics:
                    furniture:
                      placeable: true
                wing:
                  material: ELYTRA
                  Pack:
                    texture: "demo:item/wing_icon"
                """);
        write("glyphs/demo/glyphs.yml", """
                coin:
                  texture: "demo:glyph/coin.png"
                  ascent: 7
                  height: 8
                  char: "\\ue112"
                  font: "minecraft:default"
                """);
        write("pack/assets/demo/items/ruby.json", "{\"model\": {\"type\": \"minecraft:model\", \"model\": \"demo:item/ruby\"}}");
        write("pack/assets/demo/models/item/ruby.json", "{\"parent\": \"minecraft:item/generated\", \"textures\": {\"layer0\": \"demo:item/ruby\"}}");
        png("pack/assets/demo/textures/item/ruby.png", 0xFFE0115F);
        write("pack/assets/demo/items/ruby_ore.json", "{\"model\": {\"type\": \"minecraft:model\", \"model\": \"demo:block/ruby_ore\"}}");
        write("pack/assets/demo/models/block/ruby_ore.json", "{\"parent\": \"minecraft:block/cube_all\", \"textures\": {\"all\": \"demo:block/ruby_ore\"}}");
        png("pack/assets/demo/textures/block/ruby_ore.png", 0xFF777777);
        write("pack/assets/demo/items/crop_stage.json", "{\"model\": {\"type\": \"minecraft:model\", \"model\": \"demo:block/crop_stage\"}}");
        write("pack/assets/demo/models/block/crop_stage.json", "{\"parent\": \"minecraft:block/crop\", \"textures\": {\"crop\": \"demo:block/crop_stage\"}}");
        png("pack/assets/demo/textures/block/crop_stage.png", 0xFF33AA33);
        write("pack/assets/demo/items/straw_hat.json", "{\"model\": {\"type\": \"minecraft:model\", \"model\": \"demo:item/straw_hat\"}}");
        write("pack/assets/demo/models/item/straw_hat.json", """
                {"textures": {"0": "demo:item/straw_hat"}, "elements": [
                  {"from": [-2, 12, -2], "to": [18, 13, 18], "faces": {"up": {"uv": [0, 0, 16, 16], "texture": "#0"},
                   "down": {"uv": [0, 0, 16, 16], "texture": "#0"}}}],
                 "display": {"head": {"translation": [0, 6, 0]}}}""");
        png("pack/assets/demo/textures/item/straw_hat.png", 0xFFE8C468);
        png("pack/assets/demo/textures/glyph/coin.png", 0xFFFFD700);
        // No items/chair.json: Nexo generates it from Pack.model.
        write("pack/assets/demo/models/item/chair.json", "{\"parent\": \"minecraft:item/generated\", \"textures\": {\"layer0\": \"demo:item/chair\"}}");
        png("pack/assets/demo/textures/item/chair.png", 0xFF8B5A2B);
        png("pack/assets/demo/textures/item/wing_icon.png", 0xFF7FC8F0);

        folder = new NexoFolder(nexo);
        input = folder.input(null);
        result = Converter.convert(input, ConversionContext.Options.DEFAULT, "Demo");
    }

    @Test
    void readsItems() {
        IaItem ruby = item("nexo:ruby");
        assertEquals("minecraft:paper", ruby.material());
        assertEquals("demo:ruby", ruby.itemModel());
        assertEquals("Ruby", ruby.displayName());
        assertEquals(16, ruby.maxStackSize());
        assertTrue(item("nexo:ruby_ore").block());
        assertTrue(item("nexo:crop_stage").furniture());
        assertEquals("head", item("nexo:straw_hat").equipSlot());
        // An item with no model data is not custom for Bedrock.
        assertTrue(input.items().stream().noneMatch(i -> i.id().equals("nexo:no_model")));
    }

    @Test
    void encodesNoteBlockAndSynthesizesBlockstate() {
        IaBlock ore = input.blocks().get(0);
        // 77 = instrument index 1 (basedrum), note 77 % 25 = 2, 77 % 50 = 27 >= 25 -> powered
        assertEquals("minecraft:note_block[instrument=basedrum,note=2,powered=true]", ore.javaState());
        JsonObject variants = Json.parse(input.javaPack().get("assets/minecraft/blockstates/note_block.json"))
                .getAsJsonObject().getAsJsonObject("variants");
        // 'model: ruby_ore' is minecraft:ruby_ore for Nexo, which does not exist; Pack.model is used instead.
        assertEquals("demo:block/ruby_ore",
                variants.getAsJsonObject("instrument=basedrum,note=2,powered=true").get("model").getAsString());
        assertTrue(folder.warnings().stream().anyMatch(w -> w.contains("custom_block.model 'ruby_ore'")));
        assertEquals(1, result.bundle().blocks().size());
    }

    @Test
    void convertsHatCropAndGlyph() throws IOException {
        Map<String, byte[]> mcpack = JavaPack.readZip(new ByteArrayInputStream(result.mcpack()));
        ItemSpec hat = result.bundle().items().stream().filter(i -> i.bedrockId().endsWith("nexo_straw_hat")).findFirst().orElseThrow();
        assertEquals("head", hat.equipSlot());
        // 3D models (hat, crop stage) get attachables so they render on the head and on the furniture stand.
        assertTrue(mcpack.containsKey("attachables/bedrockadder/nexo_straw_hat.json"));
        assertTrue(mcpack.containsKey("attachables/bedrockadder/nexo_crop_stage.json"));
        // The glyph lands on Bedrock's E1 glyph sheet.
        assertTrue(mcpack.containsKey("font/glyph_E1.png"));
    }

    @Test
    void synthesizesMissingItemDefinitions() {
        assertEquals("demo:chair", item("nexo:chair").itemModel());
        assertTrue(input.javaPack().containsKey("assets/demo/items/chair.json"));
        // Texture-only items get nexo:<id>, as Nexo does with prefer_item_models.
        assertEquals("nexo:wing", item("nexo:wing").itemModel());
        assertTrue(input.javaPack().containsKey("assets/nexo/items/wing.json"));
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("nexo:chair") || w.contains("nexo:wing")));
    }

    private static IaItem item(String id) {
        return input.items().stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow();
    }

    private static void write(String path, String content) throws IOException {
        Path file = nexo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void png(String path, int argb) throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, argb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        Path file = nexo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, out.toByteArray());
    }
}
