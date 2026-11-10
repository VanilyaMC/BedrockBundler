package dev.bedrockbundler.common.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bedrockbundler.common.Json;
import dev.bedrockbundler.common.bedrock.Textures;
import dev.bedrockbundler.common.input.ConversionInput;
import dev.bedrockbundler.common.input.IaItem;
import dev.bedrockbundler.common.java.JavaPack;
import dev.bedrockbundler.common.spec.ItemSpec;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Nexo-style items: invisible menu buttons, custom elytra and horse armor. */
class EquipmentConverterTest {

    private static void json(Map<String, byte[]> pack, String path, String json) {
        pack.put(path, json.getBytes(StandardCharsets.UTF_8));
    }

    private static IaItem item(String id, String material, String model, String slot, String asset) {
        return new IaItem(id, material, model, null, id, null, null, slot, asset, 0, null, false, false);
    }

    @Test
    void emptyButtonsElytraAndHorseArmor() throws IOException {
        Map<String, byte[]> pack = new HashMap<>();
        BufferedImage texture = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        texture.setRGB(1, 1, 0xFF00FF00);
        pack.put("assets/nexo/textures/icon.png", Textures.png(texture));
        pack.put("assets/nexo/textures/entity/equipment/wings/wings.png", Textures.png(texture));
        pack.put("assets/nexo/textures/entity/equipment/horse_body/horse.png", Textures.png(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)));
        json(pack, "assets/nexo/models/icon.json", "{\"parent\": \"item/generated\", \"textures\": {\"layer0\": \"nexo:icon\"}}");
        json(pack, "assets/nexo/items/next_page_icon.json", "{\"model\": {\"type\": \"empty\"}}");
        json(pack, "assets/nexo/items/elytra.json", "{\"model\": {\"type\": \"model\", \"model\": \"nexo:icon\"}}");
        json(pack, "assets/nexo/items/horse.json", "{\"model\": {\"type\": \"model\", \"model\": \"nexo:icon\"}}");
        json(pack, "assets/nexo/equipment/elytra.json", "{\"layers\": {\"wings\": [{\"texture\": \"nexo:wings\"}]}}");
        json(pack, "assets/nexo/equipment/horse.json", "{\"layers\": {\"horse_body\": [{\"texture\": \"nexo:horse\"}]}}");

        Converter.Result result = Converter.convert(new ConversionInput(pack, List.of(
                item("nexo:next_page_icon", "minecraft:paper", "nexo:next_page_icon", null, null),
                item("nexo:elytra", "minecraft:elytra", "nexo:elytra", "chest", "nexo:elytra"),
                item("nexo:horse", "minecraft:diamond_horse_armor", "nexo:horse", "body", "nexo:horse")), List.of()),
                ConversionContext.Options.DEFAULT, "Test");
        Map<String, byte[]> mcpack = JavaPack.readZip(new ByteArrayInputStream(result.mcpack()));

        assertFalse(result.warnings().stream().anyMatch(w -> w.contains("next_page_icon")), result.warnings().toString());
        BufferedImage button = javax.imageio.ImageIO.read(new ByteArrayInputStream(
                mcpack.get("textures/bedrockbundler/items/nexo_next_page_icon.png")));
        assertEquals(0, button.getRGB(8, 8) >>> 24, "an empty model is a transparent icon, not the placeholder");

        JsonObject elytra = Json.parse(mcpack.get("attachables/bedrockbundler/nexo_elytra.json")).getAsJsonObject()
                .getAsJsonObject("minecraft:attachable").getAsJsonObject("description");
        assertEquals("geometry.elytra", elytra.getAsJsonObject("geometry").get("armor").getAsString());
        assertTrue(elytra.getAsJsonObject("geometry").get("default").getAsString().startsWith("geometry.bedrockbundler."),
                "the flat icon is a model to hold");
        JsonArray controllers = elytra.getAsJsonArray("render_controllers");
        assertEquals("v.held", controllers.get(0).getAsJsonObject().get("controller.render.item_default").getAsString());
        assertEquals("!v.held", controllers.get(1).getAsJsonObject().get(ArmorConverter.ARMOR_CONTROLLER).getAsString());
        assertTrue(mcpack.containsKey("textures/bedrockbundler/armor/nexo_elytra.png"));
        // Worn, Geyser shows the vanilla elytra, which takes the custom look.
        assertTrue(mcpack.containsKey("textures/models/armor/elytra.png"));
        assertTrue(mcpack.containsKey("textures/items/elytra.png"));

        JsonObject horseArmor = Json.parse(mcpack.get("attachables/bedrockbundler/nexo_horse.json")).getAsJsonObject()
                .getAsJsonObject("minecraft:attachable").getAsJsonObject("description");
        assertEquals("geometry.bedrockbundler.horse_armor", horseArmor.getAsJsonObject("geometry").get("horse").getAsString());
        // An inflated copy of the horse without saddle, bags and reins.
        String horseGeometry = new String(mcpack.get("models/entity/bedrockbundler/horse_body.geo.json"), StandardCharsets.UTF_8);
        assertTrue(horseGeometry.contains("\"geometry.bedrockbundler.horse_armor\""));
        assertTrue(horseGeometry.contains("\"inflate\": " + ArmorConverter.ARMOR_INFLATE));
        assertFalse(horseGeometry.contains("\"Saddle\"") || horseGeometry.contains("\"BitL\""));
        // Vanilla horses draw no attachables; the pack's copy of the horse turns them on.
        JsonObject horseEntity = Json.parse(mcpack.get("entity/horse.entity.json")).getAsJsonObject()
                .getAsJsonObject("minecraft:client_entity").getAsJsonObject("description");
        assertTrue(horseEntity.get("enable_attachables").getAsBoolean());
        assertTrue(horseEntity.getAsJsonObject("scripts").getAsJsonArray("pre_animation").toString()
                .contains("'bedrockbundler:nexo_horse'"));
        assertFalse(mcpack.containsKey("entity/llama.entity.json"), "only animals that wear converted armor");

        ItemSpec horse = result.bundle().items().stream().filter(i -> i.bedrockId().endsWith("nexo_horse")).findFirst().orElseThrow();
        assertEquals("body", horse.equipSlot());
        assertEquals(List.of("minecraft:horse_armor"), horse.tags());
    }

    @Test
    void shieldGetsUsingPoseAndMirroredOffHand() throws IOException {
        Map<String, byte[]> pack = new HashMap<>();
        BufferedImage texture = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        texture.setRGB(1, 1, 0xFF00FF00);
        pack.put("assets/nexo/textures/shield.png", Textures.png(texture));
        String elements = "\"textures\": {\"0\": \"nexo:shield\"}, \"elements\": [{\"from\": [0, 0, 0], \"to\": [16, 16, 2],"
                + " \"faces\": {\"north\": {\"uv\": [0, 0, 16, 16], \"texture\": \"#0\"}}}]";
        json(pack, "assets/nexo/models/idle.json", "{" + elements + ", \"display\": {"
                + "\"thirdperson_righthand\": {\"rotation\": [0, -90, -90], \"translation\": [3, -2.5, 2]}}}");
        json(pack, "assets/nexo/models/blocking.json", "{" + elements + ", \"display\": {"
                + "\"thirdperson_righthand\": {\"rotation\": [10, -60, -90], \"translation\": [1, 0, 2]}}}");
        json(pack, "assets/nexo/items/shield.json", "{\"model\": {\"type\": \"condition\", \"property\": \"using_item\","
                + " \"on_true\": {\"type\": \"model\", \"model\": \"nexo:blocking\"},"
                + " \"on_false\": {\"type\": \"model\", \"model\": \"nexo:idle\"}}}");

        Converter.Result result = Converter.convert(new ConversionInput(pack, List.of(
                item("nexo:shield", "minecraft:shield", "nexo:shield", null, null)), List.of()),
                ConversionContext.Options.DEFAULT, "Test");
        Map<String, byte[]> mcpack = JavaPack.readZip(new ByteArrayInputStream(result.mcpack()));

        JsonObject description = Json.parse(mcpack.get("attachables/bedrockbundler/nexo_shield.json")).getAsJsonObject()
                .getAsJsonObject("minecraft:attachable").getAsJsonObject("description");
        assertTrue(description.getAsJsonObject("animations").has("thirdperson_main_hand_using"));
        assertTrue(description.getAsJsonObject("scripts").getAsJsonArray("pre_animation").toString().contains("v.using"));

        JsonObject animations = Json.parse(mcpack.get("animations/bedrockbundler/nexo_shield.animation.json"))
                .getAsJsonObject().getAsJsonObject("animations");
        String prefix = "animation.bedrockbundler.nexo_shield.";
        // Java mirrors the left hand: the right hand's y/z rotations and x translation are negated.
        JsonObject offX = animations.getAsJsonObject(prefix + "thirdperson_off_hand").getAsJsonObject("bones")
                .getAsJsonObject(dev.bedrockbundler.common.bedrock.GeometryConverter.ITEM_ROOT + "_x");
        JsonObject mainX = animations.getAsJsonObject(prefix + "thirdperson_main_hand").getAsJsonObject("bones")
                .getAsJsonObject(dev.bedrockbundler.common.bedrock.GeometryConverter.ITEM_ROOT + "_x");
        assertEquals(-mainX.getAsJsonArray("position").get(0).getAsFloat(),
                offX.getAsJsonArray("position").get(0).getAsFloat(), 1e-4);
        JsonObject usingX = animations.getAsJsonObject(prefix + "thirdperson_main_hand_using").getAsJsonObject("bones")
                .getAsJsonObject(dev.bedrockbundler.common.bedrock.GeometryConverter.ITEM_ROOT + "_x");
        assertEquals(-10f, usingX.getAsJsonArray("rotation").get(0).getAsFloat(), 1e-4, "the blocking model's display");
    }

    @Test
    void connectableFurnitureVariantsBecomeItems() {
        Map<String, byte[]> pack = new HashMap<>();
        for (String name : List.of("sofa", "sofa_left", "sofa_inner", "sofa_left/broken", "table")) {
            json(pack, "assets/nexo/items/" + name + ".json", "{\"model\": {\"type\": \"empty\"}}");
        }
        IaItem sofa = new IaItem("nexo:sofa", "minecraft:paper", "nexo:sofa", null, "Sofa", null, null, null, null, 0, null, false, true);
        IaItem table = new IaItem("nexo:table", "minecraft:paper", "nexo:table", null, "Table", null, null, null, null, 0, null, false, false);
        List<IaItem> variants = Converter.modelVariants(List.of(sofa, table), new JavaPack(pack));
        assertEquals(List.of("nexo:sofa_inner", "nexo:sofa_left"), variants.stream().map(IaItem::itemModel).sorted().toList());
        assertTrue(variants.stream().allMatch(IaItem::furniture));
    }

    /**
     * ItemsAdderAdditions' chair is drawn with the HEAD display (turned 180°, raised 8 px), not the fixed one;
     * a thrown snowball item is drawn with the ground display.
     */
    @Test
    void furniturePoseUsesTheEntityDisplay() throws IOException {
        Map<String, byte[]> pack = new HashMap<>();
        BufferedImage texture = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        texture.setRGB(1, 1, 0xFF00FF00);
        pack.put("assets/ia/textures/chair.png", Textures.png(texture));
        String elements = "\"textures\": {\"0\": \"ia:chair\"}, \"elements\": [{\"from\": [0, 0, 0], \"to\": [16, 4, 2],"
                + " \"faces\": {\"north\": {\"uv\": [0, 0, 16, 16], \"texture\": \"#0\"}}}]";
        json(pack, "assets/ia/models/chair.json", "{" + elements + ", \"display\": {"
                + "\"head\": {\"rotation\": [0, -180, 0], \"translation\": [0, 8, 0]},"
                + " \"ground\": {\"scale\": [0.5, 0.5, 0.5]}}}");
        json(pack, "assets/ia/items/chair.json", "{\"model\": {\"type\": \"model\", \"model\": \"ia:chair\"}}");
        IaItem chair = new IaItem("ia:chair", "minecraft:paper", "ia:chair", null, "Chair", null, null, null, null, 0,
                null, false, true).withEntityDisplay("head");
        IaItem bomb = item("ia:bomb", "minecraft:snowball", "ia:chair", null, null);

        Converter.Result result = Converter.convert(new ConversionInput(pack, List.of(chair, bomb), List.of()),
                ConversionContext.Options.DEFAULT, "Test");
        Map<String, byte[]> mcpack = JavaPack.readZip(new ByteArrayInputStream(result.mcpack()));
        String root = dev.bedrockbundler.common.bedrock.GeometryConverter.ITEM_ROOT;

        JsonObject chairPose = Json.parse(mcpack.get("animations/bedrockbundler/ia_chair.animation.json")).getAsJsonObject()
                .getAsJsonObject("animations").getAsJsonObject("animation.bedrockbundler.ia_chair.furniture")
                .getAsJsonObject("bones");
        assertEquals(180f, chairPose.getAsJsonObject(root + "_y").getAsJsonArray("rotation").get(1).getAsFloat(), 1e-4);
        assertEquals(8f, chairPose.getAsJsonObject(root + "_x").getAsJsonArray("position").get(1).getAsFloat(), 1e-4);

        JsonObject bombPose = Json.parse(mcpack.get("animations/bedrockbundler/ia_bomb.animation.json")).getAsJsonObject()
                .getAsJsonObject("animations").getAsJsonObject("animation.bedrockbundler.ia_bomb.furniture")
                .getAsJsonObject("bones");
        assertEquals(0.5f, bombPose.getAsJsonObject(root + "_z").getAsJsonArray("scale").get(0).getAsFloat(), 1e-4);
    }

    @Test
    void glyphTitleOffsetsSumShifts() {
        Map<Integer, Integer> offsets = GlyphTitleOffsets.find(List.of(
                "a: <shift:-8><shift:-29><glyph:menu>\nb: <shift:-7><glyph:recipe:colorable> <glyph:unshifted>"),
                id -> switch (id) {
                    case "menu" -> "ꐐ";
                    case "recipe" -> "ꐑ";
                    default -> null;
                });
        assertEquals(Map.of(0xA410, -37, 0xA411, -7), new HashMap<>(offsets));
    }
}
