package dev.bedrockbundler.common.input;

import java.util.List;
import java.util.Map;

/**
 * Everything the converter needs.
 *
 * @param javaPack every file of the generated ItemsAdder resource pack, keyed by zip path
 * @param items    registry snapshot of custom items
 * @param blocks   registry snapshot of custom blocks
 */
public record ConversionInput(Map<String, byte[]> javaPack, List<IaItem> items, List<IaBlock> blocks) {
}
