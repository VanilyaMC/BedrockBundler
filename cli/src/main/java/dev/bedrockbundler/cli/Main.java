package dev.bedrockbundler.cli;

import dev.bedrockbundler.cli.nexo.NexoFolder;
import dev.bedrockbundler.common.convert.ConversionContext;
import dev.bedrockbundler.common.convert.Converter;
import dev.bedrockbundler.common.input.ConversionInput;

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
 *      --out build/bedrock [--geyser /path/to/geyser] [--pack-name Name] [--prefix name]
 * </pre>
 *
 * <ul>
 *   <li>{@code --out}: the bundle (same layout the plugin writes), plus {@code warnings.txt}</li>
 *   <li>{@code --geyser}: also writes {@code packs/<pack-name>.mcpack} and
 *       {@code custom_mappings/<prefix>_items.json} / {@code <prefix>_blocks.json} into a Geyser folder</li>
 * </ul>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> options = parse(args);
        if (!options.containsKey("nexo") || !options.containsKey("out")) {
            System.err.println("Usage: --nexo <Nexo folder> --out <dir> [--pack <generated pack.zip>] [--geyser <Geyser folder>]"
                    + " [--pack-name <name>] [--prefix <file prefix>] [--icon-size 64] [--no-fonts]");
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
        Converter.Result result = Converter.convert(input, conversionOptions, packName);

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

        System.out.printf("Converted %d of %d Nexo items and %d blocks (%d warnings) -> %s%n",
                result.bundle().items().size(), folder.itemCount(), result.bundle().blocks().size(), warnings.size(), out);
        warnings.forEach(w -> System.out.println("  warning: " + w));
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
