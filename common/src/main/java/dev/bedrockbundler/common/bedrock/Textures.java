package dev.bedrockbundler.common.bedrock;

import dev.bedrockbundler.common.ConversionLog;
import dev.bedrockbundler.common.ResourceId;
import dev.bedrockbundler.common.java.JavaPack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

/** Loads Java textures as images. Animated textures are reduced to their first frame. */
public final class Textures {

    private final JavaPack pack;
    private final ConversionLog log;
    private final Map<String, BufferedImage> cache = new HashMap<>();

    public Textures(JavaPack pack, ConversionLog log) {
        this.pack = pack;
        this.log = log;
    }

    /** True when the texture is part of vanilla Minecraft and therefore not in the custom pack. */
    public boolean isVanilla(String textureId) {
        ResourceId id = ResourceId.parse(textureId);
        return id.namespace().equals("minecraft") && !pack.has(id.asset("textures", ".png"));
    }

    /** Returns the image, or null (with a warning) when it is missing or unreadable. */
    public BufferedImage load(String textureId) {
        if (cache.containsKey(textureId)) {
            return cache.get(textureId);
        }
        ResourceId id = ResourceId.parse(textureId);
        String path = id.asset("textures", ".png");
        byte[] bytes = pack.bytes(path);
        BufferedImage image = null;
        if (bytes == null) {
            if (!isVanilla(textureId)) {
                log.warn("Missing texture " + textureId);
            }
        } else {
            try {
                image = toArgb(read(bytes));
                if (image != null && pack.has(path + ".mcmeta") && image.getHeight() > image.getWidth()) {
                    image = image.getSubimage(0, 0, image.getWidth(), image.getWidth());
                }
            } catch (IOException e) {
                log.warn("Unreadable texture " + textureId + ": " + e.getMessage());
            }
        }
        cache.put(textureId, image);
        return image;
    }

    private static BufferedImage read(byte[] bytes) throws IOException {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            // ItemsAdder's pack protection writes PNGs that Minecraft accepts but strict decoders reject.
            return ImageIO.read(new ByteArrayInputStream(repairPng(bytes)));
        }
    }

    /**
     * Rebuilds a PNG from its critical chunks: the image data is inflated without verifying the zlib
     * checksum, then re-deflated, and every chunk gets a correct CRC. Unknown ancillary chunks are dropped.
     */
    static byte[] repairPng(byte[] png) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(png);
        if (png.length < 8 || in.getLong() != 0x89504E470D0A1A0AL) {
            throw new IOException("not a PNG");
        }
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        byte[] header = null;
        while (in.remaining() >= 12) {
            int length = in.getInt();
            byte[] type = new byte[4];
            in.get(type);
            if (length < 0 || length > in.remaining() - 4) {
                throw new IOException("corrupt PNG chunk");
            }
            byte[] data = new byte[length];
            in.get(data);
            in.getInt(); // CRC, recomputed below
            String name = new String(type, StandardCharsets.ISO_8859_1);
            switch (name) {
                case "IHDR" -> header = data;
                case "IDAT" -> idat.write(data);
                case "PLTE", "tRNS" -> writeChunk(kept, name, data);
                case "IEND" -> in.position(in.limit());
                default -> {
                }
            }
        }
        byte[] compressed = idat.toByteArray();
        if (header == null || compressed.length < 2) {
            throw new IOException("PNG without image data");
        }

        Inflater inflater = new Inflater(true);
        inflater.setInput(compressed, 2, compressed.length - 2); // skip the zlib header
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                raw.write(buffer, 0, count);
            }
        } catch (DataFormatException e) {
            throw new IOException("PNG image data is corrupt", e);
        } finally {
            inflater.end();
        }

        ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(deflated)) {
            raw.writeTo(deflater);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, 8);
        writeChunk(out, "IHDR", header);
        kept.writeTo(out);
        writeChunk(out, "IDAT", deflated.toByteArray());
        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        ByteBuffer chunk = ByteBuffer.allocate(12 + data.length);
        chunk.putInt(data.length).put(typeBytes).put(data).putInt((int) crc.getValue());
        out.writeBytes(chunk.array());
    }

    private static BufferedImage toArgb(BufferedImage image) {
        if (image == null || image.getType() == BufferedImage.TYPE_INT_ARGB) {
            return image;
        }
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        copy.getGraphics().drawImage(image, 0, 0, null);
        return copy;
    }

    public static boolean hasTransparency(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0xFF) {
                    return true;
                }
            }
        }
        return false;
    }

    public static byte[] png(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
