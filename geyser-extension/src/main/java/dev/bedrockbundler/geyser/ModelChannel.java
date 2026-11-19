package dev.bedrockbundler.geyser;

import dev.bedrockbundler.common.channel.ModelMessages;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.extension.ExtensionLogger;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.PacketSendingEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The only part of the extension that uses Geyser internals: a listener on the connection to the Java server for
 * plugin messages on {@link ModelMessages#CHANNEL}.
 *
 * <p>Java servers (and Velocity) only deliver a plugin message to a client that registered its channel, and Geyser
 * registers none of ours, so this adds the channel to the client's {@code minecraft:register} message (and sends one
 * of its own after login). Packet classes are used only through reflection where their types come from Adventure,
 * which Geyser-Spigot relocates.
 *
 * <p>If any of this fails to link (a Geyser that changed these classes), {@link #create} returns a channel that does
 * nothing: models then stay invisible instead of breaking the player's connection.
 */
abstract class ModelChannel {

    abstract void attach(GeyserConnection connection, Consumer<byte[]> handler);

    abstract boolean ready();

    static ModelChannel create(ExtensionLogger logger) {
        try {
            return new Live(logger);
        } catch (LinkageError | RuntimeException | ReflectiveOperationException e) {
            logger.warning("The model plugin message channel is not available with this Geyser (" + e
                    + "); entity models will not be drawn.");
            return new ModelChannel() {
                @Override
                void attach(GeyserConnection connection, Consumer<byte[]> handler) {
                }

                @Override
                boolean ready() {
                    return false;
                }
            };
        }
    }

    private static final class Live extends ModelChannel {
        private static final int ATTEMPTS = 200;

        private final ExtensionLogger logger;
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BedrockBundler-ModelChannel");
            thread.setDaemon(true);
            return thread;
        });
        private final Method clientboundChannel;
        private final Method serverboundChannel;
        private final Constructor<?> serverboundConstructor;
        private final Method keyFactory;
        private final boolean keyIsString;

        Live(ExtensionLogger logger) throws ReflectiveOperationException {
            this.logger = logger;
            clientboundChannel = ClientboundCustomPayloadPacket.class.getMethod("getChannel");
            serverboundChannel = ServerboundCustomPayloadPacket.class.getMethod("getChannel");
            Constructor<?> found = null;
            for (Constructor<?> constructor : ServerboundCustomPayloadPacket.class.getConstructors()) {
                Class<?>[] types = constructor.getParameterTypes();
                if (types.length == 2 && types[1] == byte[].class) {
                    found = constructor;
                }
            }
            if (found == null) {
                throw new NoSuchMethodException("ServerboundCustomPayloadPacket(channel, byte[])");
            }
            serverboundConstructor = found;
            Class<?> keyType = found.getParameterTypes()[0];
            keyIsString = keyType == String.class;
            keyFactory = keyIsString ? null : keyType.getMethod("key", String.class);
        }

        @Override
        boolean ready() {
            return true;
        }

        @Override
        void attach(GeyserConnection connection, Consumer<byte[]> handler) {
            if (connection instanceof GeyserSession session) {
                schedule(session, handler, 0);
            }
        }

        private void schedule(GeyserSession session, Consumer<byte[]> handler, int attempt) {
            if (attempt > ATTEMPTS) {
                return;
            }
            scheduler.schedule(() -> {
                try {
                    if (session.getDownstream() == null) {
                        schedule(session, handler, attempt + 1);
                        return;
                    }
                    session.getDownstream().getSession().addListener(new Listener(session, handler));
                } catch (RuntimeException | LinkageError e) {
                    logger.warning("Could not listen for model messages: " + e);
                }
            }, 80, TimeUnit.MILLISECONDS);
        }

        private String channelOf(Method method, Object packet) {
            try {
                return String.valueOf(method.invoke(packet));
            } catch (ReflectiveOperationException e) {
                return "";
            }
        }

        private ServerboundCustomPayloadPacket register(byte[] data) {
            try {
                Object key = keyIsString ? "minecraft:register" : keyFactory.invoke(null, "minecraft:register");
                return (ServerboundCustomPayloadPacket) serverboundConstructor.newInstance(key, data);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        private final class Listener extends SessionAdapter {
            private final GeyserSession session;
            private final Consumer<byte[]> handler;
            private boolean registered;

            Listener(GeyserSession session, Consumer<byte[]> handler) {
                this.session = session;
                this.handler = handler;
            }

            @Override
            public void packetSending(PacketSendingEvent event) {
                if (event.getPacket() instanceof ServerboundCustomPayloadPacket payload
                        && channelOf(serverboundChannel, payload).equals("minecraft:register")) {
                    String channels = new String(payload.getData(), StandardCharsets.UTF_8);
                    if (!channels.contains(ModelMessages.CHANNEL)) {
                        event.setPacket(register((channels + "\0" + ModelMessages.CHANNEL).getBytes(StandardCharsets.UTF_8)));
                    }
                    registered = true;
                }
            }

            @Override
            public void packetReceived(Session downstream, Packet packet) {
                if (packet instanceof ClientboundCustomPayloadPacket payload
                        && channelOf(clientboundChannel, payload).equals(ModelMessages.CHANNEL)) {
                    try {
                        handler.accept(payload.getData());
                    } catch (RuntimeException e) {
                        logger.debug("Model message failed: " + e);
                    }
                } else if (packet instanceof ClientboundLoginPacket && !registered) {
                    // Geyser sent no register message of its own: announce the channel ourselves.
                    registered = true;
                    try {
                        session.sendDownstreamPacket(register(ModelMessages.CHANNEL.getBytes(StandardCharsets.UTF_8)));
                    } catch (RuntimeException e) {
                        logger.debug("Could not register the model channel: " + e);
                    }
                }
            }
        }
    }
}
