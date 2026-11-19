package dev.bedrockbundler.common.convert;

import dev.bedrockbundler.common.ConversionLog;
import dev.bedrockbundler.common.ResourceId;
import dev.bedrockbundler.common.bedrock.Textures;
import dev.bedrockbundler.common.input.IaItem;
import dev.bedrockbundler.common.input.PaintingVariant;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Paintings drawn like furniture: FurnitureMirror hides the painting entity from Bedrock players and puts up a
 * stand wearing an item whose model is the painting, a 1 px thick board of {@code width x height} blocks centred on
 * the painting entity's position, the front (north face) showing the painting. That item is made here as a Java
 * model and item definition added to the pack, then converted like any other 3D item.
 *
 * <p>Custom paintings: Bedrock has a fixed set of motives. Vanilla paintings: Geyser draws them, but its
 * PaintingEntity places them half a block off once the server sends the painting a move (it does not correct
 * moves the way it corrects the spawn position); they are drawn from the Bedrock client's own painting textures
 * ({@link #VANILLA}), which are referenced, not copied into the pack.
 */
public final class PaintingConverter {

    static final String BACK_TEXTURE = ConversionContext.NAMESPACE + ":painting_back";
    /** Texture ids standing for a texture of the Bedrock client ({@link ItemConverter.ExternalTexture}). */
    static final String BEDROCK_TEXTURE = ConversionContext.NAMESPACE + ":bedrock/painting/";

    /** A vanilla motive in a Bedrock painting texture: the texture, its size, and the motive's pixel rectangle. */
    record Motive(String file, int textureWidth, int textureHeight, int u, int v, int width, int height) {

        /** Paintings of the {@code kz} atlas have their back in it too; the others show their own picture. */
        int[] back() {
            return file.equals("kz") ? new int[]{192, 0, width, height} : new int[]{u, v, width, height};
        }
    }

    /**
     * Vanilla motives as the Bedrock client stores them: {@code textures/painting/kz.png} (256 x 256, 16 px a
     * block, the classic layout plus earth, wind, fire and water, the back at 192, 0), and one file per motive
     * since 1.21 (vanilla_1.21.0; dennis in vanilla_1.21.100).
     */
    static final Map<String, Motive> VANILLA = vanilla();

    private static Map<String, Motive> vanilla() {
        Map<String, Motive> motives = new java.util.HashMap<>();
        Object[][] kz = {
                {"kebab", 0, 0, 1, 1}, {"aztec", 16, 0, 1, 1}, {"alban", 32, 0, 1, 1}, {"aztec2", 48, 0, 1, 1},
                {"bomb", 64, 0, 1, 1}, {"plant", 80, 0, 1, 1}, {"wasteland", 96, 0, 1, 1},
                {"pool", 0, 32, 2, 1}, {"courbet", 32, 32, 2, 1}, {"sea", 64, 32, 2, 1}, {"sunset", 96, 32, 2, 1},
                {"creebet", 128, 32, 2, 1}, {"wanderer", 0, 64, 1, 2}, {"graham", 16, 64, 1, 2},
                {"fighters", 0, 96, 4, 2}, {"match", 0, 128, 2, 2}, {"bust", 32, 128, 2, 2}, {"stage", 64, 128, 2, 2},
                {"void", 96, 128, 2, 2}, {"skull_and_roses", 128, 128, 2, 2}, {"wither", 160, 128, 2, 2},
                {"earth", 0, 160, 2, 2}, {"wind", 32, 160, 2, 2}, {"fire", 64, 160, 2, 2}, {"water", 96, 160, 2, 2},
                {"pointer", 0, 192, 4, 4}, {"pigscene", 64, 192, 4, 4}, {"burning_skull", 128, 192, 4, 4},
                {"skeleton", 192, 64, 4, 3}, {"donkey_kong", 192, 112, 4, 3}};
        for (Object[] m : kz) {
            motives.put("minecraft:" + m[0],
                    new Motive("kz", 256, 256, (int) m[1], (int) m[2], 16 * (int) m[3], 16 * (int) m[4]));
        }
        Object[][] files = {
                {"backyard", 3, 4}, {"baroque", 2, 2}, {"bouquet", 3, 3}, {"cavebird", 3, 3}, {"changing", 4, 2},
                {"cotan", 3, 3}, {"dennis", 3, 3}, {"endboss", 3, 3}, {"fern", 3, 3}, {"finding", 4, 2},
                {"humble", 2, 2}, {"lowmist", 4, 2}, {"meditative", 1, 1}, {"orb", 4, 4}, {"owlemons", 3, 3},
                {"passage", 4, 2}, {"pond", 3, 4}, {"prairie_ride", 1, 2}, {"sunflowers", 3, 3}, {"tides", 3, 3},
                {"unpacked", 4, 4}};
        for (Object[] m : files) {
            int w = 16 * (int) m[1];
            int h = 16 * (int) m[2];
            motives.put("minecraft:" + m[0], new Motive((String) m[0], w, h, 0, 0, w, h));
        }
        return Map.copyOf(motives);
    }

    /** Whether FurnitureMirror draws this painting variant with a stand: custom ones and known vanilla ones. */
    public static boolean mirrored(String variantId) {
        return !variantId.startsWith("minecraft:") || VANILLA.containsKey(variantId);
    }

    private PaintingConverter() {
    }

    /** The item_model the stand's item carries for a painting variant; FurnitureMirror sets the same. */
    public static String itemModel(String variantId) {
        ResourceId id = ResourceId.parse(variantId);
        return ConversionContext.NAMESPACE + ":painting/" + id.namespace() + "/" + id.path();
    }

    /**
     * Adds the models of the paintings to {@code pack} and returns their items. Vanilla motives are drawn from a
     * Bedrock texture, put in {@code external}.
     */
    static List<IaItem> items(List<PaintingVariant> paintings, Map<String, byte[]> pack, ConversionLog log,
                              Map<String, ItemConverter.ExternalTexture> external) {
        List<IaItem> items = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (PaintingVariant painting : paintings) {
            ResourceId id = ResourceId.parse(painting.id());
            if (id.namespace().equals("minecraft")) {
                Motive motive = VANILLA.get(painting.id());
                if (motive != null) {
                    String texture = BEDROCK_TEXTURE + motive.file();
                    external.put(texture, new ItemConverter.ExternalTexture("textures/painting/" + motive.file(),
                            motive.textureWidth(), motive.textureHeight()));
                    items.add(item(id, pack, vanillaModel(texture, motive)));
                }
                continue;
            }
            ResourceId asset = ResourceId.parse(painting.asset());
            String texture = "assets/" + asset.namespace() + "/textures/painting/" + asset.path() + ".png";
            if (!pack.containsKey(texture) || painting.width() <= 0 || painting.height() <= 0) {
                missing.add(painting.id());
                continue;
            }
            items.add(item(id, pack, model(asset.namespace() + ":painting/" + asset.path(), painting.width(),
                    painting.height())));
        }
        if (!missing.isEmpty()) {
            // ItemsAdderAdditions registers dozens of texture-less variants for its creative menu (ia_creative:*).
            log.warn(missing.size() + " painting variants have no texture in the pack and are skipped, e.g. "
                    + String.join(", ", missing.subList(0, Math.min(3, missing.size()))));
        }
        if (!items.isEmpty()) {
            pack.put("assets/" + ConversionContext.NAMESPACE + "/textures/painting_back.png", Textures.png(back()));
        }
        return items;
    }

    private static IaItem item(ResourceId id, Map<String, byte[]> pack, String modelJson) {
        ResourceId model = ResourceId.parse(itemModel(id.toString()));
        pack.put("assets/" + model.namespace() + "/items/" + model.path() + ".json", bytes(
                "{\"model\": {\"type\": \"model\", \"model\": \"" + model + "\"}}"));
        pack.put("assets/" + model.namespace() + "/models/" + model.path() + ".json", bytes(modelJson));
        return new IaItem(id.namespace() + ":painting_" + id.path().replace('/', '_'), "minecraft:paper",
                model.toString(), null, "Painting " + id.path(), null, null, null, null, 0, null, false, true);
    }

    /** A vanilla motive's board, every face cut from the one Bedrock texture (UVs in Java's 0-16 space). */
    static String vanillaModel(String texture, Motive motive) {
        float x1 = 8 - motive.width() / 2f;
        float x2 = 8 + motive.width() / 2f;
        float y1 = 8 - motive.height() / 2f;
        float y2 = 8 + motive.height() / 2f;
        int[] back = motive.back();
        String front = face(motive, motive.u(), motive.v(), motive.width(), motive.height());
        String rear = face(motive, back[0], back[1], back[2], back[3]);
        String side = face(motive, back[0], back[1], 1, back[3]);
        String flat = face(motive, back[0], back[1], back[2], 1);
        return String.format(Locale.ROOT, """
                {"textures": {"0": "%s", "particle": "%s"},
                 "elements": [{"from": [%s, %s, 7.5], "to": [%s, %s, 8.5], "faces": {
                   "north": %s, "south": %s, "east": %s, "west": %s, "up": %s, "down": %s}}]}""",
                texture, texture, x1, y1, x2, y2, front, rear, side, side, flat, flat);
    }

    private static String face(Motive motive, int u, int v, int width, int height) {
        float sx = 16f / motive.textureWidth();
        float sy = 16f / motive.textureHeight();
        return String.format(Locale.ROOT, "{\"uv\": [%s, %s, %s, %s], \"texture\": \"#0\"}",
                u * sx, v * sy, (u + width) * sx, (v + height) * sy);
    }

    /** The board: x along the wall, y up, the front facing north (-z), 1 px thick, centred on the model centre. */
    static String model(String texture, int width, int height) {
        float x1 = 8 - width * 8f, x2 = 8 + width * 8f;
        float y1 = 8 - height * 8f, y2 = 8 + height * 8f;
        String edge = "{\"uv\": [0, 0, 1, 16], \"texture\": \"#back\"}";
        return String.format(Locale.ROOT, """
                {"textures": {"front": "%s", "back": "%s", "particle": "%s"},
                 "elements": [{"from": [%s, %s, 7.5], "to": [%s, %s, 8.5], "faces": {
                   "north": {"uv": [0, 0, 16, 16], "texture": "#front"},
                   "south": {"uv": [0, 0, 16, 16], "texture": "#back"},
                   "east": %s, "west": %s, "up": %s, "down": %s}}]}""",
                texture, BACK_TEXTURE, texture, x1, y1, x2, y2, edge, edge, edge, edge);
    }

    /** Plain wooden boards, like the vanilla painting back. */
    private static BufferedImage back() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                boolean seam = y % 4 == 3;
                image.setRGB(x, y, seam ? 0xFF6B4A2B : ((x * 7 + y * 3) % 5 == 0 ? 0xFF8F6A3F : 0xFF9C7547));
            }
        }
        return image;
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
