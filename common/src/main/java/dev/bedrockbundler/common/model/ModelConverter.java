package dev.bedrockbundler.common.model;

import dev.bedrockbundler.common.ConversionLog;
import dev.bedrockbundler.common.Json;
import dev.bedrockbundler.common.bedrock.BedrockPack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts BetterModel {@code .bbmodel} files into Bedrock custom entities: client entity, geometry, animations,
 * animation controllers, render controllers (with tint variants), materials and textures. The Geyser side only has
 * to register the entity identifiers and int properties listed in the returned {@link ModelSpec}s.
 *
 * <p>Output is deterministic: inputs are processed in id order, every collection is ordered and nothing random or
 * time dependent is written, so the same models give the same bytes.
 *
 * @author MrMirhan
 */
public final class ModelConverter {

    private ModelConverter() {
    }

    /** Converts with the default {@link ModelOptions}; bad models are skipped with a warning. */
    public static List<ModelSpec> convert(List<ModelInput> inputs, BedrockPack pack, ConversionLog log) {
        return convert(inputs, pack, log, ModelOptions.defaults());
    }

    public static List<ModelSpec> convert(List<ModelInput> inputs, BedrockPack pack, ConversionLog log,
                                          ModelOptions options) {
        List<ModelInput> sorted = new ArrayList<>(inputs);
        sorted.sort(Comparator.comparing(ModelInput::id));
        List<ModelSpec> specs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ModelInput input : sorted) {
            if (!seen.add(input.id())) {
                log.warn("model " + input.id() + ": duplicate id skipped");
                continue;
            }
            try {
                BedrockModel model = BbmodelReader.read(input.id(), input.bbmodel(),
                        message -> log.warn("model " + input.id() + ": " + message));
                ModelPackGenerator.Output output = ModelPackGenerator.generate(model, options,
                        message -> log.warn("model " + input.id() + ": " + message));
                output.files().forEach(pack::file);
                specs.add(output.spec());
            } catch (ModelException e) {
                log.warn("model " + input.id() + " skipped: " + e.getMessage());
            } catch (RuntimeException e) {
                log.warn("model " + input.id() + " skipped: " + e);
            }
        }
        if (!specs.isEmpty() && !pack.has("materials/entity.material")) {
            pack.file("materials/entity.material", ModelPackGenerator.MATERIAL.getBytes(StandardCharsets.UTF_8));
        }
        return specs;
    }

    /**
     * Converts into a fresh pack and writes it for inspection: {@code pack/} holds the files as they are packed
     * (paths after the 80 character rule), {@code intermediate/<id>/} the geometry, animation and config
     * documents, and {@code specs.json} the specs.
     */
    public static List<ModelSpec> convertToDirectory(List<ModelInput> inputs, Path directory, ConversionLog log,
                                                     ModelOptions options) throws IOException {
        BedrockPack pack = new BedrockPack();
        List<ModelSpec> specs = convert(inputs, pack, log, options);
        for (Map.Entry<String, byte[]> entry : pack.packedFiles().entrySet()) {
            write(directory.resolve("pack").resolve(entry.getKey()), entry.getValue());
        }
        Set<String> converted = new HashSet<>();
        specs.forEach(s -> converted.add(s.id()));
        for (ModelInput input : inputs) {
            if (!converted.contains(input.id())) {
                continue;
            }
            BedrockModel model = BbmodelReader.read(input.id(), input.bbmodel(), message -> { });
            Path folder = directory.resolve("intermediate").resolve(input.id());
            write(folder.resolve(input.id() + ".geo.json"), compact(model.geometry()));
            if (model.animationDocument() != null) {
                write(folder.resolve(input.id() + ".animation.json"), compact(model.animationDocument()));
            }
            write(folder.resolve("config.json"), compact(model.configDocument()));
            for (BedrockModel.Texture texture : model.textures()) {
                write(folder.resolve(texture.name() + ".png"), texture.png());
            }
        }
        write(directory.resolve("specs.json"), Json.bytes(Json.GSON.toJsonTree(specs)));
        return specs;
    }

    private static byte[] compact(com.google.gson.JsonElement json) {
        return Json.GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
    }

    private static void write(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }
}
