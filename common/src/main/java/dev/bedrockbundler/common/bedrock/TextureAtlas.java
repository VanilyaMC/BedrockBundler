package dev.bedrockbundler.common.bedrock;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Packs several textures into one image. Bedrock attachables take a single texture, while Java models
 * reference one texture per face.
 */
public final class TextureAtlas {

    public record Slot(int x, int y, int width, int height) {
    }

    private final BufferedImage image;
    private final Map<String, Slot> slots;

    private TextureAtlas(BufferedImage image, Map<String, Slot> slots) {
        this.image = image;
        this.slots = slots;
    }

    /** Missing textures (null images) get a magenta placeholder so broken faces are obvious in game. */
    public static TextureAtlas build(Map<String, BufferedImage> textures) {
        Map<String, BufferedImage> images = new LinkedHashMap<>();
        textures.forEach((id, image) -> images.put(id, image != null ? image : placeholder()));

        List<Map.Entry<String, BufferedImage>> sorted = new ArrayList<>(images.entrySet());
        sorted.sort(Comparator.comparingInt((Map.Entry<String, BufferedImage> e) -> e.getValue().getHeight()).reversed());

        long area = 0;
        int widest = 1;
        for (BufferedImage image : images.values()) {
            area += (long) image.getWidth() * image.getHeight();
            widest = Math.max(widest, image.getWidth());
        }
        int width = Integer.highestOneBit(Math.max(widest, (int) Math.ceil(Math.sqrt(area))) - 1) << 1;
        width = Math.max(width, widest);

        // Shelf packing: rows of textures sorted by height.
        Map<String, Slot> slots = new LinkedHashMap<>();
        int x = 0;
        int y = 0;
        int rowHeight = 0;
        for (Map.Entry<String, BufferedImage> entry : sorted) {
            BufferedImage image = entry.getValue();
            if (x + image.getWidth() > width) {
                x = 0;
                y += rowHeight;
                rowHeight = 0;
            }
            slots.put(entry.getKey(), new Slot(x, y, image.getWidth(), image.getHeight()));
            x += image.getWidth();
            rowHeight = Math.max(rowHeight, image.getHeight());
        }
        int height = Math.max(1, y + rowHeight);

        BufferedImage atlas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (Map.Entry<String, Slot> entry : slots.entrySet()) {
            Slot slot = entry.getValue();
            atlas.getGraphics().drawImage(images.get(entry.getKey()), slot.x(), slot.y(), null);
        }
        return new TextureAtlas(atlas, slots);
    }

    /**
     * One texture the pack does not carry (a texture of the Bedrock client), as an atlas of exactly its size:
     * faces get UVs in its pixels, and the image stays blank and is not written.
     */
    public static TextureAtlas external(String textureId, int width, int height) {
        return new TextureAtlas(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB),
                Map.of(textureId, new Slot(0, 0, width, height)));
    }

    static BufferedImage placeholder() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, ((x / 8 + y / 8) % 2 == 0) ? 0xFFF800F8 : 0xFF000000);
            }
        }
        return image;
    }

    public BufferedImage image() {
        return image;
    }

    public Slot slot(String textureId) {
        return slots.get(textureId);
    }

    public int width() {
        return image.getWidth();
    }

    public int height() {
        return image.getHeight();
    }
}
