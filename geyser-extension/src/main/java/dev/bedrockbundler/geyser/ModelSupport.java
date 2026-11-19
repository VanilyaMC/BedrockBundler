package dev.bedrockbundler.geyser;

import dev.bedrockbundler.common.channel.ModelMessages;
import dev.bedrockbundler.common.model.ModelSpec;
import dev.bedrockbundler.common.spec.Bundle;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.entity.custom.CustomEntityDefinition;
import org.geysermc.geyser.api.entity.data.GeyserEntityDataTypes;
import org.geysermc.geyser.api.entity.property.type.GeyserIntEntityProperty;
import org.geysermc.geyser.api.entity.type.GeyserEntity;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent;
import org.geysermc.geyser.api.event.java.ServerSpawnEntityEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntitiesEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;
import org.geysermc.geyser.api.extension.ExtensionLogger;
import org.geysermc.geyser.api.util.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bedrock entity models (converted BetterModel models).
 *
 * <p>Registered only when this Geyser has the public custom entity API (2.12+). Entirely public API except the
 * plugin message listener ({@link ModelChannel}). How it fits together:
 *
 * <ol>
 *   <li>Every model becomes a custom entity definition plus integer properties (bone visibility and animation bits).
 *   <li>The Paper plugin spawns an invisible marker armor stand per model, visible to Bedrock players only, and
 *       sends an ASSIGN message on {@code bedrockbundler:main} just before the stand is shown. When Geyser then
 *       translates the stand's spawn, {@link ServerSpawnEntityEvent} swaps its definition to the model.
 *   <li>STATE messages carry the animation and bone bits; they are applied as entity properties.
 * </ol>
 *
 * Without a matching assignment (older bundle, channel failure) the stand stays what Java says it is: an invisible
 * armor stand, so the worst case is no model, not a wrong one.
 */
final class ModelSupport {

    private final ExtensionLogger logger;
    private final Bundle bundle;
    private final Map<String, ModelSpec> byId = new HashMap<>();
    private final Map<String, ModelSpec> byEntity = new HashMap<>();
    /** entity identifier -> property name -> registered property. */
    private final Map<String, Map<String, GeyserIntEntityProperty>> properties = new HashMap<>();
    private final Map<GeyserConnection, Session> sessions = new ConcurrentHashMap<>();
    private final ModelChannel channel;
    private volatile boolean mismatchLogged;

    ModelSupport(ExtensionLogger logger, Bundle bundle) {
        this.logger = logger;
        this.bundle = bundle;
        for (ModelSpec spec : bundle.models()) {
            byId.put(spec.id(), spec);
            byEntity.put(spec.entity(), spec);
        }
        this.channel = ModelChannel.create(logger);
    }

    /** What the plugin told us about one session. */
    private static final class Session {
        /** Java entity id -> model id. Kept until REMOVE: Paper re-sends a spawn when a player comes back in range. */
        final Map<Integer, Assignment> assigned = new ConcurrentHashMap<>();
    }

    private record Assignment(ModelSpec spec, float scale) {
    }

    // ------------------------------------------------------------------ definitions

    @Subscribe
    public void onDefineEntities(GeyserDefineEntitiesEvent event) {
        int registered = 0;
        for (ModelSpec spec : bundle.models()) {
            try {
                event.register(CustomEntityDefinition.of(Identifier.of(spec.entity())));
                registered++;
            } catch (RuntimeException e) {
                logger.warning("Could not register model entity " + spec.entity() + ": " + e.getMessage());
            }
        }
        logger.info("Defined " + registered + " model entities");
    }

    @Subscribe
    public void onDefineProperties(GeyserDefineEntityPropertiesEvent event) {
        for (ModelSpec spec : bundle.models()) {
            Map<String, GeyserIntEntityProperty> registered = new HashMap<>();
            for (ModelSpec.Property property : spec.properties()) {
                try {
                    registered.put(property.name(), event.registerIntegerProperty(Identifier.of(spec.entity()),
                            Identifier.of(property.name()), property.min(), property.max(), property.defaultValue()));
                } catch (RuntimeException e) {
                    logger.warning("Could not register property " + property.name() + " of " + spec.entity() + ": " + e.getMessage());
                }
            }
            properties.put(spec.entity(), registered);
        }
    }

    // ------------------------------------------------------------------ sessions

    @Subscribe
    public void onLogin(SessionLoginEvent event) {
        sessions.put(event.connection(), new Session());
        channel.attach(event.connection(), data -> onPayload(event.connection(), data));
    }

    @Subscribe
    public void onDisconnect(SessionDisconnectEvent event) {
        sessions.remove(event.connection());
    }

    private void onPayload(GeyserConnection connection, byte[] data) {
        ModelMessages.Message message = ModelMessages.decode(data);
        Session session = sessions.get(connection);
        if (message == null || session == null) {
            return;
        }
        switch (message) {
            case ModelMessages.Assign assign -> {
                if (!assign.bundleHash().equals(shortHash())) {
                    if (!mismatchLogged) {
                        mismatchLogged = true;
                        logger.warning("The server converted its models after this Geyser started (bundle "
                                + assign.bundleHash() + " vs " + shortHash() + "); models stay invisible until Geyser restarts.");
                    }
                    return;
                }
                ModelSpec spec = byId.get(assign.model());
                if (spec != null) {
                    session.assigned.put(assign.entityId(), new Assignment(spec, assign.scale()));
                }
            }
            case ModelMessages.State state -> applyState(connection, session, state);
            case ModelMessages.Remove remove -> session.assigned.remove(remove.entityId());
        }
    }

    private void applyState(GeyserConnection connection, Session session, ModelMessages.State state) {
        Assignment assignment = session.assigned.get(state.entityId());
        if (assignment == null) {
            return;
        }
        GeyserEntity entity = connection.entities().byJavaId(state.entityId());
        if (entity == null || !assignment.spec().entity().equals(entity.definition().identifier().toString())) {
            return; // not spawned (yet) or not drawn as the model
        }
        Map<String, GeyserIntEntityProperty> registered = properties.get(assignment.spec().entity());
        if (registered == null) {
            return;
        }
        try {
            entity.updatePropertiesBatched(updater -> {
                for (ModelMessages.Value value : state.values()) {
                    String name = ModelMessages.propertyName(ModelSpec.NAMESPACE, value.kind(), value.index());
                    GeyserIntEntityProperty property = name == null ? null : registered.get(name);
                    if (property != null) {
                        updater.update(property, value.value());
                    }
                }
            });
            if (state.scale() > 0) {
                entity.override(GeyserEntityDataTypes.SCALE, state.scale());
            }
        } catch (RuntimeException e) {
            logger.debug("Could not update model entity " + state.entityId() + ": " + e);
        }
    }

    private String shortHash() {
        return bundle.hash().substring(0, Math.min(12, bundle.hash().length()));
    }

    // ------------------------------------------------------------------ spawning

    /** Swaps the definition of an assigned stand for its model. */
    @Subscribe
    public void onSpawn(ServerSpawnEntityEvent event) {
        Session session = sessions.get(event.connection());
        if (session == null) {
            return;
        }
        Assignment assignment = session.assigned.get(event.entityId());
        if (assignment == null) {
            return;
        }
        try {
            ModelSpec spec = assignment.spec();
            event.definition(CustomEntityDefinition.of(Identifier.of(spec.entity())));
            float scale = assignment.scale();
            event.preSpawnConsumer(entity -> {
                // Clicks and hits fall through to the (invisible) entity the plugin keeps under the model.
                entity.override(GeyserEntityDataTypes.WIDTH, spec.width());
                entity.override(GeyserEntityDataTypes.HEIGHT, spec.height());
                if (scale > 0) {
                    entity.override(GeyserEntityDataTypes.SCALE, scale);
                }
            });
        } catch (RuntimeException e) {
            logger.warning("Could not spawn model " + assignment.spec().id() + ": " + e.getMessage());
        }
    }

    boolean channelReady() {
        return channel.ready();
    }
}
