package app.exteraless.plugins;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

final class HookTargetCache {
    private static final int MAX_NAMES = 512;
    private volatile ConcurrentHashMap<String, List<String>> entries = new ConcurrentHashMap<>();

    List<String> get(String name, Supplier<List<String>> resolve) {
        if (name == null) {
            return Collections.emptyList();
        }
        ConcurrentHashMap<String, List<String>> current = entries;
        List<String> found = current.get(name);
        if (found != null) {
            return found;
        }
        synchronized (this) {
            current = entries;
            found = current.get(name);
            if (found != null) {
                return found;
            }
            if (current.size() >= MAX_NAMES) {
                current = new ConcurrentHashMap<>();
                entries = current;
            }
            found = Collections.unmodifiableList(new ArrayList<>(resolve.get()));
            current.put(name, found);
            return found;
        }
    }

    void invalidate() {
        entries = new ConcurrentHashMap<>();
    }
}
