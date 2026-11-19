package dev.bedrockbundler.common.model;

/**
 * Switches of the model pack generator.
 *
 * @param namespace            entity property and identifier namespace
 * @param gmeLayout            use the exact file layout and names of the GeyserModelEngine extension (golden tests);
 *                             otherwise files live in {@code bedrockbundler} sub folders and ids carry a {@code bb_} prefix
 * @param translucentMaterials draw glass-like cubes with a blending material
 * @param translucentThreshold share of semi transparent pixels that makes a cube glass
 * @param boneTints            build the extra render controllers that tint single bones
 * @param manualAnimator       add the root bone animation driven by the {@code manual_*} properties
 *
 * @author MrMirhan
 */
public record ModelOptions(String namespace, boolean gmeLayout, boolean translucentMaterials,
                           double translucentThreshold, boolean boneTints, boolean manualAnimator) {

    public static ModelOptions defaults() {
        return new ModelOptions(ModelSpec.NAMESPACE, false, true, 0.5, true, true);
    }

    /** What GeyserModelEngine with its stock config ({@code modelengine} namespace) generates. */
    public static ModelOptions gmeReference() {
        return new ModelOptions("modelengine", true, true, 0.5, true, true);
    }
}
