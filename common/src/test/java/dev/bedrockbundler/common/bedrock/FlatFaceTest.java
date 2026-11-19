package dev.bedrockbundler.common.bedrock;

import dev.bedrockbundler.common.java.JavaModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A painting's canvas: a flat element with a face on each side must not z-fight on Bedrock. */
class FlatFaceTest {

    private static JavaModel.Face face() {
        return new JavaModel.Face(new float[]{0, 0, 16, 16}, "a:b", "0", 0, -1);
    }

    @Test
    void flatElementFacesArePulledApart() {
        JavaModel.Element canvas = new JavaModel.Element(new float[]{2, 2, 16}, new float[]{14, 19, 16}, null,
                Map.of("north", face(), "south", face(), "east", face()));
        List<JavaModel.Element> parts = GeometryConverter.separateFlatFaces(canvas);
        assertEquals(2, parts.size());
        assertTrue(parts.get(0).faces().containsKey("north") && !parts.get(0).faces().containsKey("south"));
        assertEquals(java.util.Set.of("south"), parts.get(1).faces().keySet());
        assertTrue(parts.get(0).from()[2] < 16 && parts.get(1).from()[2] > 16);

        JavaModel.Element box = new JavaModel.Element(new float[]{0, 0, 0}, new float[]{16, 2, 2}, null,
                Map.of("north", face(), "south", face()));
        assertEquals(List.of(box), GeometryConverter.separateFlatFaces(box));
    }
}
