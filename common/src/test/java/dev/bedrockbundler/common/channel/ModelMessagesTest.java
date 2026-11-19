package dev.bedrockbundler.common.channel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModelMessagesTest {

    @Test
    void assignRoundTrip() {
        ModelMessages.Message message = ModelMessages.decode(ModelMessages.assign(123456, "pet_wolf", "abcdef012345", 1.5f));
        assertEquals(new ModelMessages.Assign(123456, "pet_wolf", "abcdef012345", 1.5f), message);
    }

    @Test
    void stateRoundTrip() {
        List<ModelMessages.Value> values = List.of(
                new ModelMessages.Value(ModelMessages.BONE, 0, 16777215),
                new ModelMessages.Value(ModelMessages.ANIMATION, 1, 5),
                new ModelMessages.Value(ModelMessages.TINT_COLOR, 0, 0xABCDEF));
        ModelMessages.Message message = ModelMessages.decode(ModelMessages.state(7, 0.5f, values));
        assertEquals(new ModelMessages.State(7, 0.5f, values), message);
    }

    @Test
    void removeRoundTrip() {
        assertEquals(new ModelMessages.Remove(9), ModelMessages.decode(ModelMessages.remove(9)));
    }

    @Test
    void garbageIsIgnored() {
        assertNull(ModelMessages.decode(new byte[]{99}));
        assertNull(ModelMessages.decode(new byte[]{1, 0}));
        assertNull(ModelMessages.decode(new byte[0]));
    }

    @Test
    void propertyNames() {
        assertEquals("bedrockbundler:bone2", ModelMessages.propertyName("bedrockbundler", ModelMessages.BONE, 2));
        assertEquals("bedrockbundler:anim0", ModelMessages.propertyName("bedrockbundler", ModelMessages.ANIMATION, 0));
        assertEquals("bedrockbundler:tintcolor", ModelMessages.propertyName("bedrockbundler", ModelMessages.TINT_COLOR, 0));
        assertNull(ModelMessages.propertyName("bedrockbundler", (byte) 'x', 0));
    }
}
