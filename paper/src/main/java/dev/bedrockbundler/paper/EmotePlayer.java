package dev.bedrockbundler.paper;

import dev.bedrockbundler.common.convert.EmoteConverter;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Plays converted emotes (EmoteConverter) on a player for the Bedrock players who see them.
 *
 * <p>ItemsAdder draws an emote with packet-only bones around the player; Geyser shows them to Bedrock players
 * as armor stands wearing the bone items, which look like floating heads. Instead, every Bedrock viewer (and the
 * player, if on Bedrock) gets an AnimateEntityPacket playing the emote's animation on the player's own model;
 * while the emote lasts, the bones are removed from their view and the player is kept visible.
 *
 * <p>Geyser's API cannot play animations, so this reaches into Geyser-Spigot's session classes by reflection
 * (GeyserSession, EntityCache, Entity, Cloudburst's AnimateEntityPacket). When they change, emotes stop working
 * on Bedrock and nothing else breaks: the first failure is logged once.
 */
public final class EmotePlayer {

    /**
     * The runtime controller the emote plays in: /playanimation's default, which other Geyser extensions that
     * animate entities use too.
     */
    private static final String CONTROLLER = "__runtime_controller";
    /** How often the player is revealed and new bones are removed while the emote plays. */
    private static final long CHECK_TICKS = 2;
    /** Blocks around the player within which the content plugin's bones are looked for. */
    private static final double BONE_RADIUS = 3;

    private final BedrockBundlerPlugin plugin;
    /** Emoting player -> the task that keeps them visible and their bones hidden for Bedrock viewers. */
    private final Map<UUID, ScheduledTask> playing = new ConcurrentHashMap<>();
    private volatile boolean failed;
    private volatile boolean announced;

    public EmotePlayer(BedrockBundlerPlugin plugin) {
        this.plugin = plugin;
    }

    /** Starts an emote; call on the player's own thread (the content plugin's emote event). */
    public void play(Player player, String emote) {
        int sent = send(player, EmoteConverter.animation(emote));
        if (!announced) {
            announced = true; // once, to show in the log that emotes reach Bedrock players
            plugin.getLogger().info("Playing emote " + emote + " of " + player.getName() + " for " + sent
                    + " Bedrock player(s) (" + EmoteConverter.animation(emote) + ")");
        }
        stopTask(player.getUniqueId());
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, t -> {
            if (!player.isOnline()) {
                stopTask(player.getUniqueId());
                return;
            }
            Set<Integer> real = new HashSet<>();
            real.add(player.getEntityId());
            for (org.bukkit.entity.Entity entity : player.getNearbyEntities(BONE_RADIUS, BONE_RADIUS, BONE_RADIUS)) {
                real.add(entity.getEntityId());
            }
            for (Object session : viewers(player)) {
                reveal(session, player);
                hideBones(session, player, real);
            }
        }, () -> playing.remove(player.getUniqueId()), 1, CHECK_TICKS);
        if (task != null) {
            playing.put(player.getUniqueId(), task);
        }
    }

    /**
     * Removes the content plugin's emote bones from a Bedrock viewer's view. ItemsAdder sends them as packet-only
     * entities around the player, which Geyser shows as armor stands (with the bone items as floating heads) and
     * area effect clouds. Removed are entities of those kinds within {@link #BONE_RADIUS} of the player that the
     * server does not have ({@code real}: furniture stands and other real entities stay).
     */
    private void hideBones(Object session, Player player, Set<Integer> real) {
        try {
            session.getClass().getMethod("ensureInEventLoop", Runnable.class).invoke(session, (Runnable) () -> {
                try {
                    Object own = entity(session, player.getUniqueId());
                    if (own == null) {
                        return;
                    }
                    Object center = own.getClass().getMethod("position").invoke(own);
                    Object cache = session.getClass().getMethod("getEntityCache").invoke(session);
                    Object map = cache.getClass().getMethod("getEntitiesUnsafe").invoke(cache);
                    List<Object> bones = new ArrayList<>();
                    for (Object entity : ((Map<?, ?>) map).values()) {
                        String kind = entity.getClass().getSimpleName();
                        if (!kind.equals("ArmorStandEntity") && !kind.equals("AreaEffectCloudEntity")) {
                            continue;
                        }
                        int javaId = (int) entity.getClass().getMethod("javaId").invoke(entity);
                        if (real.contains(javaId)
                                || !(boolean) entity.getClass().getMethod("isValid").invoke(entity)
                                || distance(entity.getClass().getMethod("position").invoke(entity), center) > BONE_RADIUS) {
                            continue;
                        }
                        bones.add(entity);
                    }
                    for (Object bone : bones) {
                        bone.getClass().getMethod("despawnEntity").invoke(bone);
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    fail(e);
                }
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail(e);
        }
    }

    private static double distance(Object a, Object b) throws ReflectiveOperationException {
        double d = 0;
        for (String axis : new String[]{"getX", "getY", "getZ"}) {
            double x = ((Number) a.getClass().getMethod(axis).invoke(a)).doubleValue()
                    - ((Number) b.getClass().getMethod(axis).invoke(b)).doubleValue();
            d += x * x;
        }
        return Math.sqrt(d);
    }

    /**
     * The content plugin ended the emote (sneaking, damage, or a one-shot emote's end): plays the emote's end
     * animation, which finishes a looping emote. Call on the player's own thread.
     */
    public void stop(Player player, String emote) {
        stopTask(player.getUniqueId());
        // The end of a looping emote (after its loop), or an empty animation that just replaces it.
        send(player, EmoteConverter.end(emote));
    }

    /**
     * Plays any Bedrock animation on a player for the Bedrock players who see them (/bedrockbundler animate),
     * to tell a broken emote animation from a broken delivery. Call on the player's thread.
     *
     * @return how many Bedrock players got it
     */
    public int test(Player player, String animation) {
        return send(player, animation, true);
    }

    private void stopTask(UUID player) {
        ScheduledTask task = playing.remove(player);
        if (task != null) {
            task.cancel();
        }
    }

    private int send(Player player, String animation) {
        return send(player, animation, false);
    }

    /**
     * @param log log every packet sent (the test command)
     * @return how many Bedrock players got the animation
     */
    private int send(Player player, String animation, boolean log) {
        int sent = 0;
        for (Object session : viewers(player)) {
            try {
                long id = geyserId(session, player.getUniqueId());
                if (id < 0) {
                    continue;
                }
                ClassLoader loader = session.getClass().getClassLoader();
                Object packet = Class.forName("org.cloudburstmc.protocol.bedrock.packet.AnimateEntityPacket", true, loader)
                        .getConstructor().newInstance();
                invoke(packet, "setAnimation", String.class, animation);
                invoke(packet, "setNextState", String.class, "default");
                invoke(packet, "setStopExpression", String.class, "query.any_animation_finished");
                invoke(packet, "setStopExpressionVersion", int.class, 0);
                invoke(packet, "setController", String.class, CONTROLLER);
                invoke(packet, "setBlendOutTime", float.class, 0f);
                Object ids = packet.getClass().getMethod("getRuntimeEntityIds").invoke(packet);
                ids.getClass().getMethod("add", long.class).invoke(ids, id);
                Method sendUpstream = method(session.getClass(), "sendUpstreamPacket");
                sendUpstream.invoke(session, packet);
                sent++;
                if (log) {
                    Object entity = entity(session, player.getUniqueId());
                    plugin.getLogger().info("Sent " + packet + " for " + entity.getClass().getSimpleName()
                            + " " + player.getName() + " to " + session);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                fail(e);
            }
        }
        return sent;
    }

    /** Clears the invisible flag the content plugin set, on Geyser's thread for that session. */
    private void reveal(Object session, Player player) {
        try {
            Method ensure = session.getClass().getMethod("ensureInEventLoop", Runnable.class);
            ensure.invoke(session, (Runnable) () -> {
                try {
                    Object entity = entity(session, player.getUniqueId());
                    if (entity == null) {
                        return;
                    }
                    ClassLoader loader = session.getClass().getClassLoader();
                    Class<?> flagClass = Class.forName("org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag", true, loader);
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Object invisible = Enum.valueOf((Class) flagClass, "INVISIBLE");
                    Method getFlag = method(entity.getClass(), "getFlag");
                    if (Boolean.TRUE.equals(getFlag.invoke(entity, invisible))) {
                        method(entity.getClass(), "setFlag").invoke(entity, invisible, false);
                        entity.getClass().getMethod("updateBedrockMetadata").invoke(entity);
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    fail(e);
                }
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail(e);
        }
    }

    /** Geyser sessions of the Bedrock players that see {@code player}, and the player's own if on Bedrock. */
    private List<Object> viewers(Player player) {
        List<Object> sessions = new ArrayList<>();
        if (failed) {
            return sessions;
        }
        try {
            org.geysermc.geyser.api.GeyserApi api = org.geysermc.geyser.api.GeyserApi.api();
            Object own = api.connectionByUuid(player.getUniqueId());
            if (own != null) {
                sessions.add(own);
            }
            for (Player viewer : player.getTrackedBy()) {
                Object session = api.connectionByUuid(viewer.getUniqueId());
                if (session != null) {
                    sessions.add(session);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            fail(e);
        }
        return sessions;
    }

    private static Object entity(Object session, UUID player) throws ReflectiveOperationException {
        Object own = session.getClass().getMethod("getPlayerEntity").invoke(session);
        if (own != null && player.equals(own.getClass().getMethod("uuid").invoke(own))) {
            return own;
        }
        Object cache = session.getClass().getMethod("getEntityCache").invoke(session);
        return cache.getClass().getMethod("getPlayerEntity", UUID.class).invoke(cache, player);
    }

    private static long geyserId(Object session, UUID player) throws ReflectiveOperationException {
        Object entity = entity(session, player);
        return entity == null ? -1 : (long) entity.getClass().getMethod("geyserId").invoke(entity);
    }

    private static void invoke(Object target, String name, Class<?> type, Object value) throws ReflectiveOperationException {
        target.getClass().getMethod(name, type).invoke(target, value);
    }

    /** A public method by name (the parameter types live in Geyser's class loader). */
    private static Method method(Class<?> type, String name) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private void fail(Throwable e) {
        if (!failed) {
            failed = true;
            plugin.getLogger().log(Level.WARNING, "Could not play emotes for Bedrock players (Geyser's internals "
                    + "changed?); emotes stay as Geyser shows them", e);
        }
    }
}
