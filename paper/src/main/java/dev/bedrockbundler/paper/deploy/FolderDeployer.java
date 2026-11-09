package dev.bedrockbundler.paper.deploy;

import dev.bedrockbundler.common.spec.Bundle;

import java.io.IOException;
import java.nio.file.Path;

/** Copies the bundle to a folder shared with a proxy or standalone Geyser (same machine, NFS, ...). */
public final class FolderDeployer implements Deployer {

    private final Path folder;

    public FolderDeployer(Path folder) {
        this.folder = folder;
    }

    @Override
    public String describe() {
        return "shared folder " + folder.toAbsolutePath();
    }

    @Override
    public void deploy(Path bundleDirectory) throws IOException {
        Bundle.copy(bundleDirectory, folder);
    }
}
