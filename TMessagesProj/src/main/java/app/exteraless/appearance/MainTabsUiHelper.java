package app.exteraless.appearance;

import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import me.vkryl.android.animator.BoolAnimator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProviderBuilder;
import org.telegram.ui.MainTabsLayout;

import tw.nekomimi.nekogram.helpers.MainTabsHelper;

/**
 * Material 3 для нижней панели вкладок.
 *
 * Размеры не-M3 варианта берутся из MainTabsHelper (в NagramX есть компактный режим
 * без подписей), поэтому «старая» ветка возвращает текущие значения форка, а не
 * зашитые 72/28/7.666 — при выключенном флаге поведение остаётся прежним.
 */
public final class MainTabsUiHelper {

    private MainTabsUiHelper() {
    }

    public static boolean isMaterial3NavigationBar() {
        return AppearanceConfig.newNavigationBarStyle();
    }

    /**
     * Панель как в Telegram iOS: капсула почти во всю ширину экрана, 60dp высотой,
     * вкладки растянуты по ширине. Это та же не-M3 ветка, только шире и выше,
     * поэтому все размеры считаются через MainTabsHelper. M3 приоритетнее.
     */
    public static boolean isIosNavigationBar() {
        return AppearanceConfig.iosNavigationBarStyle() && !isMaterial3NavigationBar();
    }

    /** В iOS-стиле обёртка добавляет к системным инсетам ещё 8dp по бокам — вместе с подложкой выходит 16dp. */
    private static int getWrapperSideInset() {
        return isIosNavigationBar() ? AndroidUtilities.dp(8) : 0;
    }

    /** M3 — 64dp, иначе высота из MainTabsHelper. */
    public static int getTabsViewHeightDp() {
        return isMaterial3NavigationBar() ? 64 : MainTabsHelper.getMainTabsHeightWithMargins();
    }

    /** M3 — dp(64) плюс системный отступ снизу, иначе высота из MainTabsHelper. */
    public static int getTabsViewHeight(int bottomInset) {
        return isMaterial3NavigationBar()
                ? AndroidUtilities.dp(64) + bottomInset
                : AndroidUtilities.dp(MainTabsHelper.getMainTabsHeightWithMargins());
    }

    /** В iOS-стиле капсула на 4dp выше, но таб внутри остаётся 48dp — лишнее уходит в паддинг. */
    public static int getTabsInnerPaddingVertical() {
        if (isMaterial3NavigationBar()) {
            return 0;
        }
        return AndroidUtilities.dp(MainTabsHelper.getMainTabsMargin() + (isIosNavigationBar() ? 6 : 4));
    }

    public static int getTabsInnerPaddingHorizontal() {
        return isMaterial3NavigationBar() ? 0 : AndroidUtilities.dp(MainTabsHelper.getMainTabsMargin() + 4);
    }

    /** В M3 подложка без отступа от краёв. */
    public static int getBackgroundInset() {
        return isMaterial3NavigationBar() ? 0 : AndroidUtilities.dp(MainTabsHelper.getMainTabsMargin() - 0.334f);
    }

    /** В M3 подложка прямоугольная, иначе скругление в половину высоты. */
    public static float getBackgroundRadius() {
        return isMaterial3NavigationBar() ? 0 : AndroidUtilities.dp(MainTabsHelper.getMainTabsHeight() / 2f);
    }

    /** В M3 и iOS-стиле панель растянута на всю ширину. */
    public static int getTabsViewWidth() {
        return isMaterial3NavigationBar() || isIosNavigationBar() ? LayoutHelper.MATCH_PARENT : MainTabsHelper.getTabsViewWidth();
    }

    /** Сдвиг кнопки «написать» над панелью: в M3 всегда 64. */
    public static int getTabsFabOffsetDp() {
        return isMaterial3NavigationBar() ? 64 : MainTabsHelper.getMainTabsHeight() + MainTabsHelper.getMainTabsMargin();
    }

    /** В M3 и iOS-стиле панель во всю ширину (в M3 ещё и без внутренних отступов). */
    public static void applyTabsLayoutStyle(MainTabsLayout layout, int legacyMaxWidthPx) {
        final int paddingH = getTabsInnerPaddingHorizontal();
        final int paddingV = getTabsInnerPaddingVertical();
        layout.setPadding(paddingH, paddingV, paddingH, paddingV);
        layout.setMaxWidth(isMaterial3NavigationBar() || isIosNavigationBar() ? 0 : legacyMaxWidthPx);
    }

    /**
     * В M3 нижний системный отступ уходит внутрь панели, а обёртка его не держит.
     * Левый и правый инсеты несёт обёртка, поэтому переданы отдельно.
     */
    public static void applyTabsBottomInset(MainTabsLayout layout, View wrapper, int bottomInset, int leftInset, int rightInset) {
        applyTabsBottomInset(layout, bottomInset);
        final int side = getWrapperSideInset();
        wrapper.setPadding(leftInset + side, 0, rightInset + side, isMaterial3NavigationBar() ? 0 : bottomInset);
    }

    public static void applyTabsBottomInset(MainTabsLayout layout, int bottomInset) {
        final int height = getTabsViewHeight(bottomInset);
        final ViewGroup.LayoutParams lp = layout.getLayoutParams();
        if (lp != null && lp.height != height) {
            lp.height = height;
            layout.setLayoutParams(lp);
        }
        final int paddingBottom = getTabsInnerPaddingVertical() + (isMaterial3NavigationBar() ? bottomInset : 0);
        if (layout.getPaddingBottom() != paddingBottom) {
            layout.setPadding(layout.getPaddingLeft(), layout.getPaddingTop(), layout.getPaddingRight(), paddingBottom);
        }
    }

    /** В M3 обводки у панели нет; значения не-M3 ветки — те же, что в mainTabs. */
    public static BlurredBackgroundProviderBuilder applyBackgroundStroke(BlurredBackgroundProviderBuilder builder) {
        if (isMaterial3NavigationBar()) {
            return builder
                    .setStrokeColorTop(0, 0)
                    .setStrokeColorBottom(0, 0)
                    .setStrokeWidth(0, 0);
        }
        return builder
                .setStrokeColorTop(0x11000000, 0x06FFFFFF)
                .setStrokeColorBottom(0x20000000, 0x11FFFFFF)
                .setStrokeWidth(AndroidUtilities.dpf2(0.4f), AndroidUtilities.dpf2(0.4f));
    }

    public static void setBlurBounds(RectF rectF, View view, int bottomInset) {
        final int bottom;
        final int top;
        if (isMaterial3NavigationBar()) {
            bottom = view.getMeasuredHeight();
            top = bottom - AndroidUtilities.dp(64) - bottomInset;
        } else {
            bottom = view.getMeasuredHeight() - bottomInset - AndroidUtilities.dp(MainTabsHelper.getMainTabsMargin());
            top = bottom - AndroidUtilities.dp(MainTabsHelper.getMainTabsHeight());
        }
        rectF.set(0, top, view.getMeasuredWidth(), bottom);
    }

    public static void setMainTabSelectedIndicatorBounds(RectF rectF, float width, int height) {
        final float w = Math.min(AndroidUtilities.dp(56), Math.max(0, width - AndroidUtilities.dp(4) * 2));
        final float h = Math.min(AndroidUtilities.dp(32), height);
        final float x = (width - w) / 2f;
        final float y = AndroidUtilities.dp(6);
        rectF.set(x, y, w + x, h + y);
    }

    public static int getMainTabSelectedIndicatorColor(int color, float factor) {
        return Theme.multAlpha(color, factor * 0.125f);
    }

    public static float getMaterial3MainTabIconTopDp() {
        return 10.0f;
    }

    public static float getMaterial3MainTabAvatarTopDp() {
        return getMaterial3MainTabIconTopDp() + 1.0f;
    }

    public static float getMainTabCounterCenterY(boolean material3) {
        return material3
                ? AndroidUtilities.dp(getMaterial3MainTabIconTopDp() + 6.0f)
                : AndroidUtilities.dpf2(10.0f);
    }

    public static float getSelectedBackgroundScaleX(boolean material3, float factor) {
        return AndroidUtilities.lerp(material3 ? 0.4f : 0.6f, 1.0f, factor);
    }

    public static float getSelectedBackgroundScaleY(boolean material3, float factor) {
        return material3 ? 1.0f : getSelectedBackgroundScaleX(false, factor);
    }

    public static void applyMaterial3MainTabStyle(TextView textView, BoolAnimator animator) {
        animator.setDuration(500L);
        animator.setInterpolator(CubicBezierInterpolator.Emphasized);
        textView.setIncludeFontPadding(false);
        textView.setLetterSpacing(0.04166667f);
        textView.setLayoutParams(LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 16.0f, 49, 0.0f, 42.0f, 0.0f, 0.0f));
    }

    public static void setMaterial3MainTabSelected(BoolAnimator selected, BoolAnimator background, boolean value, boolean animated) {
        selected.setValue(value, animated);
        background.setDuration(value ? 100L : 200L);
        background.setInterpolator(value ? CubicBezierInterpolator.Emphasized : CubicBezierInterpolator.EmphasizedAccelerate);
        background.setValue(value, animated);
    }

    public static Drawable createMainTabsScrimBackground(Theme.ResourcesProvider resourcesProvider, boolean circle) {
        final int color = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);
        final ShapeDrawable drawable = circle
                ? Theme.createCircleDrawable(AndroidUtilities.dp(40), color)
                : Theme.createRoundRectDrawable(AndroidUtilities.dp(28), color);
        drawable.getPaint().setShadowLayer(AndroidUtilities.dp(6), 0, AndroidUtilities.dp(1), Theme.multAlpha(0xFF000000, 0.15f));
        if (!isMaterial3NavigationBar()) {
            return drawable;
        }
        return new InsetDrawable(drawable, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8), 0);
    }
}
