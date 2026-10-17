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
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.LaunchActivity;

import app.exteraless.pillstack.PillStackSettingsActivity;
import app.exteraless.pillstack.PillType;
import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.settings.GhostModeActivity;

/** Режим призрака: нажатие переключает, долгое — открывает его экран. */
@SuppressLint("ViewConstructor")
public class GhostPill extends BasePill implements NotificationCenter.NotificationCenterDelegate {

    private final LinearLayout layout;
    private final ImageView iconView;
    private final PillAnimatedTextView textView;
    private int lastAccount;

    public GhostPill(Context context, Theme.ResourcesProvider resourcesProvider) {
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
        iconView.setImageResource(R.drawable.ayu_ghost);
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
        return PillType.GHOST.id;
    }

    @Override
    public long getRefreshInterval() {
        return 0;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        onUpdateData(true);
        lastAccount = UserConfig.selectedAccount;
        NotificationCenter.getInstance(lastAccount).addObserver(this, NotificationCenter.mainUserInfoChanged);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getInstance(lastAccount).removeObserver(this, NotificationCenter.mainUserInfoChanged);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.mainUserInfoChanged) {
            onUpdateData(true);
        }
    }

    @Override
    public void onUpdateData(boolean animated) {
        final CharSequence old = textView.getText();
        final String previous = old != null ? old.toString() : "";
        final String text = LocaleController.getString(R.string.GhostMode);

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
        final boolean wasActive = NekoConfig.isGhostModeActive();
        NekoConfig.toggleGhostMode();
        onUpdateData(true);
        NotificationCenter.getInstance(UserConfig.selectedAccount)
                .postNotificationName(NotificationCenter.mainUserInfoChanged);

        final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment != null) {
            BulletinFactory.of(fragment)
                    .createSuccessBulletin(LocaleController.getString(
                            wasActive ? R.string.GhostModeDisabled : R.string.GhostModeEnabled))
                    .show();
        }
    }

    @Override
    public boolean onPillLongClicked() {
        final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null) {
            return false;
        }
        ItemOptions.makeOptions(fragment, this)
                .add(R.drawable.ayu_ghost, LocaleController.getString(R.string.GhostMode),
                        () -> fragment.presentFragment(new GhostModeActivity()))
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
        final boolean active = NekoConfig.isGhostModeActive();
        final int color = active
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
