package dev.bedrockbundler.paper.model.bettermodel;

import dev.bedrockbundler.paper.model.ModelTracking;
import kr.toxicity.model.api.animation.AnimationIterator;
import kr.toxicity.model.api.bone.RenderedBone;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.bukkit.platform.BukkitLocation;
import kr.toxicity.model.api.data.blueprint.BlueprintAnimation;
import kr.toxicity.model.api.data.renderer.RenderPipeline;
import kr.toxicity.model.api.entity.BaseEntity;
import kr.toxicity.model.api.nms.ModelDisplay;
import kr.toxicity.model.api.platform.PlatformLocation;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.Tracker;
import kr.toxicity.model.api.util.function.BonePredicate;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Set;

/**
 * One BetterModel tracker. The bone and animation reading follows what the Bedrock model's animation controllers
 * expect (names and rules from the GeyserModelEngine project, MIT, by zimzaza4 and contributors).
 */
final class BetterModelTracking implements ModelTracking {

    private final Tracker tracker;
    /** Null for a location model: it has no source entity. */
    private final BaseEntity source;

    BetterModelTracking(Tracker tracker) {
        this.tracker = tracker;
        this.source = tracker instanceof EntityTracker entityTracker ? entityTracker.sourceEntity() : null;
    }

    @Override
    public String model() {
        return tracker.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean gone() {
        if (tracker.forRemoval() || tracker.isClosed()) {
            return true;
        }
        return source != null && source.dead();
    }

    @Override
    public Location location() {
        PlatformLocation platform = source != null ? source.location() : tracker.location();
        return platform instanceof BukkitLocation bukkit ? bukkit.source().clone() : null;
    }

    @Override
    public boolean visibleTo(Player player) {
        if (tracker.getPipeline().isHide(BukkitAdapter.adapt(player))) {
            return false;
        }
        // A location model is shown to the players its plugin spawned it for (the owner of a pet, everyone near a
        // spawn NPC); an entity model follows BetterModel's own entity tracking.
        return tracker instanceof EntityTracker || tracker.isSpawned(player.getUniqueId());
    }

    @Override
    public float scale() {
        return tracker.scaler().scale(tracker);
    }

    @Override
    public void sample(Set<String> hiddenBones, Set<String> animations) {
        tracker.bones().forEach(bone -> collect(bone, hiddenBones));

        RenderPipeline pipeline = tracker.getPipeline();
        for (RenderedBone renderedBone : pipeline.bones()) {
            if (tracker.bone(renderedBone.name()).runningAnimation() != null) {
                BlueprintAnimation animation = tracker.renderer().animations().get(renderedBone.runningAnimation().name());
                animations.add(renderedBone.runningAnimation().name());
                // A one-shot override animation hides the ones that would play under it.
                if (animation != null && animation.override() && animation.loop() == AnimationIterator.Type.PLAY_ONCE) {
                    break;
                }
            }
        }
    }

    /** The head bone is named differently depending on whether it has children (the converter's rule). */
    private static String boneName(RenderedBone bone) {
        String name = bone.name().rawName();
        if (name.equals("head")) {
            return (bone.getGroup().getChildren().isEmpty() ? "h_" : "hi_") + name;
        }
        return name;
    }

    private void collect(RenderedBone bone, Set<String> hidden) {
        String name = boneName(bone).toLowerCase(Locale.ROOT);
        if (name.equals("hitbox") || name.equals("shadow") || name.equals("mount")
                || name.startsWith("p_") || name.startsWith("b_") || name.startsWith("ob_")) {
            return;
        }
        bone.matchTree(BonePredicate.TRUE.children(false), (child, predicate) -> {
            if (predicate.test(child)) {
                return false;
            }
            collect(child, hidden);
            return true;
        });
        RenderedBone active = tracker.bone(bone.name());
        ModelDisplay display = active.getDisplay();
        if (display != null && display.invisible()) {
            hidden.add(name);
        }
    }
}
