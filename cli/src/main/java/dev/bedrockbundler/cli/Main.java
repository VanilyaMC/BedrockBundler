package dev.bedrockbundler.cli;

import dev.bedrockbundler.cli.nexo.NexoFolder;
import dev.bedrockbundler.common.convert.ConversionContext;
import dev.bedrockbundler.common.convert.Converter;
import dev.bedrockbundler.common.input.ConversionInput;
import dev.bedrockbundler.common.java.VanillaAssets;
import dev.bedrockbundler.common.java.VanillaClientJar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline converter: turns a Nexo plugin folder into a Bedrock pack and Geyser JSON mappings, without a running
 * Minecraft server. Useful for Geyser-Standalone and for checking output in CI.
 *
 * <pre>
 * java -jar BedrockBundler-cli.jar --nexo plugins/Nexo [--pack plugins/Nexo/pack/pack.zip]
 *      --out build/bedrock [--geyser /path/to/geyser] [--pack-name Name] [--prefix name] [--models plugins/BetterModel/models]
 *      [--mc-version 1.21.11 [--vanilla-cache dir] | --client-jar client.jar]
 * </pre>
 *
 * <ul>
 *   <li>{@code --out}: the bundle (same layout the plugin writes), plus {@code warnings.txt}</li>
 *   <li>{@code --geyser}: also writes {@code packs/<pack-name>.mcpack} and
 *       {@code custom_mappings/<prefix>_items.json} / {@code <prefix>_blocks.json} into a Geyser folder</li>
 *   <li>{@code --mc-version}: vanilla textures the pack references come from this version's client jar, downloaded
 *       once into {@code --vanilla-cache} (default {@code vanilla-cache}); {@code --client-jar} uses a local jar</li>
 * </ul>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> options = parse(args);
        if (!options.containsKey("nexo") || !options.containsKey("out")) {
            System.err.println("Usage: --nexo <Nexo folder> --out <dir> [--pack <generated pack.zip>] [--geyser <Geyser folder>]"
                    + " [--pack-name <name>] [--prefix <file prefix>] [--icon-size 64] [--no-fonts] [--models <BetterModel/models>]"
                    + " [--mc-version <version> [--vanilla-cache <dir>] | --client-jar <client jar>]");
            System.exit(2);
            return;
        }
        Path nexo = Path.of(options.get("nexo"));
        Path out = Path.of(options.get("out"));
        Path generated = options.containsKey("pack") ? Path.of(options.get("pack")) : null;
        String packName = options.getOrDefault("pack-name", "BedrockBundler");
        String prefix = options.getOrDefault("prefix", "bedrockbundler");
        int iconSize = Integer.parseInt(options.getOrDefault("icon-size", "64"));
        ConversionContext.Options conversionOptions = new ConversionContext.Options(
                iconSize, !options.containsKey("no-fonts"), ConversionContext.Options.DEFAULT.fontCellUnits(),
                ConversionContext.Options.DEFAULT.guiOffsetX())
                // Nexo furniture uses display_transform NONE unless the item config sets another one.
                .withFurnitureDisplay(options.getOrDefault("furniture-display", "none"));

        NexoFolder folder = new NexoFolder(nexo);
        // Nexo places GUI glyphs with explicit shifts; without one a glyph starts at the title.
        conversionOptions = conversionOptions.withGuiOffsets(0, folder.glyphOffsets());
        ConversionInput input = folder.input(generated);
        if (options.containsKey("models")) {
            input = new ConversionInput(input.javaPack(), input.items(), input.blocks(),
                    input.paintings(), input.emotes(), readModels(Path.of(options.get("models"))));
        }
        Converter.Result result;
        try (VanillaAssets vanilla = vanillaAssets(options)) {
            result = Converter.convert(input, conversionOptions, packName, vanilla);
        }

        Path bundle = out.resolve("bundle");
        result.bundle().write(bundle, result.mcpack(), result.mappings(), result.blockMappings());
        List<String> warnings = new ArrayList<>(folder.warnings());
        warnings.addAll(result.warnings());
        Files.createDirectories(out);
        Files.write(out.resolve("warnings.txt"), warnings, StandardCharsets.UTF_8);

        if (options.containsKey("geyser")) {
            Path geyser = Path.of(options.get("geyser"));
            Path packs = Files.createDirectories(geyser.resolve("packs"));
            Path mappings = Files.createDirectories(geyser.resolve("custom_mappings"));
            write(packs.resolve(packName + ".mcpack"), result.mcpack());
            write(mappings.resolve(prefix + "_items.json"), result.mappings());
            if (!result.bundle().blocks().isEmpty()) {
                write(mappings.resolve(prefix + "_blocks.json"), result.blockMappings());
            } else {
                Files.deleteIfExists(mappings.resolve(prefix + "_blocks.json"));
            }
        }

        System.out.printf("Converted %d of %d Nexo items, %d blocks and %d entity models (%d warnings) -> %s%n",
                result.bundle().items().size(), folder.itemCount(), result.bundle().blocks().size(),
                result.bundle().models().size(), warnings.size(), out);
        warnings.forEach(w -> System.out.println("  warning: " + w));
    }

    private static VanillaAssets vanillaAssets(Map<String, String> options) throws IOException {
        if (options.containsKey("client-jar")) {
            return VanillaAssets.ofJar(options.getOrDefault("mc-version", "local"), Path.of(options.get("client-jar")));
        }
        if (options.containsKey("mc-version")) {
            return VanillaClientJar.open(options.get("mc-version"), Path.of(options.getOrDefault("vanilla-cache", "vanilla-cache")),
                    message -> System.out.println(message));
        }
        System.out.println("No --mc-version: models that use vanilla textures miss those faces on Bedrock");
        return VanillaAssets.NONE;
    }

    /** The BetterModel folder: every {@code *.bbmodel}, id = file name in lower case. */
    private static List<dev.bedrockbundler.common.model.ModelInput> readModels(Path directory) throws IOException {
        List<dev.bedrockbundler.common.model.ModelInput> models = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".bbmodel")) {
                    models.add(new dev.bedrockbundler.common.model.ModelInput(
                            name.substring(0, name.length() - ".bbmodel".length()).toLowerCase(java.util.Locale.ROOT), Files.readAllBytes(file)));
                }
            }
        }
        return models;
    }

    private static void write(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument " + arg);
            }
            String key = arg.substring(2);
            if (key.startsWith("no-")) {
                options.put(key, "true");
            } else if (i + 1 < args.length) {
                options.put(key, args[++i]);
            } else {
                throw new IllegalArgumentException("Missing value for " + arg);
            }
        }
        return options;
    }
}
