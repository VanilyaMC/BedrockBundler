package dev.bedrockbundler.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeTrackerTest {

    @Test
    void firstValueIsAChange() {
        ChangeTracker<String> tracker = new ChangeTracker<>();
        assertTrue(tracker.update("a"));
    }

    @Test
    void sameValueIsNotAChange() {
        ChangeTracker<String> tracker = new ChangeTracker<>();
        tracker.update("a");
        assertFalse(tracker.update("a"));
        assertFalse(tracker.update(new String("a")));
    }

    @Test
    void differentValueIsAChange() {
        ChangeTracker<String> tracker = new ChangeTracker<>();
        tracker.update("a");
        assertTrue(tracker.update("b"));
        assertFalse(tracker.update("b"));
        assertTrue(tracker.update("a"));
    }

    @Test
    void nullIsAValueToo() {
        ChangeTracker<String> tracker = new ChangeTracker<>();
        assertTrue(tracker.update(null));
        assertFalse(tracker.update(null));
        assertTrue(tracker.update("a"));
    }

    @Test
    void resetMakesTheNextValueAChange() {
        ChangeTracker<String> tracker = new ChangeTracker<>();
        tracker.update("a");
        tracker.reset();
        assertTrue(tracker.update("a"));
    }
}
