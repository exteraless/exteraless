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
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.LaunchActivity;

import app.exteraless.pillstack.PillStackSettingsActivity;
import app.exteraless.pillstack.PillType;

@SuppressLint("ViewConstructor")
public class LastSeenPill extends BasePill {

    private final LinearLayout layout;
    private final ImageView iconView;
    private final PillAnimatedTextView textView;
    private int requestGeneration;
    private boolean online;

    public LastSeenPill(Context context, Theme.ResourcesProvider resourcesProvider) {
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
        iconView.setImageResource(R.drawable.msg_online);
        layout.addView(iconView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

        textView = new PillAnimatedTextView(context, true, true, true);
        textView.setTextSize(AndroidUtilities.dp(13));
        textView.setIncludeFontPadding(false);
        textView.setTypeface(AndroidUtilities.bold());
        textView.adaptWidth = true;
        layout.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        setLoadingTargetView(layout);
        updateColors();
        ScaleStateListAnimator.apply(layout);
    }

    @Override
    public int getPillId() {
        return PillType.LAST_SEEN.id;
    }

    @Override
    public long getRefreshInterval() {
        return 60_000L;
    }

    @Override
    public void onUpdateData(boolean force) {
        final int account = UserConfig.selectedAccount;
        final TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        if (self == null) {
            setStatus("—", false);
            return;
        }
        final TLRPC.InputUser inputUser = MessagesController.getInstance(account).getInputUser(self);
        if (inputUser == null) {
            setStatus("—", false);
            return;
        }
        final int generation = ++requestGeneration;
        if (force) {
            startLoading();
        }
        final TLRPC.TL_users_getUsers req = new TLRPC.TL_users_getUsers();
        req.id.add(inputUser);
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (generation != requestGeneration) {
                return;
            }
            stopLoading();
            TLRPC.User user = null;
            if (res instanceof Vector) {
                for (Object object : ((Vector) res).objects) {
                    if (object instanceof TLRPC.User && ((TLRPC.User) object).id == self.id) {
                        user = (TLRPC.User) object;
                    }
                }
            }
            if (user == null || user.status == null) {
                setStatus("—", false);
                return;
            }
            final boolean isOnline = user.status instanceof TLRPC.TL_userStatusOnline && user.status.expires > ConnectionsManager.getInstance(account).getCurrentTime();
            final int lastSeen = isOnline ? 0 : app.exteraless.ghost.OwnLastSeen.seconds(account, user);
            final String status;
            if (isOnline) {
                status = LocaleController.getString(R.string.Online);
            } else if (lastSeen > 0 && app.exteraless.ghost.OwnLastSeen.isToday(lastSeen)) {
                status = app.exteraless.ghost.OwnLastSeen.time(lastSeen);
            } else if (lastSeen > 0) {
                status = app.exteraless.ghost.OwnLastSeen.format(lastSeen);
            } else {
                status = LocaleController.formatUserStatus(account, user);
            }
            setStatus(status, isOnline);
            markDataUpdated();
        }));
    }

    private void setStatus(String text, boolean isOnline) {
        final CharSequence old = textView.getText();
        if (old == null || !TextUtils.equals(old, text)) {
            animateSizeChange();
            textView.setText(text, true);
        }
        if (online != isOnline) {
            online = isOnline;
            updateColors();
        }
    }

    @Override
    public void onPillClicked() {
        onUpdateData(true);
    }

    @Override
    public boolean onPillLongClicked() {
        final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null) {
            return false;
        }
        ItemOptions.makeOptions(fragment, this)
                .add(R.drawable.msg_retry, LocaleController.getString(R.string.Refresh), () -> onUpdateData(true))
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
        final int color = online
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
