package dev.bedrockbundler.common.model;

import com.google.gson.JsonObject;
import dev.bedrockbundler.common.convert.ConversionContext;
import dev.bedrockbundler.common.convert.Converter;
import dev.bedrockbundler.common.input.ConversionInput;
import dev.bedrockbundler.common.java.JavaPack;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The models reach the bundle and the pack through {@link Converter}, and change its hash. */
class ConverterModelsTest {

    private static ModelInput model(String id, int rgb) {
        JsonObject m = ModelFixtures.model(new JsonObject[]{ModelFixtures.texture("t.png", ModelFixtures.png(255, rgb))},
                new JsonObject[]{ModelFixtures.cube("a", new double[]{0, 0, 0}, new double[]{4, 4, 4}, 0)},
                new JsonObject[]{ModelFixtures.group("g", "body", new double[]{0, 0, 0}, "a")});
        return new ModelInput(id, ModelFixtures.bytes(m));
    }

    private static Converter.Result convert(List<ModelInput> models) {
        return Converter.convert(new ConversionInput(Map.of(), List.of(), List.of(), List.of(), List.of(), models),
                ConversionContext.Options.DEFAULT, "BedrockBundler");
    }

    @Test
    void modelsAreInTheBundleAndThePack() throws IOException {
        Converter.Result result = convert(List.of(model("pet_wolf", 0xAA0000)));
        assertEquals(1, result.bundle().models().size());
        ModelSpec spec = result.bundle().models().get(0);
        assertEquals("pet_wolf", spec.id());
        assertEquals("bedrockbundler:pet_wolf", spec.entity());
        Map<String, byte[]> pack = JavaPack.readZip(new ByteArrayInputStream(result.mcpack()));
        assertTrue(pack.keySet().stream().anyMatch(p -> p.startsWith("entity/")), pack.keySet().toString());
        assertTrue(pack.containsKey("materials/entity.material"));
    }

    @Test
    void modelContentChangesTheHash() {
        String a = convert(List.of(model("pet_wolf", 0xAA0000))).bundle().hash();
        String b = convert(List.of(model("pet_wolf", 0x00AA00))).bundle().hash();
        String none = convert(List.of()).bundle().hash();
        assertNotEquals(a, b);
        assertNotEquals(a, none);
        assertEquals(a, convert(List.of(model("pet_wolf", 0xAA0000))).bundle().hash());
    }
}
