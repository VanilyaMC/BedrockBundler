package dev.bedrockbundler.paper.model;

import org.bukkit.plugin.Plugin;

import java.util.function.Consumer;

/** Tells {@link ModelMirror} about every model the model plugin creates. */
public interface ModelSource {

    /** Starts listening; {@code onCreate} runs for each new model, on whatever thread the model plugin uses. */
    void start(Plugin plugin, Consumer<ModelTracking> onCreate);

    void stop();
}
