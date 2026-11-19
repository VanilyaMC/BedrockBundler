package dev.bedrockbundler.common.input;

import dev.bedrockbundler.common.model.ModelInput;

import java.util.List;
import java.util.Map;

/**
 * Everything the converter needs.
 *
 * @param javaPack  every file of the generated ItemsAdder resource pack, keyed by zip path
 * @param items     registry snapshot of custom items
 * @param blocks    registry snapshot of custom blocks
 * @param paintings painting variants of the server registry
 * @param emotes    ItemsAdder player animation files
 * @param models    BetterModel {@code .bbmodel} files, converted to Bedrock entity models
 */
public record ConversionInput(Map<String, byte[]> javaPack, List<IaItem> items, List<IaBlock> blocks,
                              List<PaintingVariant> paintings, List<EmoteAnimation> emotes,
                              List<ModelInput> models) {

    public ConversionInput {
        paintings = paintings == null ? List.of() : List.copyOf(paintings);
        emotes = emotes == null ? List.of() : List.copyOf(emotes);
        models = models == null ? List.of() : List.copyOf(models);
    }

    public ConversionInput(Map<String, byte[]> javaPack, List<IaItem> items, List<IaBlock> blocks,
                           List<PaintingVariant> paintings, List<EmoteAnimation> emotes) {
        this(javaPack, items, blocks, paintings, emotes, List.of());
    }

    public ConversionInput(Map<String, byte[]> javaPack, List<IaItem> items, List<IaBlock> blocks,
                           List<PaintingVariant> paintings) {
        this(javaPack, items, blocks, paintings, List.of(), List.of());
    }

    public ConversionInput(Map<String, byte[]> javaPack, List<IaItem> items, List<IaBlock> blocks) {
        this(javaPack, items, blocks, List.of(), List.of(), List.of());
    }
}
