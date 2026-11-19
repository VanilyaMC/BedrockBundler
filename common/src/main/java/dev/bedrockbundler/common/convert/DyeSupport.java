package dev.bedrockbundler.common.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.bedrockbundler.common.ConversionLog;
import dev.bedrockbundler.common.Json;
import dev.bedrockbundler.common.java.JavaModel;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Tints of Java item models and what Bedrock can do with them.
 *
 * <p>Java 1.21.4+ item model definitions give a {@code model} node a list of {@code tints}; tint index N colours
 * every face with {@code tintindex: N} (for {@code item/generated} models layer N has tint index N). Bedrock
 * has two mechanisms, and nothing else:
 *
 * <ul>
 *   <li><b>Dyeable items</b> ({@code minecraft:dyeable}): the item's icon, and attachables through
 *       {@code query.armor_color_slot}, a grayscale texture and the {@code entity_alphatest_change_color}
 *       material, take the colour of the item's {@code customColor} tag, which Geyser fills from the Java
 *       {@code dyed_color} component. That is {@code minecraft:dye}, the only tint source Bedrock can follow.</li>
 *   <li>Fixed colours, baked into the texture at conversion time: {@code minecraft:constant}, and the default
 *       of every other source ({@code grass}, {@code firework}, {@code potion}, {@code map_color}, {@code team},
 *       {@code custom_model_data}), whose colour follows data Bedrock has no use for on a custom item.</li>
 * </ul>
 *
 * <p>Bedrock tints a whole icon or a whole attachable with the dye colour. Faces or layers that are not
 * dye tinted in Java are tinted too; the converter warns when a model has both.
 */
final class DyeSupport {

    /** Material that multiplies a (grayscale) texture with the colour of the item the attachable belongs to. */
    static final String DYED_MATERIAL = "entity_alphatest_change_color";
    /** Lowest attachable format version that supports the dyed material (Microsoft's dyeable item guide). */
    static final String ATTACHABLE_FORMAT = "1.21.30";
    static final String ITEM_CONTROLLER = "controller.render." + ConversionContext.NAMESPACE + ".item_dyed";
    static final String ARMOR_CONTROLLER = "controller.render." + ConversionContext.NAMESPACE + ".armor_dyed";
    private static final String CONTROLLER_FILE = "render_controllers/" + ConversionContext.NAMESPACE + "/dye.render_controllers.json";
    /** What Java's {@code grass} tint source gives around plains, used as the fixed colour on Bedrock. */
    private static final int GRASS = 0x91BD59;

    private DyeSupport() {
    }

    enum Kind {
        /** {@code minecraft:dye}: follows the item's dyed colour; {@code rgb} is the colour when undyed. */
        DYE,
        /** A colour baked into the texture. */
        FIXED
    }

    record Tint(Kind kind, int rgb) {
    }

    /** The tints of a model node, by tint index. Empty when there are none. */
    static List<Tint> parse(JsonObject node, ConversionLog log, String owner) {
        JsonArray array = Json.array(node, "tints");
        List<Tint> tints = new ArrayList<>();
        if (array == null) {
            return tints;
        }
        for (JsonElement element : array) {
            JsonObject tint = element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
            String type = Json.string(tint, "type", "").replace("minecraft:", "");
            switch (type) {
                case "dye" -> tints.add(new Tint(Kind.DYE, color(tint.get("default"), 0)));
                case "constant" -> tints.add(new Tint(Kind.FIXED, color(tint.get("value"), 0xFFFFFF)));
                case "grass" -> {
                    log.warn("Item " + owner + ": the 'grass' tint follows the biome on Java; Bedrock draws a fixed plains green");
                    tints.add(new Tint(Kind.FIXED, GRASS));
                }
                default -> {
                    log.warn("Item " + owner + ": tint source '" + type + "' follows item data Bedrock cannot read on a custom item; "
                            + "its default colour is used");
                    tints.add(new Tint(Kind.FIXED, color(tint.get("default"), 0xFFFFFF)));
                }
            }
        }
        return tints;
    }

    /**
     * An RGB colour from a Java colour value: an ARGB integer (the alpha byte is ignored; negative values are
     * ordinary) or a list of three components, as floats from 0 to 1 or as 0-255 numbers.
     */
    static int color(JsonElement element, int fallback) {
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return (int) element.getAsLong() & 0xFFFFFF;
        }
        if (element.isJsonArray() && element.getAsJsonArray().size() >= 3) {
            JsonArray array = element.getAsJsonArray();
            float[] c = {array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat()};
            float scale = c[0] > 1 || c[1] > 1 || c[2] > 1 ? 1 : 255;
            int rgb = 0;
            for (float value : c) {
                rgb = (rgb << 8) | Math.max(0, Math.min(255, Math.round(value * scale)));
            }
            return rgb;
        }
        return fallback;
    }

    static boolean hasDye(List<Tint> tints) {
        return tints.stream().anyMatch(t -> t.kind() == Kind.DYE);
    }

    // ------------------------------------------------------------------ textures

    /** Texture id suffixes: a texture drawn multiplied by a colour, and the grayscale one Bedrock tints. */
    private static final String TINT = "#tint=";
    private static final String DYE = "#dye=";
    private static final String GRAY = "#gray";

    /** Wraps a texture loader so it understands the ids {@link #plan} puts into a model. */
    static Function<String, BufferedImage> loader(Function<String, BufferedImage> base) {
        return id -> {
            int mark = id.indexOf('#');
            if (mark < 0) {
                return base.apply(id);
            }
            BufferedImage image = base.apply(id.substring(0, mark));
            if (image == null) {
                return null;
            }
            String how = id.substring(mark);
            if (how.equals(GRAY)) {
                return gray(image);
            }
            int rgb = Integer.parseInt(how.substring(how.indexOf('=') + 1), 16);
            return multiply(image, rgb);
        };
    }

    /**
     * An item model's faces sorted into dyed and fixed tints.
     *
     * @param undyed  the model as it looks undyed: tinted faces use textures multiplied by their (default) colour
     * @param dyed    the model for the dyed icon: dye faces use grayscale textures; null when no face is dyed
     * @param grayOf  for every texture id of a dye face in {@code undyed}, the plain texture to make grayscale
     * @param mixed   whether the model also has faces that a dye colour will tint although Java does not
     */
    record Plan(JavaModel undyed, JavaModel dyed, Map<String, String> grayOf, boolean mixed) {
    }

    /** Null when no face of the model is tinted. */
    static Plan plan(JavaModel model, List<Tint> tints, ConversionLog log, String owner) {
        boolean tinted = false;
        for (JavaModel.Element element : model.elements()) {
            for (JavaModel.Face face : element.faces().values()) {
                tinted |= tint(face, tints) != null;
            }
        }
        if (!tinted) {
            return null;
        }
        Map<String, String> grayOf = new LinkedHashMap<>();
        boolean dye = false;
        boolean untinted = false;
        boolean fixed = false;
        List<JavaModel.Element> undyed = new ArrayList<>();
        List<JavaModel.Element> dyed = new ArrayList<>();
        for (JavaModel.Element element : model.elements()) {
            Map<String, JavaModel.Face> undyedFaces = new LinkedHashMap<>();
            Map<String, JavaModel.Face> dyedFaces = new LinkedHashMap<>();
            for (Map.Entry<String, JavaModel.Face> entry : element.faces().entrySet()) {
                JavaModel.Face face = entry.getValue();
                Tint tint = tint(face, tints);
                if (tint == null || face.texture() == null) {
                    untinted |= face.texture() != null;
                    undyedFaces.put(entry.getKey(), face);
                    dyedFaces.put(entry.getKey(), face);
                } else if (tint.kind() == Kind.DYE) {
                    dye = true;
                    String key = face.texture() + DYE + hex(tint.rgb());
                    grayOf.put(key, face.texture());
                    undyedFaces.put(entry.getKey(), with(face, key));
                    dyedFaces.put(entry.getKey(), with(face, face.texture() + GRAY));
                } else {
                    fixed |= tint.rgb() != 0xFFFFFF;
                    String key = tint.rgb() == 0xFFFFFF ? face.texture() : face.texture() + TINT + hex(tint.rgb());
                    undyedFaces.put(entry.getKey(), with(face, key));
                    dyedFaces.put(entry.getKey(), with(face, key));
                }
            }
            undyed.add(new JavaModel.Element(element.from(), element.to(), element.rotation(), undyedFaces));
            dyed.add(new JavaModel.Element(element.from(), element.to(), element.rotation(), dyedFaces));
        }
        boolean mixed = dye && (untinted || fixed);
        if (mixed) {
            log.warn("Item " + owner + ": a dye colour tints the whole model on Bedrock, including the faces that Java draws "
                    + "untinted or with a fixed tint");
        }
        return new Plan(new JavaModel(model.kind(), model.textures(), undyed, model.display()),
                dye ? new JavaModel(model.kind(), model.textures(), dyed, model.display()) : null, grayOf, mixed);
    }

    private static Tint tint(JavaModel.Face face, List<Tint> tints) {
        return face.tintIndex() >= 0 && face.tintIndex() < tints.size() ? tints.get(face.tintIndex()) : null;
    }

    private static JavaModel.Face with(JavaModel.Face face, String texture) {
        return new JavaModel.Face(face.uv(), texture, face.textureVar(), face.rotation(), face.tintIndex());
    }

    /**
     * The sprite of a flat item: layer N is drawn with tint N. Dye tinted layers are multiplied by their colour
     * when undyed, or made grayscale when {@code dyed} (the icon Bedrock tints).
     */
    static BufferedImage layer(BufferedImage image, Tint tint, boolean dyed) {
        if (tint == null) {
            return image;
        }
        if (tint.kind() == Kind.DYE && dyed) {
            return gray(image);
        }
        return tint.rgb() == 0xFFFFFF ? image : multiply(image, tint.rgb());
    }

    static String hex(int rgb) {
        return String.format(Locale.ROOT, "%06X", rgb & 0xFFFFFF);
    }

    /** Java's tint: every colour channel multiplied by the tint's, alpha kept. Never changes {@code source}. */
    static BufferedImage multiply(BufferedImage source, int rgb) {
        int tr = (rgb >> 16) & 0xFF;
        int tg = (rgb >> 8) & 0xFF;
        int tb = rgb & 0xFF;
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int argb = source.getRGB(x, y);
                int r = Math.round(((argb >> 16) & 0xFF) * tr / 255f);
                int g = Math.round(((argb >> 8) & 0xFF) * tg / 255f);
                int b = Math.round((argb & 0xFF) * tb / 255f);
                result.setRGB(x, y, (argb & 0xFF000000) | (r << 16) | (g << 8) | b);
            }
        }
        return result;
    }

    /** Luminance of every pixel, alpha kept: the texture Bedrock multiplies with the dye colour. */
    static BufferedImage gray(BufferedImage source) {
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int argb = source.getRGB(x, y);
                int l = Math.round(0.299f * ((argb >> 16) & 0xFF) + 0.587f * ((argb >> 8) & 0xFF) + 0.114f * (argb & 0xFF));
                result.setRGB(x, y, (argb & 0xFF000000) | (l << 16) | (l << 8) | l);
            }
        }
        return result;
    }

    /** {@code top} drawn over {@code base} (scaled to its size); a new image. */
    static BufferedImage over(BufferedImage base, BufferedImage top) {
        BufferedImage result = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = result.createGraphics();
        graphics.drawImage(base, 0, 0, null);
        graphics.drawImage(top, 0, 0, base.getWidth(), base.getHeight(), null);
        graphics.dispose();
        return result;
    }

    /**
     * The dyed variant of a 3D item's atlas: the atlas of the undyed look with the slots of dye tinted faces
     * replaced by their grayscale texture.
     */
    static BufferedImage dyedAtlas(dev.bedrockbundler.common.bedrock.TextureAtlas atlas, Map<String, String> grayOf,
                                   Function<String, BufferedImage> base) {
        BufferedImage result = new BufferedImage(atlas.width(), atlas.height(), BufferedImage.TYPE_INT_ARGB);
        result.getGraphics().drawImage(atlas.image(), 0, 0, null);
        for (Map.Entry<String, String> entry : grayOf.entrySet()) {
            dev.bedrockbundler.common.bedrock.TextureAtlas.Slot slot = atlas.slot(entry.getKey());
            BufferedImage plain = base.apply(entry.getValue());
            if (slot == null || plain == null) {
                continue;
            }
            BufferedImage gray = gray(plain);
            for (int y = 0; y < slot.height(); y++) {
                for (int x = 0; x < slot.width(); x++) {
                    result.setRGB(slot.x() + x, slot.y() + y, gray.getRGB(x, y));
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ attachables

    /**
     * Molang that works out, for an attachable, whether its item carries a dye colour. Bedrock gives an
     * attachable the colour only through the wearer's armor slots ({@code query.armor_color_slot}, 1.0 on every
     * channel when undyed): in a hand there is no slot, so a held item always looks undyed.
     *
     * @param armorSlot armor slot index the item is worn in (0 head, 1 chest, 2 legs, 3 feet), or -1
     */
    static List<String> preAnimation(int armorSlot) {
        return List.of(
                // A stand wears furniture on its head, whatever the item's own slot is.
                "v.dye_slot = v.head ? 0 : (v.main_hand || v.off_hand) ? -1 : " + armorSlot + ";",
                "v.is_dyed = v.dye_slot >= 0 && (q.armor_color_slot(v.dye_slot, 0) != 1.0"
                        + " || q.armor_color_slot(v.dye_slot, 1) != 1.0 || q.armor_color_slot(v.dye_slot, 2) != 1.0);");
    }

    /** Writes the render controllers of dyed attachables once: one for held 3D models, one for worn armor. */
    static void writeControllers(ConversionContext ctx) {
        if (ctx.bedrockPack().has(CONTROLLER_FILE)) {
            return;
        }
        JsonObject item = new JsonObject();
        item.addProperty("geometry", "Geometry.default");
        JsonArray itemMaterials = new JsonArray();
        itemMaterials.add(ItemConverter.stringMap("*",
                "variable.is_dyed ? Material.dyed : (variable.is_enchanted ? Material.enchanted : Material.default)"));
        item.add("materials", itemMaterials);
        JsonArray itemTextures = new JsonArray();
        itemTextures.add("variable.is_dyed ? Texture.dyed : Texture.default");
        itemTextures.add("Texture.enchanted");
        item.add("textures", itemTextures);

        JsonObject armor = new JsonObject();
        armor.addProperty("geometry", "Geometry.armor");
        JsonArray armorMaterials = new JsonArray();
        armorMaterials.add(ItemConverter.stringMap("*",
                "variable.is_dyed ? Material.armor_dyed : (variable.is_enchanted ? Material.armor_enchanted : Material.armor)"));
        armor.add("materials", armorMaterials);
        JsonArray armorTextures = new JsonArray();
        armorTextures.add("variable.is_dyed ? Texture.armor_dyed : Texture.armor");
        armorTextures.add("Texture.armor_glint");
        armor.add("textures", armorTextures);

        JsonObject controllers = new JsonObject();
        controllers.add(ITEM_CONTROLLER, item);
        controllers.add(ARMOR_CONTROLLER, armor);
        JsonObject file = new JsonObject();
        file.addProperty("format_version", "1.8.0");
        file.add("render_controllers", controllers);
        ctx.bedrockPack().json(CONTROLLER_FILE, file);
    }
}
