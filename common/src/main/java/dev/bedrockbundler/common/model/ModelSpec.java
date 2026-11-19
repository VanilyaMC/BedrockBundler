package dev.bedrockbundler.common.model;

import java.util.List;
import java.util.Locale;

/**
 * What the runtime needs to drive one converted model: the Bedrock entity identifier, the bit order of the bone and
 * animation properties, and the int entity properties to register for it. Goes into {@code bundle.json}.
 *
 * <p>Bit assignment: index {@code i} of {@link #bones()} is bit {@code i % BITS_PER_PROPERTY} of the property
 * {@code <namespace>:bone<i / BITS_PER_PROPERTY>}; {@link #animations()} works the same with {@code anim<k>}.
 * Bone tints use the same bone index: bit {@code i} of {@code tintmask<k>} tints {@code bones().get(i)}, and the
 * single {@code tintcolor} property holds the colour as 6 bits per channel (r, g, b: {@code r << 12 | g << 6 | b}).
 *
 * @param id         model id (file name without {@code .bbmodel})
 * @param entity     client entity identifier, {@code bedrockbundler:<id>}
 * @param bones      bone names in bit order (lower case); every bone the render controller can show
 * @param animations animation names in bit order (spaces replaced by {@code _}), sorted
 * @param properties int entity properties to register for the entity, in a stable order
 * @param width      hitbox width the runtime sends (the real mob keeps the actual hitbox, so clicks fall through)
 * @param height     hitbox height the runtime sends
 *
 * @author MrMirhan
 */
public record ModelSpec(String id, String entity, List<String> bones, List<String> animations,
                        List<Property> properties, float width, float height) {

    public static final String NAMESPACE = "bedrockbundler";

    /** Bits used per int property; values stay below {@code 1 << BITS_PER_PROPERTY}. */
    public static final int BITS_PER_PROPERTY = 24;

    /** Default hitbox edge: tiny so clicks and hits reach the real mob underneath the model. */
    public static final float DEFAULT_HITBOX = 0.02f;

    public ModelSpec {
        bones = List.copyOf(bones);
        animations = List.copyOf(animations);
        properties = List.copyOf(properties);
    }

    /** An int entity property to register: {@code name} is the full identifier, e.g. {@code bedrockbundler:bone0}. */
    public record Property(String name, int min, int max, int defaultValue) {
    }

    /** How a bone name is normalised in a model: lower case, runs of other characters become {@code _}. */
    public static String boneKey(String name) {
        String key = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        return key.isEmpty() ? "bone" : key;
    }

    /** How an animation name is normalised in a model: spaces become {@code _}. */
    public static String animationKey(String name) {
        return name.replace(' ', '_');
    }

    /** Number of int properties needed for {@code count} bits. */
    public static int propertyCount(int count) {
        return (count + BITS_PER_PROPERTY - 1) / BITS_PER_PROPERTY;
    }
}
