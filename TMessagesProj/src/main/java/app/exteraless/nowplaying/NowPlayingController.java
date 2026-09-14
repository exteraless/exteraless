package app.exteraless.nowplaying;

import org.telegram.tgnet.TLRPC;

public final class NowPlayingController {

    private NowPlayingController() {
    }

    public static boolean shouldShowCard(TLRPC.Document savedMusic) {
        return savedMusic != null;
    }
}
