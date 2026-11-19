package dev.bedrockbundler.paper;

import org.bukkit.block.BlockFace;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayGeometryTest {

    private static final double EPS = 1.0E-4;

    @Test
    void itemDisplayCentersTheModelHalfABlockAboveTheFeet() {
        DisplayGeometry.Pose pose = DisplayGeometry.itemDisplay(10, 64, 20, 90f, new Vector3f(), new Quaternionf(), 1f, 0, 0);
        assertEquals(10, pose.x(), EPS);
        assertEquals(63.5, pose.y(), EPS);
        assertEquals(20, pose.z(), EPS);
        assertEquals(90f, pose.yaw(), EPS);
    }

    @Test
    void itemDisplayScaleChangesTheLift() {
        assertEquals(63.0, DisplayGeometry.itemDisplay(0, 64, 0, 0f, new Vector3f(), new Quaternionf(), 2f, 0, 0).y(), EPS);
    }

    @Test
    void itemDisplayTranslationFollowsTheEntityYaw() {
        // Translation of one block along the local +x; the entity yaw turns the local frame.
        DisplayGeometry.Pose north = DisplayGeometry.itemDisplay(0, 0, 0, 0f, new Vector3f(1, 0, 0), new Quaternionf(), 1f, 0, 0);
        assertEquals(1, north.x(), EPS);
        assertEquals(0, north.z(), EPS);
        DisplayGeometry.Pose west = DisplayGeometry.itemDisplay(0, 0, 0, 90f, new Vector3f(1, 0, 0), new Quaternionf(), 1f, 0, 0);
        assertEquals(0, west.x(), EPS);
        assertEquals(1, Math.abs(west.z()), EPS);
    }

    @Test
    void itemDisplayAppliesLeftRotationAndOffsets() {
        Quaternionf quarterTurn = new Quaternionf().rotateY((float) Math.toRadians(90));
        DisplayGeometry.Pose pose = DisplayGeometry.itemDisplay(0, 0, 0, 10f, new Vector3f(), quarterTurn, 1f, 0.25, 5f);
        assertEquals(10f - 90f + 5f, pose.yaw(), 1.0E-3);
        assertEquals(-0.25, pose.y(), EPS);
    }

    @Test
    void itemFrameStandIsHalfSizeOnTheFramePlane() {
        DisplayGeometry.Pose pose = DisplayGeometry.itemFrame(5.5, 70.5, 3.5, 180f, 0.5f, 0f);
        assertEquals(5.5, pose.x(), EPS);
        assertEquals(70.25, pose.y(), EPS);
        assertEquals(3.5, pose.z(), EPS);
        assertEquals(180f, pose.yaw(), EPS);
    }

    @Test
    void frameYawFollowsTheFacing() {
        assertEquals(0f, DisplayGeometry.facingYaw(BlockFace.SOUTH));
        assertEquals(90f, DisplayGeometry.facingYaw(BlockFace.WEST));
        assertEquals(180f, DisplayGeometry.facingYaw(BlockFace.NORTH));
        assertEquals(270f, DisplayGeometry.facingYaw(BlockFace.EAST));
    }

    @Test
    void blockDisplayStandIsScaledToTheDisplayAndLowered() {
        Vector3f unit = new Vector3f(1, 1, 1);
        float standScale = DisplayGeometry.blockStandScale(unit);
        assertEquals(1f / DisplayGeometry.BLOCK_HELMET_SCALE, standScale, 1.0E-4);
        DisplayGeometry.Pose pose = DisplayGeometry.blockDisplay(0, 100, 0, 0f, new Vector3f(), new Quaternionf(), unit, 0f);
        // the block's center is half a block above and beside the display's corner origin
        assertEquals(0.5, pose.x(), EPS);
        assertEquals(0.5, pose.z(), EPS);
        assertEquals(100.5 - DisplayGeometry.BLOCK_HELMET_CENTER * standScale, pose.y(), EPS);
    }

    @Test
    void blockStandScaleStaysInTheAttributeRange() {
        assertEquals(0.0625f, DisplayGeometry.blockStandScale(new Vector3f(0.001f)), 1.0E-6);
        assertEquals(16f, DisplayGeometry.blockStandScale(new Vector3f(100f)), 1.0E-6);
    }

    @Test
    void textLineOffsetMatchesGeysersNamePlateConstants() {
        assertEquals(-0.6 + 0.1414, DisplayGeometry.textLineOffset(1), EPS);
        assertEquals(-0.6 + 0.1414 * 3, DisplayGeometry.textLineOffset(3), EPS);
        assertEquals(DisplayGeometry.textLineOffset(1), DisplayGeometry.textLineOffset(0), EPS);
        assertTrue(DisplayGeometry.textLineOffset(4) > DisplayGeometry.textLineOffset(1), "more lines sit higher");
    }

    @Test
    void textStandFollowsTranslationAndConfiguredOffset() {
        DisplayGeometry.Pose pose = DisplayGeometry.text(1, 2, 3, 0f, new Vector3f(0, 0.5f, 0), 1, 0.1);
        assertEquals(1, pose.x(), EPS);
        assertEquals(2 + 0.5 + DisplayGeometry.textLineOffset(1) + 0.1, pose.y(), EPS);
        assertEquals(3, pose.z(), EPS);
    }
}
