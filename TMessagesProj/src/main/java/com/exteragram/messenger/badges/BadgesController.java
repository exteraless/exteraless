package com.exteragram.messenger.badges;

import android.content.SharedPreferences;
import android.widget.FrameLayout;

import com.exteragram.messenger.api.dto.BadgeDTO;
import com.exteragram.messenger.badges.source.ApiBadgeSource;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.function.Consumer;

/**
 * Локальный аналог {@code com.exteragram.messenger.badges.BadgesController}.
 *
 * У exteraGram значки приходят с их сервера; сюда это не переносится, поэтому
 * хранилище локальное — SharedPreferences. Плагины каталога подменяют
 * {@link #getBadge}, {@link #isExtera} и {@link #isDeveloper} через Xposed и
 * рисуют значки сами, им нужен именно этот набор имён.
 */
public final class BadgesController {

    public static final BadgesController INSTANCE = new BadgesController();

    private static final ApiBadgeSource apiBadgeSource = new ApiBadgeSource();

    private static final String PREFS_NAME = "exteraless_badges";
    private static final String KEY_DOCUMENT = "badge_document_";
    private static final String KEY_TEXT = "badge_text_";

    private BadgesController() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, 0);
    }

    private static long selfId() {
        TLRPC.User self = UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser();
        return self == null ? 0 : self.id;
    }

    public BadgeDTO getBadge() {
        return getBadgeFor(selfId());
    }

    public BadgeDTO getBadge(TLObject object) {
        if (object instanceof TLRPC.User) {
            return getBadgeFor(((TLRPC.User) object).id);
        }
        if (object instanceof TLRPC.Chat) {
            return getBadgeFor(((TLRPC.Chat) object).id);
        }
        return null;
    }

    public ApiBadgeSource getApiBadgeSource() {
        return apiBadgeSource;
    }

    private BadgeDTO getBadgeFor(long id) {
        if (id == 0) {
            return null;
        }
        BadgeDTO fromSource = apiBadgeSource.getBadge(id, id > 0);
        if (fromSource != null) {
            return fromSource;
        }
        SharedPreferences preferences = prefs();
        long documentId = preferences.getLong(KEY_DOCUMENT + id, 0);
        if (documentId == 0) {
            return null;
        }
        return new BadgeDTO(documentId, preferences.getString(KEY_TEXT + id, null));
    }

    public BadgeDTO getDefaultBadge() {
        return null;
    }

    public BadgeDTO getDefaultBadge(TLRPC.User user) {
        return null;
    }

    public BadgeDTO getSecondaryBadge(TLRPC.User user) {
        return null;
    }

    public boolean hasBadge() {
        return getBadge() != null;
    }

    public boolean hasBadge(TLObject object) {
        return getBadge(object) != null;
    }

    public boolean canChangeBadge() {
        return canChangeBadge(UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser());
    }

    public boolean canChangeBadge(TLRPC.User user) {
        return user != null && (user.id == selfId() || apiBadgeSource.canChangeBadge(user.id));
    }

    public boolean isDeveloper() {
        return apiBadgeSource.isDeveloper(selfId());
    }

    public boolean isDeveloper(TLRPC.User user) {
        return user != null && apiBadgeSource.isDeveloper(user.id);
    }

    public boolean isExtera(long id) {
        return false;
    }

    public boolean isExtera(TLRPC.Chat chat) {
        return false;
    }

    public boolean isTrusted(long id) {
        return false;
    }

    public boolean shouldUseSecondaryBadgeSlot(TLRPC.User user, BadgeDTO badge) {
        return false;
    }

    public void updateBadge(BadgeDTO badge, Consumer<String> callback) {
        long id = selfId();
        if (id != 0) {
            SharedPreferences.Editor editor = prefs().edit();
            if (badge == null) {
                editor.remove(KEY_DOCUMENT + id).remove(KEY_TEXT + id);
            } else {
                editor.putLong(KEY_DOCUMENT + id, badge.getDocumentId())
                        .putString(KEY_TEXT + id, badge.getText());
            }
            editor.apply();
        }
        if (callback != null) {
            callback.accept("ok");
        }
    }

    public void showBadgeBulletin(BaseFragment fragment, BadgeDTO badge, TLRPC.Chat chat,
                                  Theme.ResourcesProvider resourcesProvider, int account) {
        showBadgeBulletin(fragment, badge, chat, resourcesProvider, account, null, Boolean.FALSE);
    }

    public void showBadgeBulletin(BaseFragment fragment, BadgeDTO badge, TLRPC.Chat chat,
                                  Theme.ResourcesProvider resourcesProvider, int account,
                                  FrameLayout containerLayout, Boolean showButton) {
    }

    public void showBadgeBulletin(BaseFragment fragment, BadgeDTO badge, TLRPC.User user,
                                  Theme.ResourcesProvider resourcesProvider, int account) {
        showBadgeBulletin(fragment, badge, user, resourcesProvider, account, null, Boolean.FALSE);
    }

    public void showBadgeBulletin(BaseFragment fragment, BadgeDTO badge, TLRPC.User user,
                                  Theme.ResourcesProvider resourcesProvider, int account,
                                  FrameLayout containerLayout, Boolean showButton) {
    }

    public void showBadgeBulletin(BaseFragment fragment, TLRPC.User user,
                                  Theme.ResourcesProvider resourcesProvider, int account,
                                  FrameLayout containerLayout, Boolean showButton) {
    }
}
