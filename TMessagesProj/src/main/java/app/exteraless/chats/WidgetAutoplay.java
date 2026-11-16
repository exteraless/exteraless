package app.exteraless.chats;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class WidgetAutoplay {

    private static final Set<String> allowed = ConcurrentHashMap.newKeySet();

    private WidgetAutoplay() {
    }

    public static boolean mayRun(String storageKey) {
        return !ChatsConfig.dontAutoplayWidgets() || allowed.contains(storageKey);
    }

    public static void allow(String storageKey) {
        allowed.add(storageKey);
    }
}
