package dev.bedrockbundler.common.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bedrockbundler.common.Json;
import dev.bedrockbundler.common.bedrock.Textures;
import dev.bedrockbundler.common.input.ConversionInput;
import dev.bedrockbundler.common.input.IaItem;
import dev.bedrockbundler.common.java.JavaPack;
import dev.bedrockbundler.common.spec.ItemSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dyed items: {@code minecraft:dye} tints of item models become Bedrock dyeable items, constant tints are baked into
 * the textures. Checks the generated JSON against Bedrock's dyeable item recipe (Microsoft Learn, "How to Add Custom
 * Items"): a grayscale TGA, the {@code entity_alphatest_change_color} material, 1.21.30 attachables and
 * {@code query.armor_color_slot} in a pre_animation script.
 */
class DyeableConverterTest {

    private static final int LEATHER = 0xA06540; // Java's undyed leather colour, -6265536

    private static Converter.Result result;
    private static Map<String, byte[]> mcpack;

    private static void json(Map<String, byte[]> pack, String path, String json) {
        pack.put(path, json.getBytes(StandardCharsets.UTF_8));
    }

    private static void png(Map<String, byte[]> pack, String path, int size, int argb) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setRGB(x, y, argb);
            }
        }
        pack.put(path, Textures.png(image));
    }

    private static IaItem item(String id, String material, String model, String slot, String asset) {
        return new IaItem(id, material, model, null, id, null, null, slot, asset, 0, null, false, false);
    }

    @BeforeAll
    static void convert() throws IOException {
        result = run();
        mcpack = JavaPack.readZip(new ByteArrayInputStream(result.mcpack()));
        // Keep the output around for manual inspection.
        for (Map.Entry<String, byte[]> entry : mcpack.entrySet()) {
            java.nio.file.Path file = java.nio.file.Path.of("build", "test-output", "dyeable", entry.getKey());
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.write(file, entry.getValue());
        }
    }

    private static Converter.Result run() {
        Map<String, byte[]> pack = new HashMap<>();
        // Flat dyeable item: layer0 tinted (gray base), layer1 an untinted overlay.
        png(pack, "assets/test/textures/item/gray.png", 16, 0xFF808080);
        png(pack, "assets/test/textures/item/overlay.png", 16, 0x00000000);
        json(pack, "assets/test/models/item/cap.json", """
                {"parent": "minecraft:item/generated", "textures": {"layer0": "test:item/gray", "layer1": "test:item/overlay"}}""");
        json(pack, "assets/test/items/cap.json", """
                {"model": {"type": "minecraft:model", "model": "test:item/cap",
                 "tints": [{"type": "minecraft:dye", "default": -6265536}]}}""");

        // 3D dyeable item: one element, all faces tint index 0.
        png(pack, "assets/test/textures/block/cloth.png", 16, 0xFFC0C0C0);
        json(pack, "assets/test/models/item/sofa.json", """
                {"textures": {"0": "test:block/cloth"},
                 "elements": [{"from": [0, 0, 0], "to": [16, 8, 16], "faces": {
                   "up": {"texture": "#0", "tintindex": 0}, "down": {"texture": "#0", "tintindex": 0},
                   "north": {"texture": "#0", "tintindex": 0}, "south": {"texture": "#0", "tintindex": 0},
                   "east": {"texture": "#0", "tintindex": 0}, "west": {"texture": "#0", "tintindex": 0}}}]}""");
        json(pack, "assets/test/items/sofa.json", """
                {"model": {"type": "minecraft:model", "model": "test:item/sofa", "tints": [{"type": "minecraft:dye", "default": -1}]}}""");

        // Leather style armor: dyeable layer plus untinted overlay layer, and a dye tinted item model.
        json(pack, "assets/test/equipment/leathery.json", """
                {"layers": {"humanoid": [{"texture": "test:leathery", "dyeable": {"color_when_undyed": -6265536}},
                                         {"texture": "test:leathery_overlay"}]}}""");
        png(pack, "assets/test/textures/entity/equipment/humanoid/leathery.png", 64, 0xFF808080);
        png(pack, "assets/test/textures/entity/equipment/humanoid/leathery_overlay.png", 64, 0x00000000);
        json(pack, "assets/test/models/item/leathery_chestplate.json", """
                {"parent": "minecraft:item/generated", "textures": {"layer0": "test:item/gray"}}""");
        json(pack, "assets/test/items/leathery_chestplate.json", """
                {"model": {"type": "minecraft:model", "model": "test:item/leathery_chestplate",
                 "tints": [{"type": "minecraft:dye", "default": -6265536}]}}""");

        // Constant tint: baked into the texture, not dyeable. Also a potion tint, which Bedrock cannot follow.
        json(pack, "assets/test/models/item/glow.json", """
                {"parent": "minecraft:item/generated", "textures": {"layer0": "test:item/gray"}}""");
        json(pack, "assets/test/items/glow.json", """
                {"model": {"type": "minecraft:model", "model": "test:item/glow",
                 "tints": [{"type": "minecraft:constant", "value": -16711936}]}}""");
        json(pack, "assets/test/items/brew.json", """
                {"model": {"type": "minecraft:model", "model": "test:item/glow",
                 "tints": [{"type": "minecraft:potion", "default": -13083194}]}}""");

        // Legacy CustomModelData on a vanilla leather helmet, tinted by the vanilla definition's entry.
        json(pack, "assets/minecraft/items/leather_helmet.json", """
                {"model": {"type": "range_dispatch", "property": "custom_model_data",
                  "fallback": {"type": "model", "model": "minecraft:item/leather_helmet",
                               "tints": [{"type": "minecraft:dye", "default": -6265536}]},
                  "entries": [{"threshold": 7, "model": {"type": "model", "model": "test:item/cap",
                               "tints": [{"type": "minecraft:dye", "default": -1}]}}]}}""");

        List<IaItem> items = List.of(
                item("test:cap", "minecraft:paper", "test:cap", null, null),
                item("test:sofa", "minecraft:paper", "test:sofa", null, null),
                item("test:leathery_chestplate", "minecraft:leather_chestplate", "test:leathery_chestplate", "chest", "test:leathery"),
                item("test:glow", "minecraft:paper", "test:glow", null, null),
                item("test:brew", "minecraft:paper", "test:brew", null, null),
                new IaItem("test:legacy_cap", "minecraft:leather_helmet", null, 7, "x", null, null, null, null, 0, null, false, false));
        return Converter.convert(new ConversionInput(pack, items, List.of()), ConversionContext.Options.DEFAULT, "Test");
    }

    private static ItemSpec spec(String suffix) {
        return result.bundle().items().stream().filter(i -> i.bedrockId().endsWith(suffix)).findFirst().orElseThrow();
    }

    private static JsonObject attachable(String name) {
        return Json.parse(mcpack.get("attachables/bedrockbundler/" + name + ".json")).getAsJsonObject();
    }

    private static JsonObject description(String name) {
        return attachable(name).getAsJsonObject("minecraft:attachable").getAsJsonObject("description");
    }

    /** Decodes the TGAs {@link Textures#tga} writes (32 bit, type 2, top left origin) and checks the header. */
    private static BufferedImage tga(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, buffer.get(0), "no image id");
        assertEquals(0, buffer.get(1), "no colour map");
        assertEquals(2, buffer.get(2), "uncompressed true colour");
        int width = buffer.getShort(12);
        int height = buffer.getShort(14);
        assertEquals(32, buffer.get(16));
        assertEquals(0x28, buffer.get(17), "8 alpha bits, top left origin");
        assertEquals(18 + width * height * 4, bytes.length);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int at = 18 + (y * width + x) * 4;
                image.setRGB(x, y, ((bytes[at + 3] & 0xFF) << 24) | ((bytes[at + 2] & 0xFF) << 16)
                        | ((bytes[at + 1] & 0xFF) << 8) | (bytes[at] & 0xFF));
            }
        }
        return image;
    }

    private static BufferedImage image(String path) throws IOException {
        byte[] bytes = mcpack.get(path);
        assertNotNull(bytes, path);
        return path.endsWith(".tga") ? tga(bytes) : ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private static int channel(int argb, int shift) {
        return (argb >> shift) & 0xFF;
    }

    @Test
    void flatDyeableItemGetsGrayscaleDyedIcon() throws IOException {
        ItemSpec cap = spec("test_cap");
        assertEquals("bedrockbundler.test_cap_dyed", cap.dyedIcon());
        assertEquals(LEATHER, cap.dyeColor());
        assertNull(spec("test_glow").dyedIcon());

        // Undyed: the gray base times the leather colour. Dyed icon: plain gray for Bedrock to tint.
        int undyed = image("textures/bedrockbundler/items/test_cap.png").getRGB(8, 8);
        assertEquals(Math.round(0x80 * 0xA0 / 255f), channel(undyed, 16));
        assertEquals(Math.round(0x80 * 0x65 / 255f), channel(undyed, 8));
        assertEquals(Math.round(0x80 * 0x40 / 255f), channel(undyed, 0));
        int dyed = image("textures/bedrockbundler/items/test_cap_dyed.tga").getRGB(8, 8);
        assertEquals(0x80, channel(dyed, 16));
        assertEquals(0x80, channel(dyed, 8));
        assertEquals(0x80, channel(dyed, 0));

        JsonObject itemTexture = Json.parse(mcpack.get("textures/item_texture.json")).getAsJsonObject().getAsJsonObject("texture_data");
        assertEquals("textures/bedrockbundler/items/test_cap_dyed", itemTexture.getAsJsonObject("bedrockbundler.test_cap_dyed").get("textures").getAsString());
        assertEquals("textures/bedrockbundler/items/test_cap", itemTexture.getAsJsonObject("bedrockbundler.test_cap").get("textures").getAsString());
        assertFalse(mcpack.containsKey("attachables/bedrockbundler/test_cap.json"), "a flat item is drawn by its icon");
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("test:cap") && w.contains("layer1")),
                "the untinted overlay gets the dye colour on Bedrock: " + result.warnings());
    }

    @Test
    void dyeable3dItemUsesDyedMaterialAndArmorColorQuery() throws IOException {
        ItemSpec sofa = spec("test_sofa");
        assertEquals("bedrockbundler.test_sofa_dyed", sofa.dyedIcon());
        assertEquals(0xFFFFFF, sofa.dyeColor());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("test:sofa") && w.contains("held in a hand")),
                "a dye tinted 3D model looks undyed in a hand: " + result.warnings());

        JsonObject description = description("test_sofa");
        assertEquals("1.21.30", attachable("test_sofa").get("format_version").getAsString());
        assertEquals("entity_alphatest_change_color", description.getAsJsonObject("materials").get("dyed").getAsString());
        assertEquals("entity_alphatest", description.getAsJsonObject("materials").get("default").getAsString());
        assertEquals("textures/bedrockbundler/models/test_sofa_dyed.tga", description.getAsJsonObject("textures").get("dyed").getAsString());
        assertEquals(DyeSupport.ITEM_CONTROLLER, description.getAsJsonArray("render_controllers").get(0).getAsString());

        String script = description.getAsJsonObject("scripts").getAsJsonArray("pre_animation").toString();
        assertTrue(script.contains("v.dye_slot = v.head ? 0 : (v.main_hand || v.off_hand) ? -1 : -1;"), script);
        assertTrue(script.contains("q.armor_color_slot(v.dye_slot, 0) != 1.0"), script);
        assertTrue(script.indexOf("v.dye_slot =") > script.indexOf("v.head ="), "after the variables it reads");

        JsonObject controller = Json.parse(mcpack.get("render_controllers/bedrockbundler/dye.render_controllers.json"))
                .getAsJsonObject().getAsJsonObject("render_controllers").getAsJsonObject(DyeSupport.ITEM_CONTROLLER);
        assertEquals("Geometry.default", controller.get("geometry").getAsString());
        assertTrue(controller.getAsJsonArray("materials").get(0).getAsJsonObject().get("*").getAsString()
                .startsWith("variable.is_dyed ? Material.dyed"));
        assertEquals("variable.is_dyed ? Texture.dyed : Texture.default", controller.getAsJsonArray("textures").get(0).getAsString());

        // The default texture is the cloth times white; the dyed one is its grayscale, same atlas layout.
        BufferedImage normal = image("textures/bedrockbundler/models/test_sofa.png");
        BufferedImage dyed = image("textures/bedrockbundler/models/test_sofa_dyed.tga");
        assertEquals(normal.getWidth(), dyed.getWidth());
        assertEquals(normal.getHeight(), dyed.getHeight());
        assertEquals(0xC0, channel(dyed.getRGB(2, 2), 16));
        // The inventory icon is rendered from the model, in a dyed (grayscale) and an undyed version.
        assertNotNull(mcpack.get("textures/bedrockbundler/items/test_sofa_dyed.tga"));
    }

    @Test
    void leatherStyleArmorIsDyedThroughTheWornSlot() throws IOException {
        ItemSpec armor = spec("test_leathery_chestplate");
        assertEquals("chest", armor.equipSlot());
        assertNotNull(armor.dyedIcon());
        assertEquals(LEATHER, armor.dyeColor());

        JsonObject description = description("test_leathery_chestplate");
        assertEquals("1.21.30", attachable("test_leathery_chestplate").get("format_version").getAsString());
        assertEquals("entity_alphatest_change_color", description.getAsJsonObject("materials").get("armor_dyed").getAsString());
        assertEquals("armor", description.getAsJsonObject("materials").get("armor").getAsString());
        assertEquals("textures/bedrockbundler/armor/test_leathery_chestplate_dyed.tga",
                description.getAsJsonObject("textures").get("armor_dyed").getAsString());
        JsonArray controllers = description.getAsJsonArray("render_controllers");
        assertEquals("!v.held", controllers.get(1).getAsJsonObject().get(DyeSupport.ARMOR_CONTROLLER).getAsString());
        assertTrue(description.getAsJsonObject("scripts").getAsJsonArray("pre_animation").toString()
                .contains("v.dye_slot = v.head ? 0 : (v.main_hand || v.off_hand) ? -1 : 1;"));

        // Undyed texture: the dyeable layer times color_when_undyed; the dyed one is plain gray.
        assertEquals(Math.round(0x80 * 0xA0 / 255f), channel(image("textures/bedrockbundler/armor/test_leathery_chestplate.png").getRGB(4, 4), 16));
        assertEquals(0x80, channel(image("textures/bedrockbundler/armor/test_leathery_chestplate_dyed.tga").getRGB(4, 4), 16));

        JsonObject controller = Json.parse(mcpack.get("render_controllers/bedrockbundler/dye.render_controllers.json"))
                .getAsJsonObject().getAsJsonObject("render_controllers").getAsJsonObject(DyeSupport.ARMOR_CONTROLLER);
        assertEquals("variable.is_dyed ? Texture.armor_dyed : Texture.armor", controller.getAsJsonArray("textures").get(0).getAsString());
        assertTrue(controller.getAsJsonArray("materials").get(0).getAsJsonObject().get("*").getAsString().contains("Material.armor_dyed"));
    }

    @Test
    void constantTintIsBakedAndNotDyeable() throws IOException {
        ItemSpec glow = spec("test_glow");
        assertNull(glow.dyedIcon());
        int pixel = image("textures/bedrockbundler/items/test_glow.png").getRGB(8, 8);
        // -16711936 is 0xFF00FF00: only the green channel survives the multiplication.
        assertEquals(0, channel(pixel, 16));
        assertEquals(0x80, channel(pixel, 8));
        assertEquals(0, channel(pixel, 0));
        assertFalse(mcpack.containsKey("textures/bedrockbundler/items/test_glow_dyed.tga"));
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("test:glow")), result.warnings().toString());

        // Sources that follow item data fall back to their default colour, with a warning.
        assertNull(spec("test_brew").dyedIcon());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("test:brew") && w.contains("potion")), result.warnings().toString());
    }

    @Test
    void legacyCustomModelDataItemTakesTheTintsOfItsDefinitionEntry() {
        // The entry's model is tinted with a white default, so the item is dyeable and undyed it stays plain.
        assertNotNull(spec("test_legacy_cap").dyedIcon());
        assertEquals(7, spec("test_legacy_cap").customModelData());
    }

    @Test
    void conversionIsDeterministic() {
        Converter.Result second = run();
        assertArrayEquals(result.mcpack(), second.mcpack());
        assertEquals(result.bundle().items(), second.bundle().items());
        assertEquals(result.warnings(), second.warnings());
    }

    @Test
    void tgaWriterRoundTrips() {
        BufferedImage image = new BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x80FF8040);
        image.setRGB(1, 2, 0xFF102030);
        BufferedImage back = tga(Textures.tga(image));
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 2; x++) {
                assertEquals(image.getRGB(x, y), back.getRGB(x, y));
            }
        }
        assertArrayEquals(Textures.tga(image), Textures.tga(back));
    }

    @Test
    void geyserMappingsDescribeDyeableItems() {
        JsonObject items = Json.parse(result.mappings()).getAsJsonObject().getAsJsonObject("items");
        JsonObject options = items.getAsJsonArray("minecraft:paper").asList().stream().map(e -> e.getAsJsonObject())
                .filter(d -> d.get("bedrock_identifier").getAsString().endsWith("test_cap")).findFirst().orElseThrow()
                .getAsJsonObject("bedrock_options");
        assertEquals("#A06540", options.getAsJsonObject("dyeable").get("default_color").getAsString());
        assertEquals("bedrockbundler.test_cap_dyed", options.getAsJsonObject("icons").get("dyed").getAsString());
        assertFalse(options.has("icon"));
        JsonObject plain = items.getAsJsonArray("minecraft:paper").asList().stream().map(e -> e.getAsJsonObject())
                .filter(d -> d.get("bedrock_identifier").getAsString().endsWith("test_glow")).findFirst().orElseThrow()
                .getAsJsonObject("bedrock_options");
        assertEquals("bedrockbundler.test_glow", plain.get("icon").getAsString());
        assertFalse(plain.has("dyeable"));
    }

    @Test
    void bundlesWithoutDyeFieldsStillRead() {
        ItemSpec old = Json.GSON.fromJson("""
                {"javaItem": "minecraft:paper", "model": "test:x", "bedrockId": "bedrockbundler:x", "icon": "bedrockbundler.x",
                 "creativeCategory": "items", "protection": 0}""", ItemSpec.class);
        assertNull(old.dyedIcon());
        assertNull(old.dyeColor());
        assertEquals("minecraft:paper", old.javaItem());
        // The constructor of the format before dyeable items.
        ItemSpec plain = new ItemSpec("minecraft:paper", "test:x", null, "bedrockbundler:x", "X", "bedrockbundler.x", false,
                "items", null, null, null, 0, null, List.of());
        assertNull(plain.dyedIcon());
    }

    @Test
    void colorsReadIntegersAndComponentLists() {
        assertEquals(0xA06540, DyeSupport.color(Json.parse("-6265536".getBytes(StandardCharsets.UTF_8)), 0));
        assertEquals(0x80FF00, DyeSupport.color(Json.parse("[0.5019608, 1.0, 0.0]".getBytes(StandardCharsets.UTF_8)), 0));
        assertEquals(0x123456, DyeSupport.color(null, 0x123456));
        assertEquals("A06540", DyeSupport.hex(-6265536));
    }
}
