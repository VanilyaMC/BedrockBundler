package dev.bedrockbundler.common.model;

/**
 * One BetterModel source file.
 *
 * @param id      file name without {@code .bbmodel}, lower case {@code [a-z0-9_]}
 * @param bbmodel the raw file content (Blockbench JSON)
 *
 * @author MrMirhan
 */
public record ModelInput(String id, byte[] bbmodel) {
}
