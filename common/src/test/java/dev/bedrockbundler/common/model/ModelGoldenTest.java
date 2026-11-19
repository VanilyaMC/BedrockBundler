package dev.bedrockbundler.common.model;

import com.google.gson.JsonElement;
import dev.bedrockbundler.common.ConversionLog;
import dev.bedrockbundler.common.Json;
import dev.bedrockbundler.common.bedrock.BedrockPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden comparison on the real BetterModel models, opt in with {@code BB_GOLDEN=1}. Environment:
 * <ul>
 *   <li>{@code BB_GOLDEN_MODELS}: bbmodel folders separated by the path separator; the first folder wins a duplicate id.
 *   <li>{@code BB_GOLDEN_PYTHON}: the {@code input/} folder {@code bbmodel_gme.py} wrote from the same models.
 *   <li>{@code BB_GOLDEN_PACK}: the {@code generated_pack} folder the real GeyserModelEngine extension generated
 *       from that input.
 *   <li>{@code BB_GOLDEN_OUT} (optional): folder to dump the converted files into for inspection.
 * </ul>
 * The converter runs in its GeyserModelEngine layout and namespace here, so file paths and ids are comparable.
 */
@EnabledIfEnvironmentVariable(named = "BB_GOLDEN", matches = "1")
// NOTE: the reference pack of GeyserModelEngine packs 24 bits per property; ModelSpec.BITS_PER_PROPERTY is 19 now, so the
// molang bit tests and property ranges differ from it by design. This test matched with 24 on 2026-11-19 (commit 231719d:
// 94 models, 753 files, 0 mismatches); to repeat that, set BITS_PER_PROPERTY to 24 temporarily.
class ModelGoldenTest {

    /** Arrays whose order carries no meaning (the reference iterates hash sets). */
    private static final Set<String> UNORDERED = Set.of("animate", "render_controllers", "materials", "part_visibility", "bones");

    @Test
    void realModelsMatchPythonToolAndGeyserExtension() throws IOException {
        List<ModelInput> inputs = collect(System.getenv("BB_GOLDEN_MODELS"));
        Path python = Path.of(System.getenv("BB_GOLDEN_PYTHON"));
        Path reference = Path.of(System.getenv("BB_GOLDEN_PACK"));

        ConversionLog log = new ConversionLog();
        BedrockPack pack = new BedrockPack();
        long start = System.nanoTime();
        List<ModelSpec> specs = ModelConverter.convert(inputs, pack, log, ModelOptions.gmeReference());
        long millis = (System.nanoTime() - start) / 1_000_000;
        if (System.getenv("BB_GOLDEN_OUT") != null) {
            ModelConverter.convertToDirectory(inputs, Path.of(System.getenv("BB_GOLDEN_OUT")), new ConversionLog(),
                    ModelOptions.gmeReference());
        }

        List<String> mismatches = new ArrayList<>();
        int pythonFiles = 0;
        for (ModelInput input : inputs) {
            BedrockModel model = BbmodelReader.read(input.id(), input.bbmodel(), m -> { });
            Path dir = python.resolve(input.id());
            pythonFiles += compareJson(mismatches, input.id() + " geo", Json.parse(Files.readAllBytes(dir.resolve(input.id() + ".geo.json"))),
                    model.geometry(), Set.of());
            Path animation = dir.resolve(input.id() + ".animation.json");
            if (Files.exists(animation) != (model.animationDocument() != null)) {
                mismatches.add(input.id() + ": animation file presence differs");
            } else if (Files.exists(animation)) {
                pythonFiles += compareJson(mismatches, input.id() + " animation", Json.parse(Files.readAllBytes(animation)),
                        model.animationDocument(), Set.of());
            }
            pythonFiles += compareJson(mismatches, input.id() + " config", Json.parse(Files.readAllBytes(dir.resolve("config.json"))),
                    model.configDocument(), Set.of());
            for (BedrockModel.Texture texture : model.textures()) {
                pythonFiles++;
                if (!Arrays.equals(Files.readAllBytes(dir.resolve(texture.name() + ".png")), texture.png())) {
                    mismatches.add(input.id() + ": texture " + texture.name() + " bytes differ");
                }
            }
        }

        Map<String, byte[]> ours = pack.packedFiles();
        int packFiles = 0;
        int identicalBytes = 0;
        int pngFiles = 0;
        int orderOnly = 0;
        int jsonCompared = 0;
        List<Path> referenceFiles;
        try (Stream<Path> walk = Files.walk(reference)) {
            referenceFiles = walk.filter(Files::isRegularFile).sorted().toList();
        }
        Map<String, Boolean> matched = new TreeMap<>();
        for (Path file : referenceFiles) {
            String relative = reference.relativize(file).toString().replace(File.separatorChar, '/');
            if (relative.equals("manifest.json")) {
                continue; // identity and version differ by design
            }
            packFiles++;
            byte[] ourBytes = ours.get(relative);
            if (ourBytes == null) {
                mismatches.add("missing in our pack: " + relative);
                continue;
            }
            matched.put(relative, true);
            byte[] expected = Files.readAllBytes(file);
            if (Arrays.equals(expected, ourBytes)) {
                identicalBytes++;
                continue;
            }
            if (relative.endsWith(".png")) {
                pngFiles++;
                if (!samePixels(expected, ourBytes)) {
                    mismatches.add(relative + ": pixels differ");
                }
            } else {
                jsonCompared++;
                List<String> diff = JsonDiff.diff(Json.parse(expected), Json.parse(ourBytes), 1e-5, UNORDERED);
                if (diff.isEmpty() && !JsonDiff.diff(Json.parse(expected), Json.parse(ourBytes), 1e-5, Set.of()).isEmpty()) {
                    orderOnly++;
                }
                if (!diff.isEmpty()) {
                    mismatches.add(relative + ": " + diff.subList(0, Math.min(3, diff.size())));
                }
            }
        }
        for (String path : ours.keySet()) {
            if (!matched.containsKey(path)) {
                mismatches.add("extra in our pack: " + path);
            }
        }

        // The same models in the production layout: size, path length and reproducibility
        ConversionLog productionLog = new ConversionLog();
        BedrockPack production = new BedrockPack();
        List<ModelSpec> productionSpecs = ModelConverter.convert(inputs, production, productionLog);
        BedrockPack again = new BedrockPack();
        ModelConverter.convert(inputs, again, new ConversionLog());
        byte[] mcpack = production.build("golden", "golden", new int[]{1, 0, 0});
        assertArrayEquals(mcpack, again.build("golden", "golden", new int[]{1, 0, 0}), "production pack is reproducible");
        int productionLongest = production.packedFiles().keySet().stream().mapToInt(String::length).max().orElse(0);
        long productionBytes = production.packedFiles().values().stream().mapToLong(b -> b.length).sum();
        long properties = productionSpecs.stream().mapToLong(s -> s.properties().size()).max().orElse(0);
        System.out.println("GOLDEN production models=" + productionSpecs.size() + " warnings=" + productionLog.warnings().size()
                + " files=" + production.packedFiles().size() + " rawBytes=" + productionBytes + " mcpackBytes=" + mcpack.length
                + " longestPath=" + productionLongest + " maxProperties=" + properties);
        assertTrue(productionLongest < 80);

        long totalBytes = ours.values().stream().mapToLong(b -> b.length).sum();
        int longest = ours.keySet().stream().mapToInt(String::length).max().orElse(0);
        System.out.println("GOLDEN models=" + specs.size() + "/" + inputs.size() + " warnings=" + log.warnings().size()
                + " pythonFilesCompared=" + pythonFiles + " packFilesCompared=" + packFiles
                + " byteIdentical=" + identicalBytes + " pngPixelCompared=" + pngFiles
                + " jsonParsedCompared=" + jsonCompared + " orderOnlyDifferences=" + orderOnly
                + " mismatches=" + mismatches.size() + " packBytes=" + totalBytes + " longestPath=" + longest
                + " convertMillis=" + millis);
        log.warnings().forEach(w -> System.out.println("GOLDEN WARN " + w));
        mismatches.forEach(m -> System.out.println("GOLDEN MISMATCH " + m));

        assertEquals(inputs.size(), specs.size(), "every model converts");
        assertTrue(mismatches.isEmpty(), "mismatches: " + mismatches);
    }

    private static int compareJson(List<String> mismatches, String label, JsonElement expected, JsonElement actual,
                                   Set<String> unordered) {
        List<String> diff = JsonDiff.diff(expected, actual, 1e-5, unordered);
        if (!diff.isEmpty()) {
            mismatches.add(label + ": " + diff.subList(0, Math.min(3, diff.size())));
        }
        return 1;
    }

    private static boolean samePixels(byte[] a, byte[] b) throws IOException {
        BufferedImage x = ImageIO.read(new ByteArrayInputStream(a));
        BufferedImage y = ImageIO.read(new ByteArrayInputStream(b));
        if (x == null || y == null || x.getWidth() != y.getWidth() || x.getHeight() != y.getHeight()) {
            return false;
        }
        for (int j = 0; j < x.getHeight(); j++) {
            for (int i = 0; i < x.getWidth(); i++) {
                if (x.getRGB(i, j) != y.getRGB(i, j)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static List<ModelInput> collect(String folders) throws IOException {
        Map<String, ModelInput> chosen = new LinkedHashMap<>();
        for (String folder : folders.split(File.pathSeparator)) {
            Path dir = Path.of(folder);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> list = Files.list(dir)) {
                files = list.filter(p -> p.getFileName().toString().endsWith(".bbmodel")).sorted().toList();
            }
            for (Path file : files) {
                String name = file.getFileName().toString();
                String id = name.substring(0, name.length() - ".bbmodel".length());
                chosen.putIfAbsent(id, new ModelInput(id, Files.readAllBytes(file)));
            }
        }
        return new ArrayList<>(chosen.values());
    }
}
