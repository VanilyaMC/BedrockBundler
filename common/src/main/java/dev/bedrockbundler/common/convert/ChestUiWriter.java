package dev.bedrockbundler.common.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.bedrockbundler.common.bedrock.Textures;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GUI backgrounds for chest menus. ItemsAdder puts a large glyph in the inventory title and moves it with
 * negative offsets so it covers the window; Bedrock text can neither be that large nor move backwards.
 *
 * <p>Instead the Bedrock chest screen draws each background as an image in the window's root panel, visible
 * while the title contains its glyph. The glyph itself is invisible in the glyph sheet, so the title text still
 * reads normally. Only the classic (desktop) layout is changed; the pocket (touch) layout keeps its look.
 *
 * <p>The visibility test, {@code (not ($atext - 'glyph' = $atext))} with {@code $atext} copied from
 * {@code $container_title}, is the one community chest overlays for Geyser use: removing the glyph from the
 * title changes it only when the title contains the glyph.
 */
final class ChestUiWriter {

    /** A GUI glyph: Java font height (the drawn height) and ascent. */
    record Background(int codePoint, BufferedImage image, double height, double ascent) {
    }

    /*
     * Java draws a container title at (8, 6) of the window and a glyph's top 7 - ascent below the text top.
     * Bedrock's classic chest window has the same width and slot grid as Java, but its slots sit 4 px lower.
     */
    private static final int JAVA_TITLE_X = 8;
    private static final int JAVA_WINDOW_WIDTH = 176;
    private static final int JAVA_TITLE_Y = 6;
    private static final int JAVA_TEXT_BASELINE = 7;
    private static final int BEDROCK_SLOT_SHIFT_Y = 4;
    /** Above the slot backgrounds, below the item icons (layers add up along the control tree). */
    private static final int OVERLAY_LAYER = 11;

    private final ConversionContext ctx;

    ChestUiWriter(ConversionContext ctx) {
        this.ctx = ctx;
    }

    void write(List<Background> backgrounds) {
        JsonObject file = new JsonObject();
        file.addProperty("namespace", "chest");
        // The vanilla title sits at (7, 11) of the small window and (7, 10) of the large one.
        file.add("small_chest_panel", chestPanel("small", null, overlays(backgrounds, 11)));
        file.add("large_chest_panel", chestPanel("large", array(176, 220), overlays(backgrounds, 10)));
        ctx.bedrockPack().json("ui/chest_screen.json", file);
    }

    /**
     * Per background: the image, plus the title drawn again above it (the vanilla title label is on a lower
     * layer and would be hidden). Both show only while the title contains the background's glyph.
     */
    private JsonArray overlays(List<Background> backgrounds, int titleY) {
        JsonArray overlays = new JsonArray();
        Map<Integer, Integer> offsets = offsets(backgrounds);
        for (Background background : backgrounds) {
            String texture = String.format("textures/%s/gui/u%04x", ConversionContext.NAMESPACE, background.codePoint());
            ctx.bedrockPack().file(texture + ".png", Textures.png(background.image()));

            int height = (int) Math.round(background.height());
            int width = (int) Math.round(background.image().getWidth() * background.height() / background.image().getHeight());
            int x = JAVA_TITLE_X + offsets.get(background.codePoint());
            int y = JAVA_TITLE_Y + JAVA_TEXT_BASELINE - (int) Math.round(background.ascent()) + BEDROCK_SLOT_SHIFT_Y;
            String glyph = new String(Character.toChars(background.codePoint()));

            JsonObject image = new JsonObject();
            image.addProperty("type", "image");
            image.addProperty("texture", texture);
            image.addProperty("layer", OVERLAY_LAYER);
            image.add("size", array(width, height));
            image.add("offset", array(x, y));
            image.addProperty("anchor_from", "top_left");
            image.addProperty("anchor_to", "top_left");
            String visible = "(not ($atext - '" + glyph + "' = $atext))";
            image.addProperty("$atext", "$container_title");
            image.addProperty("visible", visible);
            overlays.add(named(String.format("bedrockbundler_gui_%04x", background.codePoint()), image));

            JsonObject title = new JsonObject();
            title.addProperty("type", "label");
            title.addProperty("layer", OVERLAY_LAYER + 1);
            title.add("offset", array(7, titleY));
            title.add("size", strings("90%", "default"));
            title.addProperty("anchor_from", "top_left");
            title.addProperty("anchor_to", "top_left");
            title.addProperty("text", "$container_title");
            title.addProperty("color", "$title_text_color");
            title.addProperty("localize", "$localize_title");
            title.addProperty("enable_profanity_filter", true);
            title.addProperty("$atext", "$container_title");
            title.addProperty("visible", visible);
            overlays.add(named(String.format("bedrockbundler_gui_%04x_title", background.codePoint()), title));
        }
        return overlays;
    }

    /** Visible pixel bounds {minX, minY, maxX, maxY}, or null for an empty image. */
    private static int[] bounds(BufferedImage image) {
        int[] box = null;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    box = box == null ? new int[]{x, y, x, y}
                            : new int[]{Math.min(box[0], x), Math.min(box[1], y), Math.max(box[2], x), Math.max(box[3], y)};
                }
            }
        }
        return box;
    }

    /**
     * Horizontal offset per background. Explicit ones come from the menu titles of the content plugin. A plugin
     * that gives any (Nexo) also builds some titles in code; for those a glyph that looks the same as one with a
     * known offset (same size, ascent and visible area) shares it, and a texture whose visible part is exactly as
     * wide as the chest window is lined up with the window. Everything else uses the configured default.
     */
    private Map<Integer, Integer> offsets(List<Background> backgrounds) {
        ConversionContext.Options options = ctx.options();
        Map<Integer, Integer> offsets = new HashMap<>();
        Map<String, Integer> byShape = new HashMap<>();
        Map<Integer, String> shapes = new HashMap<>();
        for (Background background : backgrounds) {
            int[] box = bounds(background.image());
            String shape = background.height() + "/" + background.ascent() + "/" + background.image().getWidth()
                    + "x" + background.image().getHeight() + "/" + Arrays.toString(box);
            shapes.put(background.codePoint(), shape);
            Integer explicit = options.guiOffsets().get(background.codePoint());
            if (explicit != null) {
                offsets.put(background.codePoint(), explicit);
                byShape.putIfAbsent(shape, explicit);
            }
        }
        for (Background background : backgrounds) {
            int codePoint = background.codePoint();
            if (offsets.containsKey(codePoint)) {
                continue;
            }
            Integer offset = options.guiOffsets().isEmpty() ? null : byShape.get(shapes.get(codePoint));
            if (offset == null && !options.guiOffsets().isEmpty()) {
                int[] box = bounds(background.image());
                double scale = background.height() / background.image().getHeight();
                if (box != null && Math.round((box[2] - box[0] + 1) * scale) == JAVA_WINDOW_WIDTH) {
                    offset = -JAVA_TITLE_X - (int) Math.round(box[0] * scale);
                }
            }
            offsets.put(codePoint, offset != null ? offset : options.guiOffsetX());
        }
        return offsets;
    }

    /**
     * The classic chest panel with the overlays added to its root panel. A pack's element replaces the vanilla
     * element's arrays, so the vanilla controls are listed again unchanged.
     */
    private static JsonObject chestPanel(String size, JsonArray rootSize, JsonArray overlays) {
        JsonArray chestControls = new JsonArray();
        chestControls.add(named(size + "_chest_panel_top_half@chest." + size + "_chest_panel_top_half", new JsonObject()));
        chestControls.add(named("inventory_panel_bottom_half_with_label@common.inventory_panel_bottom_half_with_label", new JsonObject()));
        chestControls.add(named("hotbar_grid@common.hotbar_grid_template", new JsonObject()));
        chestControls.add(named("inventory_take_progress_icon_button@common.inventory_take_progress_icon_button", new JsonObject()));
        JsonObject flying = new JsonObject();
        flying.addProperty("layer", 15);
        chestControls.add(named("flying_item_renderer@common.flying_item_renderer", flying));

        JsonObject chestPanel = new JsonObject();
        chestPanel.addProperty("type", "panel");
        chestPanel.addProperty("layer", 5);
        chestPanel.add("controls", chestControls);

        JsonArray rootControls = new JsonArray();
        rootControls.add(named("common_panel@common.common_panel", new JsonObject()));
        rootControls.add(named("chest_panel", chestPanel));
        rootControls.add(named("inventory_selected_icon_button@common.inventory_selected_icon_button", new JsonObject()));
        rootControls.add(named("gamepad_cursor@common.gamepad_cursor_button", new JsonObject()));
        rootControls.addAll(overlays);

        JsonObject root = new JsonObject();
        root.addProperty("layer", 1);
        if (rootSize != null) {
            root.add("size", rootSize);
        }
        root.add("controls", rootControls);

        JsonArray controls = new JsonArray();
        controls.add(named("container_gamepad_helpers@common.container_gamepad_helpers", new JsonObject()));
        JsonObject details = new JsonObject();
        JsonObject lock = new JsonObject();
        if (size.equals("large")) {
            details.addProperty("control_name", "@chest.selected_item_details");
            lock.addProperty("control_name", "@common.item_lock_notification");
        }
        controls.add(named("selected_item_details_factory@common.selected_item_details_factory", details));
        controls.add(named("item_lock_notification_factory@common.item_lock_notification_factory", lock));
        controls.add(named("root_panel@common.root_panel", root));

        JsonObject panel = new JsonObject();
        panel.addProperty("type", "panel");
        panel.add("controls", controls);
        return panel;
    }

    private static JsonObject named(String name, JsonObject control) {
        JsonObject wrapper = new JsonObject();
        wrapper.add(name, control);
        return wrapper;
    }

    private static JsonArray strings(String a, String b) {
        JsonArray array = new JsonArray();
        array.add(a);
        array.add(b);
        return array;
    }

    private static JsonArray array(int a, int b) {
        JsonArray array = new JsonArray();
        array.add(a);
        array.add(b);
        return array;
    }
}
