package app.exteraless.chats;

import static org.telegram.messenger.AndroidUtilities.dp;

import org.telegram.messenger.AndroidUtilities;

public final class WideChannelPostLayout {

    private static final int OUTER_INSET_DP = 9;
    private static final int MEDIA_BACKGROUND_CONTENT_INSET_DP = 8;
    private static final int REGULAR_BACKGROUND_CONTENT_INSET_DP = 17;

    private WideChannelPostLayout() {
    }

    public static int backgroundWidth(int viewportWidth, int leadingInset, boolean mediaBackground) {
        int width = viewportWidth - leadingInset - dp(OUTER_INSET_DP * 2);
        if (mediaBackground) {
            width -= dp(OUTER_INSET_DP);
        }
        return Math.max(dp(1), width);
    }

    public static int messageTextWidth(int viewportWidth, int leadingInset) {
        return Math.max(dp(1), backgroundWidth(viewportWidth, leadingInset, false) - dp(31));
    }

    public static int mediaContentWidth(int backgroundWidth, boolean mediaBackground) {
        int contentInset = mediaBackground
                ? MEDIA_BACKGROUND_CONTENT_INSET_DP
                : REGULAR_BACKGROUND_CONTENT_INSET_DP;
        return Math.max(dp(1), backgroundWidth - dp(contentInset));
    }

    public static int mediaContentWidth(int viewportWidth, int leadingInset) {
        return mediaContentWidth(backgroundWidth(viewportWidth, leadingInset, false), false);
    }

    public static int groupedMediaViewportWidth(int viewportWidth, int leadingInset) {
        return Math.max(dp(1), viewportWidth - leadingInset);
    }

    public static int groupedMediaContentSpanCount() {
        int viewportWidth = AndroidUtilities.isTablet()
                ? AndroidUtilities.getMinTabletSide()
                : AndroidUtilities.displaySize.x;
        return groupedMediaContentSpanCount(viewportWidth);
    }

    public static int groupedMediaContentSpanCount(int groupedMediaViewportWidth) {
        int viewportWidth = groupedMediaViewportWidth;
        if (viewportWidth <= 0) {
            return 1000;
        }
        int contentWidth = Math.max(dp(1), viewportWidth - dp(OUTER_INSET_DP * 2));
        return Math.max(1, Math.min(1000, Math.round(contentWidth * 1000f / viewportWidth)));
    }
}
