package dev.bedrockbundler.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Collects non-fatal conversion problems so the platform can log them in one place. */
public final class ConversionLog {

    private final List<String> warnings = Collections.synchronizedList(new ArrayList<>());

    public void warn(String message) {
        warnings.add(message);
    }

    public List<String> warnings() {
        synchronized (warnings) {
            return List.copyOf(warnings);
        }
    }
}
