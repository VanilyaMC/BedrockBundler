package dev.bedrockbundler.common.input;

/**
 * Snapshot of one ItemsAdder custom block.
 *
 * @param id          namespaced ItemsAdder id
 * @param javaState   the vanilla block state ItemsAdder uses in the world,
 *                    e.g. {@code minecraft:note_block[instrument=banjo,note=3,powered=false]}
 * @param displayName plain display name
 * @param hardness    ItemsAdder hardness, used as Bedrock mining time
 * @param lightLevel  emitted light, 0-15
 */
public record IaBlock(String id, String javaState, String displayName, float hardness, int lightLevel) {
}
