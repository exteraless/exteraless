package app.exteraless.drawer;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;

/**
 * Счётчик непрочитанных на строке аккаунта.
 * exteraGram: {@code com/exteragram/messenger/drawer/DrawerAccountUnreadBadge.java} (68 строк).
 */
final class DrawerAccountUnreadBadge {

    private final RectF rect = new RectF();

    private int account = -1;
    private int badgeWidth;
    private String text;
    private int textWidth;
    private boolean visible;

    void bind(int account, SimpleTextView nameView) {
        this.account = account;
        update(nameView);
    }

    /** Бейдж по центру строки, отступ справа 12.5dp. */
    void draw(View view, Canvas canvas) {
        if (!visible) {
            return;
        }
        final float height = AndroidUtilities.dp(23.0f);
        final float top = (view.getMeasuredHeight() - height) / 2.0f;
        final float left = view.getMeasuredWidth() - AndroidUtilities.dp(12.5f) - badgeWidth;
        rect.set(left, top, left + badgeWidth, top + height);
        canvas.drawRoundRect(rect, AndroidUtilities.dp(11.5f), AndroidUtilities.dp(11.5f), Theme.dialogs_countPaint);
        final float centerY = rect.centerY()
                - ((Theme.dialogs_countTextPaint.descent() + Theme.dialogs_countTextPaint.ascent()) / 2.0f);
        canvas.drawText(text, rect.left + ((rect.width() - textWidth) / 2.0f), centerY, Theme.dialogs_countTextPaint);
    }

    /** При одном аккаунте бейдж не нужен. */
    void update(SimpleTextView nameView) {
        visible = false;
        text = null;
        textWidth = 0;
        badgeWidth = 0;
        if (account < 0 || UserConfig.getVisibleAccountsCount() <= 1
                || !NotificationsController.getInstance(account).showBadgeNumber) {
            nameView.setRightPadding(0);
            return;
        }
        final int unread = MessagesStorage.getInstance(account).getMainUnreadCount();
        if (unread <= 0) {
            nameView.setRightPadding(0);
            return;
        }
        visible = true;
        text = Integer.toString(unread);
        textWidth = (int) Math.ceil(Theme.dialogs_countTextPaint.measureText(text));
        badgeWidth = Math.max(AndroidUtilities.dp(10.0f), textWidth) + AndroidUtilities.dp(14.0f);
        nameView.setRightPadding(badgeWidth + AndroidUtilities.dp(12.0f));
    }
}
