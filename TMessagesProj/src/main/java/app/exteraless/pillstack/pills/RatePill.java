package app.exteraless.pillstack.pills;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.LaunchActivity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.atomic.AtomicReference;

import app.exteraless.pillstack.ExchangeRates;
import app.exteraless.pillstack.PillCurrencies;
import app.exteraless.pillstack.PillStackEvents;
import app.exteraless.pillstack.PillStackSettingsActivity;

/** База для пилюль курсов: BTC, TON, USD. */
@SuppressLint("ViewConstructor")
public abstract class RatePill extends BasePill implements PillStackEvents.Listener {

    /** Последнее показанное значение, общее для всех экземпляров одной пилюли. */
    public static final class RateCache {
        final AtomicReference<String> cachedPrice = new AtomicReference<>();
        final AtomicReference<String> cachedCurrency = new AtomicReference<>();
    }

    private final RateCache cache;
    private final String baseCurrency;
    private final int scale;
    private final int iconResId;
    private final ColoredBackground background;

    private final LinearLayout layout;
    private final ImageView iconView;
    private final AnimatedTextView textView;
    private boolean requestInFlight;

    public RatePill(Context context, Theme.ResourcesProvider resourcesProvider, RateCache cache,
                    String baseCurrency, int scale, int iconResId, ColoredBackground background) {
        super(context, resourcesProvider);
        this.cache = cache;
        this.baseCurrency = baseCurrency;
        this.scale = scale;
        this.iconResId = iconResId;
        this.background = background;

        layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER);
        layout.setMinimumWidth(AndroidUtilities.dp(48));
        layout.setPadding(AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8), 0);
        addView(layout, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 28,
                (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL));

        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        layout.addView(iconView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

        textView = new AnimatedTextView(context, true, true, true);
        textView.setTextSize(AndroidUtilities.dp(13));
        textView.setIncludeFontPadding(false);
        textView.setTypeface(AndroidUtilities.bold());
        textView.adaptWidth = true;
        layout.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        setLoadingTargetView(layout);
        updateColors();
        ScaleStateListAnimator.apply(layout);

        String cached = cache.cachedPrice.get();
        if (cached != null) {
            setData(cached, false);
        }
    }

    public abstract String getTargetSelection();

    public abstract void setTargetSelection(String currency);

    /**
     * Источник курса «сколько target стоит одна единица базы». По умолчанию курсы
     * {@link ExchangeRates}; пилюли с внешним источником (золото) переопределяют.
     */
    protected void fetchRate(String target, boolean force, Utilities.Callback<BigDecimal> callback) {
        if (force) {
            ExchangeRates.clearCache();
        }
        ExchangeRates.fetch(state ->
                callback.run(state == null ? null : state.getRate(baseCurrency, target)));
    }

    public String[] getTargetCurrencies() {
        return PillCurrencies.TARGET_CURRENCIES;
    }

    @Override
    public long getRefreshInterval() {
        return 5 * 60 * 1000L;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (PillStackEvents.checkAndClearPendingUpdate(getPillId()) || cache.cachedPrice.get() == null || isRefreshDue()) {
            onUpdateData(true);
        }
        PillStackEvents.addListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        PillStackEvents.removeListener(this);
    }

    @Override
    public void onPillStackSettingsChanged(int[] pillIds) {
        if (PillStackEvents.shouldUpdatePill(pillIds, getPillId())) {
            PillStackEvents.checkAndClearPendingUpdate(getPillId());
            onUpdateData(true);
        }
    }

    @Override
    public void onUpdateData(boolean force) {
        final String target = ExchangeRates.resolveTargetCurrency(getTargetSelection());
        String cached = cache.cachedPrice.get();
        if (!TextUtils.equals(target, cache.cachedCurrency.get())) {
            cached = null;
        }
        if (!force && cached != null && !isRefreshDue()) {
            setData(cached, false);
            return;
        }
        if (requestInFlight) {
            return;
        }
        requestInFlight = true;
        if (force) {
            animateSizeChange();
        }
        startLoading();
        if (cached == null && cache.cachedPrice.get() == null) {
            iconView.setVisibility(GONE);
            textView.setVisibility(GONE);
        } else {
            iconView.setImageResource(iconResId);
            iconView.setVisibility(VISIBLE);
            textView.setVisibility(VISIBLE);
        }
        fetchRate(target, force, rate -> {
            requestInFlight = false;
            if (rate == null) {
                String fallback = cache.cachedPrice.get();
                if (fallback != null) {
                    setData(fallback, true);
                } else {
                    setErrorState(true);
                }
                return;
            }
            String price = formatPrice(rate, target);
            cache.cachedPrice.set(price);
            cache.cachedCurrency.set(target);
            setData(price, true);
            markDataUpdated();
        });
    }

    public String formatPrice(BigDecimal value, String currency) {
        String formatted = PillCurrencies.formatFiatPrice(value, currency);
        if (formatted != null) {
            return formatted;
        }
        return value.setScale(scale, RoundingMode.HALF_UP).toPlainString() + " " + currency;
    }

    private void setData(String price, boolean animated) {
        stopLoading();
        if (animated) {
            animateSizeChange();
        }
        iconView.setImageResource(iconResId);
        iconView.setVisibility(VISIBLE);
        textView.setText(price, animated);
        textView.setVisibility(VISIBLE);
    }

    private void setErrorState(boolean animated) {
        stopLoading();
        if (animated) {
            animateSizeChange();
        }
        iconView.setImageResource(R.drawable.msg_retry);
        iconView.setVisibility(VISIBLE);
        textView.setText(LocaleController.getString(R.string.Retry), animated);
        textView.setVisibility(VISIBLE);
    }

    @Override
    public void onPillClicked() {
        if (iconView.getVisibility() == VISIBLE && textView.getText() != null
                && TextUtils.equals(textView.getText(), LocaleController.getString(R.string.Retry))) {
            onUpdateData(true);
        } else {
            onPillLongClicked();
        }
    }

    @Override
    public boolean onPillLongClicked() {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null) {
            return false;
        }
        final ItemOptions options = ItemOptions.makeOptions(fragment, this, true);
        final ItemOptions swipeback = options.makeSwipeback()
                .add(R.drawable.ic_ab_back, LocaleController.getString(R.string.Back), options::dismiss)
                .addGap();

        final String selection = getTargetSelection();
        for (final String currency : getTargetCurrencies()) {
            swipeback.addChecked(currency.equalsIgnoreCase(selection),
                    PillCurrencies.getTargetCurrencyLabel(currency), () -> {
                        options.dismiss();
                        if (currency.equalsIgnoreCase(selection)) {
                            return;
                        }
                        setTargetSelection(currency);
                        onUpdateData(false);
                    });
        }

        ActionBarMenuSubItem currencyItem = new ActionBarMenuSubItem(options.getContext(), false, false, resourcesProvider);
        currencyItem.setTextAndIcon(LocaleController.getString(R.string.CryptoPillTargetCurrency), R.drawable.msg_language);
        currencyItem.setSubtext(PillCurrencies.getTargetCurrencySubtext(selection));
        currencyItem.setItemHeight(56);
        currencyItem.setOnClickListener((View v) -> options.openSwipeback(swipeback));

        options.addView(currencyItem)
                .addGap()
                .add(R.drawable.msg_retry, LocaleController.getString(R.string.Refresh), () -> onUpdateData(true))
                .add(R.drawable.msg_settings, LocaleController.getString(R.string.Settings),
                        () -> fragment.presentFragment(new PillStackSettingsActivity()))
                .setSwipebackGravity(!LocaleController.isRTL, false)
                .setDrawScrim(false)
                .setGravity(LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT)
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
        layout.setBackground(background);
        textView.setTextColor(0xFFFFFFFF);
        iconView.setColorFilter(0xFFFFFFFF);
        updateLoadingColors();
    }

    @Override
    public void updateLoadingColors() {
        if (loadingDrawable != null) {
            loadingDrawable.setColors(Theme.multAlpha(0xFFFFFFFF, 0.1f), Theme.multAlpha(0xFFFFFFFF, 0.3f));
        }
    }
}
