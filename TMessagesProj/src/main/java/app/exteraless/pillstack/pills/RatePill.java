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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import app.exteraless.pillstack.ExchangeRates;
import app.exteraless.pillstack.GoldPrice;
import app.exteraless.pillstack.PillCurrencies;
import app.exteraless.pillstack.PillStackEvents;
import app.exteraless.pillstack.PillStackSettingsActivity;
import app.exteraless.pillstack.RateInstances;

/** Курс одной валюты к другой. Пара выбирается пользователем и живёт в {@link RateInstances}. */
@SuppressLint("ViewConstructor")
public class RatePill extends BasePill implements PillStackEvents.Listener {

    private static final class RateCache {
        final AtomicReference<String> cachedPrice = new AtomicReference<>();
        final AtomicReference<String> cachedCurrency = new AtomicReference<>();
    }

    private static final Map<String, RateCache> CACHES = new HashMap<>();

    private static RateCache cacheFor(String from) {
        synchronized (CACHES) {
            RateCache cache = CACHES.get(from);
            if (cache == null) {
                cache = new RateCache();
                CACHES.put(from, cache);
            }
            return cache;
        }
    }

    private final int instanceId;

    private final LinearLayout layout;
    private final ImageView iconView;
    private final AnimatedTextView textView;
    private boolean requestInFlight;

    public RatePill(Context context, Theme.ResourcesProvider resourcesProvider, int instanceId) {
        super(context, resourcesProvider);
        this.instanceId = instanceId;

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

        String cached = cache().cachedPrice.get();
        if (cached != null) {
            setData(cached, false);
        }
    }

    @Override
    public int getPillId() {
        return instanceId;
    }

    private RateInstances.Instance instance() {
        return RateInstances.get(instanceId);
    }

    private String baseCurrency() {
        RateInstances.Instance instance = instance();
        return instance == null ? RateInstances.defaultBase() : instance.from;
    }

    private RateCache cache() {
        return cacheFor(baseCurrency());
    }

    public String getTargetSelection() {
        RateInstances.Instance instance = instance();
        return instance == null ? PillCurrencies.AUTO : instance.to;
    }

    public void setTargetSelection(String currency) {
        RateInstances.Instance instance = instance();
        if (instance != null) {
            RateInstances.setPair(instanceId, instance.from, currency);
        }
    }

    public void setBaseSelection(String currency) {
        RateInstances.Instance instance = instance();
        if (instance != null) {
            RateInstances.setPair(instanceId, currency, instance.to);
        }
    }

    private void fetchRate(String target, boolean force, Utilities.Callback<BigDecimal> callback) {
        final String base = baseCurrency();
        if (force) {
            ExchangeRates.clearCache();
            if ("XAU".equals(base)) {
                GoldPrice.clearCache();
            }
        }
        if (!"XAU".equals(base)) {
            ExchangeRates.fetch(state -> callback.run(state == null ? null : state.getRate(base, target)));
            return;
        }
        GoldPrice.fetch(usdPrice -> {
            if (usdPrice == null) {
                callback.run(null);
                return;
            }
            if ("USD".equals(target)) {
                callback.run(usdPrice);
                return;
            }
            ExchangeRates.fetch(state -> {
                BigDecimal conversion = state == null ? null : state.getRate("USD", target);
                callback.run(conversion == null ? null : usdPrice.multiply(conversion));
            });
        });
    }

    private String[] getTargetCurrencies() {
        return PillCurrencies.getTargetCurrencies(baseCurrency());
    }

    @Override
    public long getRefreshInterval() {
        return 5 * 60 * 1000L;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (PillStackEvents.checkAndClearPendingUpdate(getPillId()) || cache().cachedPrice.get() == null || isRefreshDue()) {
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
            updateColors();
            onUpdateData(true);
        }
    }

    @Override
    public void onUpdateData(boolean force) {
        final RateCache cache = cache();
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
            iconView.setImageResource(RateInstances.getBaseIcon(baseCurrency()));
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

    private String formatPrice(BigDecimal value, String currency) {
        String formatted = PillCurrencies.formatFiatPrice(value, currency);
        if (formatted != null) {
            return formatted;
        }
        return value.setScale(RateInstances.getScale(baseCurrency()), RoundingMode.HALF_UP).toPlainString()
                + " " + currency;
    }

    private void setData(String price, boolean animated) {
        stopLoading();
        if (animated) {
            animateSizeChange();
        }
        iconView.setImageResource(RateInstances.getBaseIcon(baseCurrency()));
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

        final String base = baseCurrency();
        final ItemOptions baseSwipeback = options.makeSwipeback()
                .add(R.drawable.ic_ab_back, LocaleController.getString(R.string.Back), options::dismiss)
                .addGap();
        for (final String currency : RateInstances.getBaseCurrencies()) {
            baseSwipeback.addChecked(currency.equals(base), RateInstances.getBaseLabel(currency), () -> {
                options.dismiss();
                if (!currency.equals(base)) {
                    setBaseSelection(currency);
                }
            });
        }

        final String selection = getTargetSelection();
        final ItemOptions targetSwipeback = options.makeSwipeback()
                .add(R.drawable.ic_ab_back, LocaleController.getString(R.string.Back), options::dismiss)
                .addGap();
        for (final String currency : getTargetCurrencies()) {
            targetSwipeback.addChecked(currency.equalsIgnoreCase(selection),
                    PillCurrencies.getTargetCurrencyLabel(currency), () -> {
                        options.dismiss();
                        if (!currency.equalsIgnoreCase(selection)) {
                            setTargetSelection(currency);
                        }
                    });
        }

        ActionBarMenuSubItem baseItem = new ActionBarMenuSubItem(options.getContext(), true, false, resourcesProvider);
        baseItem.setTextAndIcon(LocaleController.getString(R.string.PillStackRateFrom), R.drawable.msg_language);
        baseItem.setSubtext(RateInstances.getBaseLabel(base));
        baseItem.setItemHeight(56);
        baseItem.setOnClickListener((View v) -> options.openSwipeback(baseSwipeback));

        ActionBarMenuSubItem targetItem = new ActionBarMenuSubItem(options.getContext(), false, false, resourcesProvider);
        targetItem.setTextAndIcon(LocaleController.getString(R.string.PillStackRateTo), R.drawable.msg_language);
        targetItem.setSubtext(PillCurrencies.getTargetCurrencySubtext(selection));
        targetItem.setItemHeight(56);
        targetItem.setOnClickListener((View v) -> options.openSwipeback(targetSwipeback));

        options.addView(baseItem)
                .addView(targetItem)
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
        final String base = baseCurrency();
        layout.setBackground("TON".equals(base)
                ? new ColoredBackground()
                : new ColoredBackground(RateInstances.getBaseColorTop(base), RateInstances.getBaseColorBottom(base)));
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
