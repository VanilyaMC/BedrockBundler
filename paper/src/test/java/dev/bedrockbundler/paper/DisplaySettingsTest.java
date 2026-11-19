package dev.bedrockbundler.paper;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplaySettingsTest {

    private static DisplaySettings load(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return DisplaySettings.load(config);
    }

    @Test
    void defaultsMirrorItemsAndFramesOnly() throws Exception {
        DisplaySettings settings = load("");
        assertTrue(settings.enabled());
        assertTrue(settings.items());
        assertTrue(settings.frames());
        assertTrue(settings.hideInvisibleFrames());
        assertFalse(settings.blocks());
        assertFalse(settings.text());
        assertEquals(2000, settings.maxMirrors());
        assertTrue(settings.any());
    }

    @Test
    void shippedConfigHasTheSameDefaults() throws Exception {
        String yaml = new String(DisplaySettingsTest.class.getResourceAsStream("/config.yml").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(load(""), load(yaml));
    }

    @Test
    void switchesAreRead() throws Exception {
        DisplaySettings settings = load("""
                displays:
                  max-mirrors: 5
                  items: {enabled: false}
                  frames: {enabled: false}
                  blocks: {enabled: true}
                  text: {enabled: true, hide-original: false, y-offset: 0.25}
                """);
        assertFalse(settings.items());
        assertFalse(settings.frames());
        assertTrue(settings.blocks());
        assertTrue(settings.text());
        assertFalse(settings.textHideOriginal());
        assertEquals(0.25, settings.textYOffset());
        assertEquals(5, settings.maxMirrors());
    }

    @Test
    void masterSwitchAndEmptyKindsDisableTheFeature() throws Exception {
        assertFalse(load("displays: {enabled: false}").any());
        assertFalse(load("displays: {items: {enabled: false}, frames: {enabled: false}}").any());
    }

    @Test
    void absurdCapsAreClamped() throws Exception {
        assertEquals(1, load("displays: {max-mirrors: -4}").maxMirrors());
        assertEquals(100_000, load("displays: {max-mirrors: 99999999}").maxMirrors());
    }
}
