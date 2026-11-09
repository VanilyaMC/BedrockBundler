package dev.bedrockadder.geyser;

import dev.bedrockadder.common.convert.Converter;
import dev.bedrockadder.common.spec.BlockSpec;
import dev.bedrockadder.common.spec.Bundle;
import dev.bedrockadder.common.spec.ItemSpec;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.block.custom.CustomBlockData;
import org.geysermc.geyser.api.block.custom.component.CustomBlockComponents;
import org.geysermc.geyser.api.block.custom.component.GeometryComponent;
import org.geysermc.geyser.api.block.custom.component.MaterialInstance;
import org.geysermc.geyser.api.block.custom.component.TransformationComponent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomBlocksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineResourcePacksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPreInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.item.custom.v2.CustomItemBedrockOptions;
import org.geysermc.geyser.api.item.custom.v2.CustomItemDefinition;
import org.geysermc.geyser.api.item.custom.v2.component.java.JavaConsumable;
import org.geysermc.geyser.api.item.custom.v2.component.java.JavaEquippable;
import org.geysermc.geyser.api.item.custom.v2.component.java.JavaFoodProperties;
import org.geysermc.geyser.api.item.custom.v2.component.java.JavaItemDataComponents;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;
import org.geysermc.geyser.api.predicate.item.ItemRangeDispatchPredicate;
import org.geysermc.geyser.api.util.CreativeCategory;
import org.geysermc.geyser.api.util.Identifier;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Registers the ItemsAdder content converted by the BedrockAdder Paper plugin: custom items, custom blocks
 * and the Bedrock resource pack. Geyser only builds its registries at startup, so new content needs a
 * Geyser restart.
 */
public final class BedrockAdderExtension implements Extension {

    private static final String FULL_BLOCK_GEOMETRY = "minecraft:geometry.full_block";

    private Bundle bundle;

    /** Where the plugin (or a sync) delivers new bundles; may change while Geyser runs. */
    private Path bundleFolder() {
        return dataFolder().resolve("bundle");
    }

    /**
     * Snapshot of the bundle taken at startup. Geyser serves the pack from here: it hashes the pack file once,
     * so the file must not change underneath it when the plugin delivers a new conversion.
     */
    private Path activeFolder() {
        return dataFolder().resolve("active");
    }

    @Subscribe
    public void onPreInitialize(GeyserPreInitializeEvent event) {
        try {
            ExtensionConfig config = ExtensionConfig.load(dataFolder());
            sync(config);
            if (Files.isRegularFile(bundleFolder().resolve(Bundle.SPEC_FILE))) {
                Bundle.copy(bundleFolder(), activeFolder());
            }
            bundle = Bundle.read(activeFolder());
        } catch (IOException | RuntimeException e) {
            logger().error("Could not load the BedrockAdder bundle", e);
        }
        if (bundle == null) {
            logger().warning("No BedrockAdder bundle found in " + bundleFolder()
                    + ". Run /iazip on the ItemsAdder server, then restart Geyser.");
        } else if (bundle.formatVersion() != Bundle.FORMAT_VERSION) {
            logger().error("Bundle format " + bundle.formatVersion() + " does not match this extension ("
                    + Bundle.FORMAT_VERSION + "). Update the BedrockAdder plugin and extension together.");
            bundle = null;
        } else if (bundle.converterVersion() != Converter.CONVERTER_VERSION) {
            logger().warning("The BedrockAdder bundle was made by a different converter version ("
                    + bundle.converterVersion() + ", expected " + Converter.CONVERTER_VERSION
                    + ") and is ignored. It is regenerated when ItemsAdder loads; restart Geyser afterwards.");
            bundle = null;
        } else {
            logger().info("Loaded BedrockAdder bundle " + bundle.hash().substring(0, 12) + ": "
                    + bundle.items().size() + " items, " + bundle.blocks().size() + " blocks");
        }
    }

    private void sync(ExtensionConfig config) {
        try {
            switch (config.source()) {
                case LOCAL -> {
                }
                case FOLDER -> {
                    Path folder = Path.of(config.folder());
                    if (Files.isRegularFile(folder.resolve(Bundle.SPEC_FILE))) {
                        Bundle.copy(folder, bundleFolder());
                    } else {
                        logger().warning("No bundle in shared folder " + folder + "; using the last copy");
                    }
                }
                case HTTP -> download(config);
            }
        } catch (IOException | RuntimeException e) {
            logger().warning("Bundle sync (" + config.source() + ") failed, using the last copy: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void download(ExtensionConfig config) throws IOException, InterruptedException {
        if (config.url().isEmpty()) {
            throw new IOException("'url' is not set in config.properties");
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(config.timeoutSeconds())).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.url()))
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("Authorization", "Bearer " + config.token())
                .GET()
                .build();
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (java.io.InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " from " + config.url());
            }
            Bundle.unzip(body, bundleFolder());
        }
        logger().info("Downloaded BedrockAdder bundle from " + config.url());
    }

    // ------------------------------------------------------------------ items

    @Subscribe
    public void onDefineCustomItems(GeyserDefineCustomItemsEvent event) {
        if (bundle == null) {
            return;
        }
        int registered = 0;
        for (ItemSpec spec : bundle.items()) {
            try {
                event.register(Identifier.of(spec.javaItem()), definition(spec));
                registered++;
            } catch (RuntimeException e) {
                logger().warning("Could not register item " + spec.bedrockId() + ": " + e.getMessage());
            }
        }
        logger().info("Registered " + registered + " custom items");
    }

    private static CustomItemDefinition definition(ItemSpec spec) {
        CustomItemBedrockOptions.Builder options = CustomItemBedrockOptions.builder()
                .icon(spec.icon())
                .allowOffhand(true)
                .displayHandheld(spec.handheld())
                .creativeCategory(category(spec.creativeCategory()));
        if (spec.protection() > 0) {
            options.protectionValue(spec.protection());
        }
        if (spec.tags() != null) {
            for (String tag : spec.tags()) {
                options.tag(Identifier.of(tag));
            }
        }

        CustomItemDefinition.Builder builder = CustomItemDefinition.builder(Identifier.of(spec.bedrockId()), Identifier.of(spec.model()))
                .bedrockOptions(options);
        if (spec.displayName() != null) {
            builder.displayName(spec.displayName());
        }
        if (spec.customModelData() != null) {
            // ItemsAdder hands out sequential CustomModelData; the highest matching threshold wins.
            builder.predicate(ItemRangeDispatchPredicate.legacyCustomModelData(spec.customModelData()));
        }
        if (spec.maxStackSize() != null) {
            builder.component(JavaItemDataComponents.MAX_STACK_SIZE, spec.maxStackSize());
        }
        if (spec.maxDamage() != null) {
            builder.component(JavaItemDataComponents.MAX_DAMAGE, spec.maxDamage());
        }
        JavaEquippable.EquipmentSlot slot = slot(spec.equipSlot());
        if (slot != null) {
            builder.component(JavaItemDataComponents.EQUIPPABLE, JavaEquippable.of(slot));
        }
        if (spec.food() != null) {
            builder.component(JavaItemDataComponents.FOOD,
                    JavaFoodProperties.of(spec.food().nutrition(), spec.food().saturation(), spec.food().canAlwaysEat()));
            builder.component(JavaItemDataComponents.CONSUMABLE, JavaConsumable.of(1.6f, JavaConsumable.Animation.EAT));
        }
        return builder.build();
    }

    private static CreativeCategory category(String name) {
        return switch (name) {
            case "construction" -> CreativeCategory.CONSTRUCTION;
            case "nature" -> CreativeCategory.NATURE;
            case "equipment" -> CreativeCategory.EQUIPMENT;
            default -> CreativeCategory.ITEMS;
        };
    }

    private static JavaEquippable.EquipmentSlot slot(String name) {
        if (name == null) {
            return null;
        }
        return switch (name) {
            case "head" -> JavaEquippable.EquipmentSlot.HEAD;
            case "chest" -> JavaEquippable.EquipmentSlot.CHEST;
            case "legs" -> JavaEquippable.EquipmentSlot.LEGS;
            case "feet" -> JavaEquippable.EquipmentSlot.FEET;
            case "body" -> JavaEquippable.EquipmentSlot.BODY;
            default -> null;
        };
    }

    // ------------------------------------------------------------------ blocks

    @Subscribe
    public void onDefineCustomBlocks(GeyserDefineCustomBlocksEvent event) {
        if (bundle == null) {
            return;
        }
        int registered = 0;
        for (BlockSpec spec : bundle.blocks()) {
            try {
                CustomBlockData block = block(spec);
                event.register(block);
                event.registerOverride(spec.javaState(), block.defaultBlockState());
                registered++;
            } catch (RuntimeException e) {
                logger().warning("Could not register block " + spec.name() + " (" + spec.javaState() + "): " + e.getMessage());
            }
        }
        logger().info("Registered " + registered + " custom blocks");
    }

    private static CustomBlockData block(BlockSpec spec) {
        CustomBlockComponents.Builder components = CustomBlockComponents.builder()
                .geometry(GeometryComponent.builder()
                        .identifier(spec.geometry() != null ? spec.geometry() : FULL_BLOCK_GEOMETRY)
                        .build())
                .destructibleByMining(spec.destroyTime())
                .lightEmission(spec.lightEmission());
        if (spec.displayName() != null) {
            components.displayName(spec.displayName());
        }
        if (spec.rotationX() != 0 || spec.rotationY() != 0) {
            // Java blockstate rotations are clockwise seen from above; Bedrock's Y rotation is the other way.
            components.transformation(new TransformationComponent(spec.rotationX(), -spec.rotationY(), 0));
        }
        for (Map.Entry<String, BlockSpec.Material> entry : spec.materials().entrySet()) {
            BlockSpec.Material material = entry.getValue();
            components.materialInstance(entry.getKey(), MaterialInstance.builder()
                    .texture(material.texture())
                    .renderMethod(material.renderMethod())
                    .faceDimming(true)
                    .ambientOcclusion(true)
                    .build());
        }
        return CustomBlockData.builder()
                .name(spec.name())
                .includedInCreativeInventory(false)
                .components(components.build())
                .build();
    }

    // ------------------------------------------------------------------ resource pack

    @Subscribe
    public void onDefineResourcePacks(GeyserDefineResourcePacksEvent event) {
        Path pack = activeFolder().resolve(Bundle.PACK_FILE);
        if (bundle == null || !Files.isRegularFile(pack)) {
            return;
        }
        try {
            event.register(ResourcePack.create(PackCodec.path(pack)));
            logger().info("Registered resource pack " + pack.getFileName());
        } catch (RuntimeException e) {
            logger().error("Could not register the BedrockAdder resource pack", e);
        }
    }
}
