package dev.bedrockbundler.paper;

import java.util.Objects;

/**
 * Remembers the last value written to a mirror and says whether a new one differs, so a mirror pass that finds
 * nothing changed does no work. Used on one entity's thread only.
 */
final class ChangeTracker<T> {

    private T last;
    private boolean has;

    /** Records {@code value}; true when it differs from the previous one (or none was recorded yet). */
    boolean update(T value) {
        if (has && Objects.equals(last, value)) {
            return false;
        }
        last = value;
        has = true;
        return true;
    }

    /** Forgets the last value, so the next {@link #update} reports a change (a new stand needs everything). */
    void reset() {
        last = null;
        has = false;
    }
}
