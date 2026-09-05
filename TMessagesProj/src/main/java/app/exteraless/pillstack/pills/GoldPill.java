package app.exteraless.pillstack.pills;

import android.annotation.SuppressLint;
import android.content.Context;

import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.IconBackgroundColors;

import java.math.BigDecimal;

import app.exteraless.pillstack.ExchangeRates;
import app.exteraless.pillstack.GoldPrice;
import app.exteraless.pillstack.PillStackConfig;
import app.exteraless.pillstack.PillType;

/**
 * Цена золота за тройскую унцию. Спот приходит в USD с gold-api.com,
 * в целевую валюту конвертируем через обычные курсы {@link ExchangeRates}.
 */
@SuppressLint("ViewConstructor")
public class GoldPill extends RatePill {

    private static final RateCache CACHE = new RateCache();

    public GoldPill(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider, CACHE, "XAU", 2, R.drawable.pillstack_gold,
                new ColoredBackground(IconBackgroundColors.ORANGE.top,
                        IconBackgroundColors.ORANGE.bottom));
    }

    @Override
    public int getPillId() {
        return PillType.GOLD.id;
    }

    @Override
    public String getTargetSelection() {
        return PillStackConfig.goldTargetCurrency.String();
    }

    @Override
    public void setTargetSelection(String currency) {
        PillStackConfig.goldTargetCurrency.setConfigString(currency);
    }

    @Override
    protected void fetchRate(String target, boolean force, Utilities.Callback<BigDecimal> callback) {
        if (force) {
            GoldPrice.clearCache();
            ExchangeRates.clearCache();
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
}
