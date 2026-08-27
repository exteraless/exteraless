package tw.nekomimi.nekogram.helpers;

import app.exteraless.appearance.AppearanceConfig;
import app.exteraless.appearance.MainTabsUiHelper;

import org.telegram.ui.MainTabsActivity;

import xyz.nextalone.nagram.NaConfig;

public final class MainTabsHelper {
    public static final int MAIN_TABS_HEIGHT = 56;
    public static final int MAIN_TABS_HEIGHT_IOS = 60;
    public static final int MAIN_TABS_MARGIN = 8;
    public static final int MAIN_TABS_MARGIN_COMPACT = 4;
    public static final int FILTER_TABS_HEIGHT = 36;
    public static final int TAB_WIDTH = 80;
    public static final int TAB_PADDING = 4;

    private MainTabsHelper() {
    }

    public static boolean isMainTabsHideTitleStyle() {
        return NaConfig.INSTANCE.getMainTabsHideTitles().Bool();
    }

    public static int getMainTabsHeight() {
        if (isMainTabsHideTitleStyle()) {
            return FILTER_TABS_HEIGHT;
        }
        return MainTabsUiHelper.isIosNavigationBar() ? MAIN_TABS_HEIGHT_IOS : MAIN_TABS_HEIGHT;
    }

    public static int getMainTabsMargin() {
        return isMainTabsHideTitleStyle() ? MAIN_TABS_MARGIN_COMPACT : MAIN_TABS_MARGIN;
    }

    public static int getMainTabsHeightWithMargins() {
        return getMainTabsHeight() + getMainTabsMargin() * 2;
    }

    public static boolean isContactsTabHidden() {
        return NaConfig.INSTANCE.getMainTabsHideContacts().Bool();
    }

    public static boolean isCallsOrSettingsTabHidden() {
        return NaConfig.INSTANCE.getMainTabsHideCallsSettings().Bool();
    }

    public static boolean isProfileTabHidden() {
        return NaConfig.INSTANCE.getMainTabsHideProfile().Bool();
    }

    public static boolean isFeedTabShown() {
        return AppearanceConfig.showFeedTab();
    }

    public static boolean hasContactsOrFeedTab() {
        return !isContactsTabHidden() || isFeedTabShown();
    }

    public static int getChatsPosition() {
        return 0;
    }

    public static int getContactsPosition() {
        return 1;
    }

    public static int getCallsOrSettingsPosition() {
        if (isCallsOrSettingsTabHidden()) {
            return -1;
        }
        return hasContactsOrFeedTab() ? 2 : 1;
    }

    public static int getProfilePosition() {
        if (isProfileTabHidden()) {
            return -1;
        }
        int position = hasContactsOrFeedTab() ? 3 : 2;
        return isCallsOrSettingsTabHidden() ? position - 1 : position;
    }

    public static int getFragmentsCount() {
        int count = MainTabsActivity.TABS_COUNT;
        if (!hasContactsOrFeedTab()) {
            count--;
        }
        if (isCallsOrSettingsTabHidden()) {
            count--;
        }
        if (isProfileTabHidden()) {
            count--;
        }
        return count;
    }

    public static int getTabsViewWidth() {
        return TAB_WIDTH * getFragmentsCount() + (getMainTabsMargin() + TAB_PADDING) * 2;
    }
}
