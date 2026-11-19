package dev.bedrockbundler.paper;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Where the mirror armor stand of each display kind stands. Pure math on numbers, so it is unit tested without a
 * server. Everything Bedrock-side here is derived from Java behavior; nothing could be checked on a Bedrock client.
 */
final class DisplayGeometry {

    /** Where a stand's feet go and which way it faces. */
    record Pose(double x, double y, double z, float yaw) {
    }

    /** Java draws a block worn on an armor stand's head at 5/8 of its size. */
    static final float BLOCK_HELMET_SCALE = 0.625f;
    /**
     * Height of the center of a block worn on the head above the stand's feet: the head pivot is 1.5 up and the
     * head layer moves the block 0.25 further (vanilla armor stand model and head layer).
     */
    static final double BLOCK_HELMET_CENTER = 1.75;
    /** Bedrock draws the name plate about half a block above a stand with no height; see {@link #textLineOffset}. */
    private static final double TEXT_BASE_OFFSET = -0.6;
    private static final double TEXT_LINE_OFFSET = 0.1414;

    private DisplayGeometry() {
    }

    /**
     * An item display of a custom item, drawn through the item's "furniture" pose: the model's center sits half a
     * block (times the stand's scale) above the feet. The translation is in the display's local frame, which the
     * entity's yaw turns. Same formula as the furniture mirror, so both look alike.
     */
    static Pose itemDisplay(double x, double y, double z, float yaw, Vector3f translation, Quaternionf leftRotation,
                            float scale, double yOffset, float yawOffset) {
        Vector3f t = new Vector3f(translation).rotateY((float) Math.toRadians(-yaw));
        Vector3f angles = leftRotation.getEulerAnglesYXZ(new Vector3f());
        return new Pose(x + t.x(), y + t.y() + yOffset - 0.5 * scale, z + t.z(),
                yaw - (float) Math.toDegrees(angles.y()) + yawOffset);
    }

    /**
     * An item frame: the frame entity sits at the item's plane, and Java draws the item at half size there. The
     * stand is shrunk the same way, and faces like the frame (a frame's yaw is its facing). Item rotation steps of
     * a frame cannot be shown, because a stand cannot roll.
     */
    static Pose itemFrame(double x, double y, double z, float yaw, float scale, float yawOffset) {
        return new Pose(x, y - 0.5 * scale, z, yaw + yawOffset);
    }

    /**
     * A block display drawn as a block worn on the stand's head. The block's center is the middle of its unit cube
     * after the display's scale and rotation; the stand is scaled so the worn block ends up as large as the
     * display, and lowered so the block sits where its center is.
     *
     * @return pose; {@link #blockStandScale} gives the matching stand scale
     */
    static Pose blockDisplay(double x, double y, double z, float yaw, Vector3f translation, Quaternionf leftRotation,
                             Vector3f scale, float yawOffset) {
        Vector3f center = new Vector3f(scale).mul(0.5f).rotate(leftRotation).add(translation)
                .rotateY((float) Math.toRadians(-yaw));
        Vector3f angles = leftRotation.getEulerAnglesYXZ(new Vector3f());
        double standScale = blockStandScale(scale);
        return new Pose(x + center.x(), y + center.y() - BLOCK_HELMET_CENTER * standScale, z + center.z(),
                yaw - (float) Math.toDegrees(angles.y()) + yawOffset);
    }

    /** Stand scale that makes a worn block as large as the display's average scale, kept in the attribute's range. */
    static float blockStandScale(Vector3f scale) {
        float average = (Math.abs(scale.x()) + Math.abs(scale.y()) + Math.abs(scale.z())) / 3f;
        return Math.max(0.0625f, Math.min(16f, average / BLOCK_HELMET_SCALE));
    }

    /**
     * Y offset of a name plate mirror relative to a text display's position. A Java text display is centered on its
     * position, and more lines grow it both ways. Bedrock draws a name plate above its anchor and grows it upward,
     * so the anchor sits lower for few lines and higher for many. The constants are Geyser's own for the name
     * plate it draws for a text display (TextDisplayEntity.calculateLineOffset), so both agree.
     */
    static double textLineOffset(int lines) {
        return TEXT_BASE_OFFSET + TEXT_LINE_OFFSET * Math.max(1, lines);
    }

    /** A text display's name plate stand: at the display, moved by its translation, lowered by the line offset. */
    static Pose text(double x, double y, double z, float yaw, Vector3f translation, int lines, double yOffset) {
        Vector3f t = new Vector3f(translation).rotateY((float) Math.toRadians(-yaw));
        return new Pose(x + t.x(), y + t.y() + textLineOffset(lines) + yOffset, z + t.z(), yaw);
    }

    /** Entity yaw of a wall item frame by the way it faces (Minecraft: south 0, west 90, north 180, east 270). */
    static float facingYaw(org.bukkit.block.BlockFace facing) {
        return switch (facing) {
            case WEST -> 90f;
            case NORTH -> 180f;
            case EAST -> 270f;
            default -> 0f;
        };
    }
}
