package dev.bedrockbundler.paper.deploy;

import java.io.IOException;
import java.nio.file.Path;

/** Delivers a finished bundle to wherever the BedrockBundler Geyser extension reads it from. */
public interface Deployer {

    /** Human-readable target, shown by {@code /bedrockbundler status}. */
    String describe();

    void deploy(Path bundleDirectory) throws IOException;

    default void close() {
    }
}
