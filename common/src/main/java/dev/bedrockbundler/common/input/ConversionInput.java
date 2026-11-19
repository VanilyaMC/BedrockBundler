package dev.bedrockbundler.common.input;

import java.util.List;
import java.util.Map;

/**
 * Everything the converter needs.
 *
 * @param javaPack every file of the generated ItemsAdder resource pack, keyed by zip path
 * @param items    registry snapshot of custom items
 * @param blocks   registry snapshot of custom blocks
 * @param models   BetterModel {@code .bbmodel} files, converted to Bedrock entity models
 */
public record ConversionInput(Map<String, byte[]> javaPack, List<IaItem> items, List<IaBlock> blocks,
                              List<dev.bedrockbundler.common.model.ModelInput> models) {

    public ConversionInput(Map<String, byte[]> javaPack, List<IaItem> items, List<IaBlock> blocks) {
        this(javaPack, items, blocks, List.of());
    }
}
