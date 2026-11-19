package dev.bedrockbundler.paper.model.bettermodel;

import dev.bedrockbundler.paper.model.ModelSource;
import dev.bedrockbundler.paper.model.ModelTracking;
import kr.toxicity.model.api.bukkit.BetterModelBukkit;
import kr.toxicity.model.api.event.CreateDummyTrackerEvent;
import kr.toxicity.model.api.event.CreateEntityTrackerEvent;
import org.bukkit.plugin.Plugin;

import java.util.function.Consumer;

/**
 * Feeds {@link dev.bedrockbundler.paper.model.ModelMirror} with BetterModel's models. Compiled for Java 25 because
 * the BetterModel API is; it is only loaded (by name) when BetterModel is installed.
 *
 * <p>Both kinds of model are tracked: models of an entity (a mob with a model) and location models
 * ({@code DummyTracker}: pets, NPC figures, crate models and fish).
 */
public final class BetterModelSource implements ModelSource {

    @Override
    public void start(Plugin plugin, Consumer<ModelTracking> onCreate) {
        var bus = BetterModelBukkit.platform().eventBus();
        bus.subscribe(plugin, CreateEntityTrackerEvent.class, event -> onCreate.accept(new BetterModelTracking(event.tracker())));
        bus.subscribe(plugin, CreateDummyTrackerEvent.class, event -> onCreate.accept(new BetterModelTracking(event.tracker())));
    }

    @Override
    public void stop() {
        // BetterModel drops the subscriptions of a disabled plugin itself.
    }
}
