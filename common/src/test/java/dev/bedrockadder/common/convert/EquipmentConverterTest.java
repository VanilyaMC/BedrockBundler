package dev.bedrockadder.common.convert;

import com.google.gson.JsonObject;
import dev.bedrockadder.common.Json;
import dev.bedrockadder.common.bedrock.Textures;
import dev.bedrockadder.common.input.ConversionInput;
import dev.bedrockadder.common.input.IaItem;
import dev.bedrockadder.common.java.JavaPack;
import dev.bedrockadder.common.spec.ItemSpec;
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
                mcpack.get("textures/bedrockadder/items/nexo_next_page_icon.png")));
        assertEquals(0, button.getRGB(8, 8) >>> 24, "an empty model is a transparent icon, not the placeholder");

        JsonObject elytra = Json.parse(mcpack.get("attachables/bedrockadder/nexo_elytra.json")).getAsJsonObject()
                .getAsJsonObject("minecraft:attachable").getAsJsonObject("description");
        assertEquals("geometry.elytra", elytra.getAsJsonObject("geometry").get("default").getAsString());
        assertEquals("controller.render.armor.player", elytra.getAsJsonArray("render_controllers").get(0).getAsString());
        assertTrue(mcpack.containsKey("textures/bedrockadder/armor/nexo_elytra.png"));

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

        JsonObject description = Json.parse(mcpack.get("attachables/bedrockadder/nexo_shield.json")).getAsJsonObject()
                .getAsJsonObject("minecraft:attachable").getAsJsonObject("description");
        assertTrue(description.getAsJsonObject("animations").has("thirdperson_main_hand_using"));
        assertTrue(description.getAsJsonObject("scripts").getAsJsonArray("pre_animation").toString().contains("v.using"));

        JsonObject animations = Json.parse(mcpack.get("animations/bedrockadder/nexo_shield.animation.json"))
                .getAsJsonObject().getAsJsonObject("animations");
        String prefix = "animation.bedrockadder.nexo_shield.";
        // Java mirrors the left hand: the right hand's y/z rotations and x translation are negated.
        JsonObject offX = animations.getAsJsonObject(prefix + "thirdperson_off_hand").getAsJsonObject("bones")
                .getAsJsonObject(dev.bedrockadder.common.bedrock.GeometryConverter.ITEM_ROOT + "_x");
        JsonObject mainX = animations.getAsJsonObject(prefix + "thirdperson_main_hand").getAsJsonObject("bones")
                .getAsJsonObject(dev.bedrockadder.common.bedrock.GeometryConverter.ITEM_ROOT + "_x");
        assertEquals(-mainX.getAsJsonArray("position").get(0).getAsFloat(),
                offX.getAsJsonArray("position").get(0).getAsFloat(), 1e-4);
        JsonObject usingX = animations.getAsJsonObject(prefix + "thirdperson_main_hand_using").getAsJsonObject("bones")
                .getAsJsonObject(dev.bedrockadder.common.bedrock.GeometryConverter.ITEM_ROOT + "_x");
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
