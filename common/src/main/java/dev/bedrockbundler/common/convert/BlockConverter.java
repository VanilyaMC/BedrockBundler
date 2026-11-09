package dev.bedrockbundler.common.convert;

import dev.bedrockbundler.common.ResourceId;
import dev.bedrockbundler.common.bedrock.GeometryConverter;
import dev.bedrockbundler.common.bedrock.Textures;
import dev.bedrockbundler.common.input.IaBlock;
import dev.bedrockbundler.common.java.JavaModel;
import dev.bedrockbundler.common.java.ModelResolver;
import dev.bedrockbundler.common.spec.BlockSpec;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns ItemsAdder blocks into Geyser custom blocks. ItemsAdder stores every custom block as a specific
 * vanilla state (note block, mushroom block, chorus plant, tripwire); the Java pack's blockstate file tells
 * which model that state shows, and Geyser swaps the state for a Bedrock custom block.
 */
public final class BlockConverter {

    private static final List<String> CUBE_FACES = List.of("down", "up", "north", "south", "west", "east");

    private final ConversionContext ctx;

    public BlockConverter(ConversionContext ctx) {
        this.ctx = ctx;
    }

    public BlockSpec convert(IaBlock block) {
        String javaState = normalizeState(block.javaState());
        ModelResolver.Variant variant = ctx.models().blockVariant(javaState);
        if (variant == null) {
            ctx.log().warn("Block " + block.id() + ": no blockstate variant for " + javaState + "; skipped");
            return null;
        }
        JavaModel model = ctx.models().model(variant.model());
        if (model.kind() != JavaModel.Kind.ELEMENTS || model.elements().isEmpty()) {
            ctx.log().warn("Block " + block.id() + ": model " + variant.model() + " has no elements; skipped");
            return null;
        }

        String name = ResourceId.parse(block.id()).flat();
        Map<String, BlockSpec.Material> materials = new LinkedHashMap<>();
        String geometry = null;

        if (isFullCube(model)) {
            JavaModel.Element cube = model.elements().get(0);
            for (String face : CUBE_FACES) {
                materials.put(face, material(cube.faces().get(face).texture()));
            }
            materials.put("*", materials.get("north"));
        } else {
            geometry = "geometry." + ConversionContext.NAMESPACE + ".block." + name;
            Map<String, String> instanceByTexture = new LinkedHashMap<>();
            for (JavaModel.Element element : model.elements()) {
                for (JavaModel.Face face : element.faces().values()) {
                    if (face.texture() != null) {
                        instanceByTexture.computeIfAbsent(face.texture(), t -> "m" + instanceByTexture.size());
                    }
                }
            }
            instanceByTexture.forEach((texture, instance) -> materials.put(instance, material(texture)));
            if (!materials.isEmpty()) {
                materials.put("*", materials.values().iterator().next());
            }
            ctx.bedrockPack().json("models/blocks/" + ConversionContext.NAMESPACE + "/" + name + ".geo.json",
                    GeometryConverter.block(geometry, model,
                            face -> face.texture() == null ? null : instanceByTexture.get(face.texture())));
        }

        return new BlockSpec(name, javaState, block.displayName(), geometry, materials,
                variant.x(), variant.y(), Math.max(0, block.hardness()), Math.max(0, Math.min(15, block.lightLevel())));
    }

    private static boolean isFullCube(JavaModel model) {
        if (model.elements().size() != 1) {
            return false;
        }
        JavaModel.Element element = model.elements().get(0);
        if (element.rotation() != null || element.faces().size() != 6) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            if (element.from()[i] != 0 || element.to()[i] != 16) {
                return false;
            }
        }
        for (JavaModel.Face face : element.faces().values()) {
            float[] uv = face.uv();
            if (face.texture() == null || face.rotation() != 0 || uv[0] != 0 || uv[1] != 0 || uv[2] != 16 || uv[3] != 16) {
                return false;
            }
        }
        return true;
    }

    /** Copies the texture into the Bedrock pack (once) and returns its material instance. */
    private BlockSpec.Material material(String textureId) {
        if (textureId == null) {
            return new BlockSpec.Material("missing", "alpha_test");
        }
        ResourceId id = ResourceId.parse(textureId);
        String key = ConversionContext.NAMESPACE + ".block." + id.flat();
        boolean transparent = false;
        if (ctx.textures().isVanilla(textureId)) {
            // Vanilla textures already exist in the Bedrock vanilla pack, mostly under the same name.
            if (!ctx.bedrockPack().hasTerrainTexture(key)) {
                ctx.bedrockPack().terrainTexture(key, "textures/blocks/" + id.path().replaceFirst("^block/", ""));
            }
        } else {
            BufferedImage image = ctx.textures().load(textureId);
            transparent = image != null && Textures.hasTransparency(image);
            if (!ctx.bedrockPack().hasTerrainTexture(key) && image != null) {
                String path = "textures/" + ConversionContext.NAMESPACE + "/blocks/" + id.flat();
                ctx.bedrockPack().file(path + ".png", Textures.png(image));
                ctx.bedrockPack().terrainTexture(key, path);
            }
        }
        return new BlockSpec.Material(key, transparent ? "alpha_test" : "opaque");
    }

    /** Geyser expects properties sorted by name, like the Java registry prints them. */
    static String normalizeState(String state) {
        int bracket = state.indexOf('[');
        String block = bracket < 0 ? state : state.substring(0, bracket);
        if (!block.contains(":")) {
            block = "minecraft:" + block;
        }
        if (bracket < 0) {
            return block;
        }
        Map<String, String> properties = new TreeMap<>(
                ModelResolver.parseProperties(state.substring(bracket + 1, state.length() - 1)));
        if (properties.isEmpty()) {
            return block;
        }
        StringBuilder builder = new StringBuilder(block).append('[');
        properties.forEach((key, value) -> builder.append(key).append('=').append(value).append(','));
        builder.setLength(builder.length() - 1);
        return builder.append(']').toString();
    }
}
