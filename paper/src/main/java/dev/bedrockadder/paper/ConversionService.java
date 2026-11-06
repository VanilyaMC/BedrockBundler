package dev.bedrockadder.paper;

import dev.bedrockadder.common.convert.ConversionContext;
import dev.bedrockadder.common.convert.Converter;
import dev.bedrockadder.common.input.ConversionInput;
import dev.bedrockadder.common.input.IaBlock;
import dev.bedrockadder.common.input.IaItem;
import dev.bedrockadder.common.spec.Bundle;
import dev.bedrockadder.paper.deploy.Deployer;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/** Runs conversions off the server thread and hands the result to the deployer. */
final class ConversionService {

    record Status(Instant time, String hash, int items, int blocks, int skippedBlocks, int warnings, boolean converted) {
    }

    private final BedrockAdderPlugin plugin;
    private final Path bundleFolder;
    private final Deployer deployer;
    private final ConversionContext.Options options;
    private final String packName;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BedrockAdder-Converter");
        thread.setDaemon(true);
        return thread;
    });
    /** Hash of the bundle that existed at startup, i.e. what Geyser most likely loaded. */
    private final String startupHash;
    private volatile Status status;

    ConversionService(BedrockAdderPlugin plugin, Deployer deployer, ConversionContext.Options options, String packName) {
        this.plugin = plugin;
        this.bundleFolder = plugin.getDataFolder().toPath().resolve("bundle");
        this.deployer = deployer;
        this.options = options;
        this.packName = packName;
        this.startupHash = currentHash();
    }

    Path bundleFolder() {
        return bundleFolder;
    }

    Status status() {
        return status;
    }

    Deployer deployer() {
        return deployer;
    }

    boolean restartRequired() {
        String current = currentHash();
        return current != null && !current.equals(startupHash);
    }

    private String currentHash() {
        try {
            Bundle bundle = Bundle.read(bundleFolder);
            return bundle == null ? null : bundle.hash();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Re-deploys the bundle from the last run, so remote targets have it right after startup. */
    void deployExisting() {
        executor.execute(() -> {
            try {
                Bundle bundle = Bundle.read(bundleFolder);
                if (bundle == null) {
                    return;
                }
                if (bundle.converterVersion() != Converter.CONVERTER_VERSION) {
                    // Output of an older BedrockAdder may be broken; wait for the reconversion instead.
                    plugin.getLogger().info("The existing Bedrock bundle was made by an older BedrockAdder and is not deployed; "
                            + "it is regenerated once ItemsAdder has loaded.");
                    return;
                }
                deployer.deploy(bundleFolder);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Could not deploy existing bundle to " + deployer.describe(), e);
            }
        });
    }

    /** Must be called on the server thread: the ItemsAdder registry is read here. */
    void convert(Map<String, byte[]> javaPack, boolean force, CommandSender sender) {
        IaRegistryReader reader = new IaRegistryReader(plugin.getLogger());
        List<IaItem> items = reader.items();
        List<String> skippedBlocks = new ArrayList<>();
        List<IaBlock> blocks = reader.blocks(skippedBlocks);
        ConversionInput input = new ConversionInput(javaPack, items, blocks);
        executor.execute(() -> run(input, skippedBlocks, force, sender));
    }

    private void run(ConversionInput input, List<String> skippedBlocks, boolean force, CommandSender sender) {
        try {
            String hash = Converter.hash(input, options);
            Bundle existing = Bundle.read(bundleFolder);
            if (!force && existing != null && hash.equals(existing.hash())
                    && existing.converterVersion() == Converter.CONVERTER_VERSION) {
                deployer.deploy(bundleFolder);
                Bundle bundle = existing;
                status = new Status(Instant.now(), hash, bundle.items().size(), bundle.blocks().size(),
                        skippedBlocks.size(), 0, false);
                message(sender, "ItemsAdder content is unchanged; Bedrock bundle is up to date.");
                return;
            }

            long start = System.nanoTime();
            Converter.Result result = Converter.convert(input, options, packName);
            result.bundle().write(bundleFolder, result.mcpack(), result.mappings(), result.blockMappings());
            writeWarnings(result.warnings(), skippedBlocks);
            deployer.deploy(bundleFolder);

            status = new Status(Instant.now(), hash, result.bundle().items().size(), result.bundle().blocks().size(),
                    skippedBlocks.size(), result.warnings().size(), true);
            long millis = (System.nanoTime() - start) / 1_000_000;
            message(sender, String.format("Converted %d items and %d blocks for Bedrock in %d ms (%d warnings, see warnings.txt). Deployed to %s.",
                    status.items(), status.blocks(), millis, status.warnings(), deployer.describe()));
            if (!skippedBlocks.isEmpty()) {
                message(sender, skippedBlocks.size() + " entity-based blocks are not converted yet (furniture support comes later).");
            }
            if (restartRequired()) {
                message(sender, "Restart Geyser (or the server running it) so Bedrock players get the new content.");
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Bedrock conversion failed", e);
            message(sender, "Bedrock conversion failed: " + e.getMessage());
        }
    }

    private void writeWarnings(List<String> warnings, List<String> skippedBlocks) throws IOException {
        List<String> lines = new ArrayList<>(warnings);
        for (String id : skippedBlocks) {
            lines.add("Block " + id + " is entity-based and not converted yet");
        }
        Files.write(plugin.getDataFolder().toPath().resolve("warnings.txt"), lines, StandardCharsets.UTF_8);
    }

    private void message(CommandSender sender, String message) {
        plugin.getLogger().info(message);
        if (sender != null && !(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage("[BedrockAdder] " + message));
        }
    }

    void shutdown() {
        executor.shutdownNow();
        deployer.close();
    }
}
