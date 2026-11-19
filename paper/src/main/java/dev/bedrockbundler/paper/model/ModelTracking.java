package dev.bedrockbundler.paper.model;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Set;

/**
 * A running 3D model of a model plugin (BetterModel), as {@link ModelMirror} needs to see it. Implemented over the
 * plugin's own API in a separate class, so this package compiles without it.
 */
public interface ModelTracking {

    /** Model id, as in the .bbmodel file name (lower case). */
    String model();

    /** True once the model or its entity is gone and the Bedrock copy must go too. */
    boolean gone();

    /** Where the model stands now, or null while unknown. */
    Location location();

    /** Whether the model shows for this player (a hidden model, or a location model not spawned for them, does not). */
    boolean visibleTo(Player player);

    /** Current scale of the model; 1 normally. */
    float scale();

    /**
     * Samples the model's bones and animations.
     *
     * @param hiddenBones receives the (lower case) names of bones that are currently not drawn
     * @param animations  receives the names of the animations that are playing
     */
    void sample(Set<String> hiddenBones, Set<String> animations);
}
