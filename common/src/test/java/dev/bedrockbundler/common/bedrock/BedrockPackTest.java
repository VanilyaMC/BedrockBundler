package dev.bedrockbundler.common.bedrock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BedrockPackTest {

    @Test
    void longDefinitionPathsAreShortened() {
        String path = "render_controllers/bedrockbundler/nexo_forest_horse_armor.render_controllers.json";
        assertTrue(path.length() >= BedrockPack.MAX_PATH);
        String shortened = BedrockPack.shortPath(path);
        assertTrue(shortened.length() < BedrockPack.MAX_PATH);
        assertTrue(shortened.startsWith("render_controllers/") && shortened.endsWith(".json"));
        assertEquals(shortened, BedrockPack.shortPath(path), "stable across conversions");
    }

    @Test
    void shortPathsAndTexturesKeepTheirPath() {
        String shortPath = "attachables/bedrockbundler/nexo_ruby.json";
        assertEquals(shortPath, BedrockPack.shortPath(shortPath));
        String texture = "textures/bedrockbundler/models/" + "x".repeat(60) + ".png";
        assertEquals(texture, BedrockPack.shortPath(texture));
    }
}
