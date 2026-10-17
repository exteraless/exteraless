package app.exteraless.pillstack.pills;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ProxyListActivity;

import app.exteraless.pillstack.PillStackSettingsActivity;
import app.exteraless.pillstack.PillType;

/** Состояние прокси: выключен / подключается / пинг. */
@SuppressLint("ViewConstructor")
public class ProxyPill extends BasePill implements NotificationCenter.NotificationCenterDelegate {

    private final LinearLayout layout;
    private final ImageView iconView;
    private final PillAnimatedTextView textView;
    private int lastAccount;

    public ProxyPill(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider);

        layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER);
        layout.setMinimumWidth(AndroidUtilities.dp(48));
        layout.setPadding(AndroidUtilities.dp(8), 0, AndroidUtilities.dp(10), 0);
        addView(layout, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 28,
                (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL));

        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        layout.addView(iconView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0, 0, 2, 0));

        textView = new PillAnimatedTextView(context, true, true, true);
        textView.setTextSize(AndroidUtilities.dp(13));
        textView.setIncludeFontPadding(false);
        textView.setTypeface(AndroidUtilities.bold());
        textView.adaptWidth = true;
        layout.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        setLoadingTargetView(layout);
        updateColors();
        ScaleStateListAnimator.apply(layout);
        onUpdateData(false);
    }

    @Override
    public int getPillId() {
        return PillType.PROXY.id;
    }

    @Override
    public long getRefreshInterval() {
        return 0;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        onUpdateData(true);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxyCheckDone);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxyPingUpdated);
        lastAccount = UserConfig.selectedAccount;
        NotificationCenter.getInstance(lastAccount).addObserver(this, NotificationCenter.didUpdateConnectionState);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxySettingsChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxyCheckDone);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxyPingUpdated);
        NotificationCenter.getInstance(lastAccount).removeObserver(this, NotificationCenter.didUpdateConnectionState);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.proxySettingsChanged
                || id == NotificationCenter.proxyCheckDone
                || id == NotificationCenter.proxyPingUpdated
                || id == NotificationCenter.didUpdateConnectionState) {
            onUpdateData(true);
        }
    }

    @Override
    public void onUpdateData(boolean animated) {
        boolean enabled = SharedConfig.isProxyEnabled();
        int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        boolean connected = state == ConnectionsManager.ConnectionStateConnected
                || state == ConnectionsManager.ConnectionStateUpdating;
        CharSequence old = textView.getText();
        String previous = old != null ? old.toString() : "";

        String text;
        if (!enabled || SharedConfig.currentProxy == null) {
            iconView.setImageResource(R.drawable.drawer_proxy_off);
            text = LocaleController.getString(R.string.Proxy);
            stopLoading();
        } else if (connected) {
            long ping = Utilities.clamp(SharedConfig.currentProxy.ping, 9999L, 0L);
            iconView.setImageResource(R.drawable.drawer_proxy_on);
            text = ping > 0
                    ? LocaleController.formatString(R.string.PillStackProxyPing, ping)
                    : LocaleController.getString(R.string.MenuProxyConnected);
            stopLoading();
        } else {
            iconView.setImageResource(R.drawable.drawer_proxy_off);
            text = LocaleController.getString(R.string.MenuProxyConnecting);
            startLoading();
        }

        if (animated || !TextUtils.equals(previous, text)) {
            if (animated) {
                animateSizeChange();
            }
            textView.setText(text, animated);
        }
        updateColors();
    }

    @Override
    public void onPillClicked() {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment != null) {
            fragment.presentFragment(new ProxyListActivity());
        }
    }

    @Override
    public boolean onPillLongClicked() {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null) {
            return false;
        }
        ItemOptions.makeOptions(fragment, this)
                .add(R.drawable.msg_settings, LocaleController.getString(R.string.Settings),
                        () -> fragment.presentFragment(new PillStackSettingsActivity()))
                .setDrawScrim(false)
                .setDimAlpha(0)
                .show();
        return true;
    }

    @Override
    public void drawableHotspotChanged(float x, float y) {
        if (loading) {
            return;
        }
        super.drawableHotspotChanged(x, y);
        layout.drawableHotspotChanged(x - layout.getLeft(), y - layout.getTop());
    }

    @Override
    public void setPressed(boolean pressed) {
        if (loading) {
            pressed = false;
        }
        super.setPressed(pressed);
        layout.setPressed(pressed);
    }

    @Override
    public void updateColors() {
        boolean enabled = SharedConfig.isProxyEnabled();
        int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        boolean connected = enabled && SharedConfig.currentProxy != null
                && (state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating);
        int color = connected
                ? getThemedColor(Theme.key_windowBackgroundWhiteGreenText)
                : getThemedColor(Theme.key_windowBackgroundWhiteBlackText, 0.75f);
        layout.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(14),
                Theme.isCurrentThemeDark() ? getThemedColor(Theme.key_windowBackgroundWhite) : Theme.multAlpha(color, 0.09f),
                Theme.multAlpha(color, 0.1f)));
        textView.setTextColor(color);
        iconView.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
        updateLoadingColors();
    }
}
