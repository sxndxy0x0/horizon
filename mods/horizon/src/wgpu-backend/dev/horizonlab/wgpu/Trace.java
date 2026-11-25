package dev.horizonlab.wgpu;

import java.util.Map;
import java.util.TreeMap;

/** {@code -Dhz.trace}: counts of what the game asks of the backend, printed at each submit (first frames). */
final class Trace {
    static final boolean ON = Boolean.getBoolean("hz.trace");
    private static final Map<String, Integer> COUNTS = new TreeMap<>();
    private static int submits;

    private Trace() {}

    static synchronized void count(String what) {
        if (ON) {
            COUNTS.merge(what, 1, Integer::sum);
        }
    }

    static synchronized void submit() {
        if (ON && submits++ < 400) {
            System.out.println("[horizon-wgpu] java " + submits + ": " + COUNTS);
            COUNTS.clear();
        }
    }
}
