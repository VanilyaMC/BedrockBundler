package dev.bedrockbundler.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResourceIdTest {

    @Test
    void flatNamesStartWithALetter() {
        assertEquals("iainternal_icon_search", ResourceId.parse("_iainternal:icon_search").flat());
        assertEquals("myitems_item_ruby", ResourceId.parse("MyItems:item/ruby").flat());
        assertEquals("x", ResourceId.parse("_:_").flat());
    }
}
