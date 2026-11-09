package dev.bedrockadder.common.convert;

import dev.bedrockadder.common.ConversionLog;
import dev.bedrockadder.common.Json;
import dev.bedrockadder.common.bedrock.BedrockPack;
import dev.bedrockadder.common.bedrock.Textures;
import dev.bedrockadder.common.input.ConversionInput;
import dev.bedrockadder.common.input.IaBlock;
import dev.bedrockadder.common.input.IaItem;
import dev.bedrockadder.common.java.JavaPack;
import dev.bedrockadder.common.java.ModelResolver;
import dev.bedrockadder.common.spec.BlockSpec;
import dev.bedrockadder.common.spec.Bundle;
import dev.bedrockadder.common.spec.ItemSpec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Converts a custom content resource pack plus registry snapshot into a {@link Bundle} and Bedrock pack. */
public final class Converter {

    /** Bump whenever converter output changes, so servers regenerate bundles made by older versions. */
    public static final int CONVERTER_VERSION = 15;

    public record Result(Bundle bundle, byte[] mcpack, byte[] mappings, byte[] blockMappings, List<String> warnings) {
    }

    private Converter() {
    }

    /** Hash identifying the conversion input; equal hashes produce identical output. */
    public static String hash(ConversionInput input, ConversionContext.Options options) {
        String extra = CONVERTER_VERSION + "|" + options + "|" + Json.GSON.toJson(input.items()) + "|" + Json.GSON.toJson(input.blocks());
        return JavaPack.hash(input.javaPack(), extra);
    }

    /**
     * Furniture that changes its model by swapping the item_model of the displayed item, like Nexo's connectable
     * furniture ({@code nexo:connectable/connectable} becomes {@code ..._left}, {@code _inner} and so on next to a
     * neighbour). Every item definition named {@code <model>_<variant>} next to a furniture item's model becomes an
     * item of its own, so Geyser knows those models too.
     */
    static List<IaItem> modelVariants(List<IaItem> items, JavaPack pack) {
        Set<String> known = new HashSet<>();
        for (IaItem item : items) {
            if (item.itemModel() != null) {
                known.add(item.itemModel());
            }
        }
        List<IaItem> variants = new ArrayList<>();
        for (IaItem item : items) {
            if (!item.furniture() || item.itemModel() == null) {
                continue;
            }
            dev.bedrockadder.common.ResourceId model = dev.bedrockadder.common.ResourceId.parse(item.itemModel());
            String prefix = "assets/" + model.namespace() + "/items/" + model.path() + "_";
            for (String path : pack.paths().stream().sorted().toList()) {
                if (!path.startsWith(prefix) || !path.endsWith(".json")) {
                    continue;
                }
                String suffix = path.substring(prefix.length(), path.length() - 5);
                String variantModel = model.namespace() + ":" + model.path() + "_" + suffix;
                if (suffix.contains("/") || !known.add(variantModel)) {
                    continue;
                }
                variants.add(new IaItem(item.id() + "_" + suffix, item.material(), variantModel, null,
                        item.displayName(), item.maxStackSize(), item.maxDamage(), item.equipSlot(), item.armorAsset(),
                        item.protection(), item.food(), item.block(), true));
            }
        }
        return variants;
    }

    public static Result convert(ConversionInput input, ConversionContext.Options options, String packName) {
        ConversionLog log = new ConversionLog();
        JavaPack javaPack = new JavaPack(input.javaPack());
        BedrockPack bedrockPack = new BedrockPack();
        ConversionContext ctx = new ConversionContext(javaPack, new ModelResolver(javaPack, log),
                new Textures(javaPack, log), bedrockPack, log, options);

        List<IaItem> sortedItems = new ArrayList<>(input.items());
        sortedItems.addAll(modelVariants(input.items(), javaPack));
        sortedItems.sort(Comparator.comparing(IaItem::id));
        ItemConverter itemConverter = new ItemConverter(ctx);
        List<ItemSpec> items = new ArrayList<>();
        Set<String> bedrockIds = new HashSet<>();
        for (IaItem item : sortedItems) {
            try {
                ItemSpec spec = itemConverter.convert(item);
                if (spec != null && bedrockIds.add(spec.bedrockId())) {
                    items.add(spec);
                } else if (spec != null) {
                    log.warn("Item " + item.id() + " collides with another item as " + spec.bedrockId() + "; skipped");
                }
            } catch (RuntimeException e) {
                log.warn("Item " + item.id() + " failed: " + e);
            }
        }

        List<IaBlock> sortedBlocks = new ArrayList<>(input.blocks());
        sortedBlocks.sort(Comparator.comparing(IaBlock::id));
        BlockConverter blockConverter = new BlockConverter(ctx);
        List<BlockSpec> blocks = new ArrayList<>();
        Set<String> javaStates = new HashSet<>();
        for (IaBlock block : sortedBlocks) {
            try {
                BlockSpec spec = blockConverter.convert(block);
                if (spec != null && javaStates.add(spec.javaState())) {
                    blocks.add(spec);
                } else if (spec != null) {
                    log.warn("Block " + block.id() + " uses " + spec.javaState() + " like another block; skipped");
                }
            } catch (RuntimeException e) {
                log.warn("Block " + block.id() + " failed: " + e);
            }
        }

        if (options.fonts()) {
            try {
                new FontConverter(ctx).convert();
            } catch (RuntimeException e) {
                log.warn("Font conversion failed: " + e);
            }
        }

        String hash = hash(input, options);
        // Keep version components small (0-999); any content change still changes the version.
        int[] version = {1, Integer.parseInt(hash.substring(0, 4), 16) % 1000, Integer.parseInt(hash.substring(4, 8), 16) % 1000};
        byte[] mcpack = bedrockPack.build(packName, "Custom content for Bedrock players, generated by BedrockAdder", version);
        Bundle bundle = new Bundle(Bundle.FORMAT_VERSION, CONVERTER_VERSION, hash, items, blocks);
        return new Result(bundle, mcpack, MappingWriter.bytes(items), BlockMappingWriter.bytes(blocks), log.warnings());
    }
}
