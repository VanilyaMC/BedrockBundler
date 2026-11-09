package dev.bedrockbundler.common.bedrock;

import dev.bedrockbundler.common.ConversionLog;
import dev.bedrockbundler.common.java.JavaPack;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TexturesTest {

    /**
     * A 16x16 PNG as written by ItemsAdder's pack protection: bad zlib checksum in IDAT, a custom
     * {@code iaPR} chunk and a wrong IEND CRC. Minecraft loads it; plain ImageIO does not.
     */
    private static final byte[] PROTECTED_PNG = HexFormat.of().parseHex(
            "89504e470d0a1a0a0000000d4948445200000010000000100806000000ba300e1a0000002049444154785e6348108cff4f09"
                    + "6600110fc8c4a3068c1a306a00b50da004030016264d925c0ae8a000000004696150524941500193e22a7600000000"
                    + "49454e440b8191f9");

    @Test
    void readsProtectedItemsAdderPng() {
        ConversionLog log = new ConversionLog();
        Textures textures = new Textures(new JavaPack(Map.of("assets/test/textures/item/ruby.png", PROTECTED_PNG)), log);
        BufferedImage image = textures.load("test:item/ruby");
        assertNotNull(image, () -> "warnings: " + log.warnings());
        assertEquals(16, image.getWidth());
        assertEquals(16, image.getHeight());
        assertEquals(0xFF, image.getRGB(8, 8) >>> 24);
        assertTrue(log.warnings().isEmpty());
    }
}
