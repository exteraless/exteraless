package app.exteraless.nowplaying;

import android.text.TextUtils;

import java.util.Locale;

public final class ProfileMusicMark {

    private static final String PREFIX = "_oelfm_";
    private static final String LEGACY_PREFIX = ".oelfm.";
    private static final String CHARSET = "abcdefghijklmnopqrstuvwxyz0123456789-_";
    private static final int MAX_LENGTH = 32;
    private static final int CHECKSUM_MASK = 0xFFF;
    private static final int CHECKSUM_LENGTH = 3;

    private ProfileMusicMark() {
    }

    public static String stamp(String fileName, String nick, long ownerId) {
        String base = strip(fileName);
        if (TextUtils.isEmpty(base)) {
            base = "audio.mp3";
        }
        String lower = nick == null ? "" : nick.toLowerCase();
        if (!isValid(lower)) {
            return base;
        }
        int dot = base.lastIndexOf('.');
        String name = dot > 0 ? base.substring(0, dot) : base;
        String extension = dot > 0 ? base.substring(dot) : "";
        return name + PREFIX + lower + "_" + checksumOf(ownerId) + extension;
    }

    public static String nickFrom(String fileName, long ownerId) {
        if (TextUtils.isEmpty(fileName)) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        int at = stem.lastIndexOf(PREFIX);
        if (at >= 0) {
            String tail = stem.substring(at + PREFIX.length());
            int split = tail.length() - CHECKSUM_LENGTH - 1;
            if (split > 0 && tail.charAt(split) == '_') {
                String nick = tail.substring(0, split);
                if (isValid(nick) && tail.substring(split + 1).equals(checksumOf(ownerId))) {
                    return nick;
                }
            }
        }
        return legacyNickFrom(fileName, ownerId);
    }

    public static String strip(String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return fileName;
        }
        String legacy = legacyStrip(fileName);
        int dot = legacy.lastIndexOf('.');
        String stem = dot > 0 ? legacy.substring(0, dot) : legacy;
        String extension = dot > 0 ? legacy.substring(dot) : "";
        for (int at = stem.indexOf(PREFIX); at >= 0; at = stem.indexOf(PREFIX, at + 1)) {
            if (isMarkTail(stem.substring(at + PREFIX.length()))) {
                String name = stem.substring(0, at);
                return (TextUtils.isEmpty(name) ? "audio" : name) + extension;
            }
        }
        return legacy;
    }

    private static String legacyNickFrom(String fileName, long ownerId) {
        int at = fileName.indexOf(LEGACY_PREFIX);
        if (at < 0) {
            return null;
        }
        int nickAt = at + LEGACY_PREFIX.length();
        int nickEnd = fileName.indexOf('.', nickAt);
        if (nickEnd < 0 || nickEnd + 1 + CHECKSUM_LENGTH > fileName.length()) {
            return null;
        }
        String nick = fileName.substring(nickAt, nickEnd);
        if (!isValid(nick)) {
            return null;
        }
        String stamped = fileName.substring(nickEnd + 1, nickEnd + 1 + CHECKSUM_LENGTH);
        if (!stamped.equals(checksumOf(ownerId))) {
            return null;
        }
        return nick;
    }

    private static String legacyStrip(String fileName) {
        int at = fileName.indexOf(LEGACY_PREFIX);
        if (at < 0) {
            return fileName;
        }
        int nickAt = at + LEGACY_PREFIX.length();
        int nickEnd = fileName.indexOf('.', nickAt);
        if (nickEnd < 0 || nickEnd + 1 + CHECKSUM_LENGTH > fileName.length()) {
            return fileName;
        }
        return fileName.substring(0, at) + fileName.substring(nickEnd + 1 + CHECKSUM_LENGTH);
    }

    private static boolean isMarkTail(String tail) {
        if (TextUtils.isEmpty(tail)) {
            return false;
        }
        for (int i = 0; i < tail.length(); i++) {
            if (CHARSET.indexOf(tail.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValid(String nick) {
        if (TextUtils.isEmpty(nick) || nick.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < nick.length(); i++) {
            if (CHARSET.indexOf(nick.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static String checksumOf(long ownerId) {
        return String.format(Locale.US, "%03x", checksum(ownerId));
    }

    private static int checksum(long ownerId) {
        long h = ownerId * 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (int) (h & CHECKSUM_MASK);
    }
}
