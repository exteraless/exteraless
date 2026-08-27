package com.exteragram.messenger.utils.system;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import app.exteraless.utils.AppUtils;

public final class SystemUtils {

    private SystemUtils() {
    }

    public static int getRoundVideoResolution() {
        return AppUtils.getRoundVideoResolution(
                MessagesController.getInstance(UserConfig.selectedAccount).roundVideoSize);
    }

    public static int getRoundVideoBitrate() {
        return MessagesController.getInstance(UserConfig.selectedAccount).roundVideoBitrate;
    }

    public static int getRoundAudioBitrate() {
        return MessagesController.getInstance(UserConfig.selectedAccount).roundAudioBitrate;
    }

    public static long getRoundVideoMaxDurationMs() {
        return Utilities.clamp(83886080000L / ((Math.max(1, getRoundVideoBitrate())
                + Math.max(0, getRoundAudioBitrate())) * 1024L), 60000L, 10000L);
    }
}
