package app.exteraless.pillstack.pills;

import android.annotation.SuppressLint;
import android.content.Context;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.IconBackgroundColors;

import app.exteraless.pillstack.PillCurrencies;
import app.exteraless.pillstack.PillStackConfig;
import app.exteraless.pillstack.PillType;

/** Курс евро к выбранной валюте. EUR в списке целей смысла не имеет. */
@SuppressLint("ViewConstructor")
public class EurPill extends RatePill {

    private static final RateCache CACHE = new RateCache();

    public EurPill(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider, CACHE, "EUR", 2, R.drawable.pillstack_eur,
                new ColoredBackground(IconBackgroundColors.BLUE.top,
                        IconBackgroundColors.BLUE.bottom));
    }

    @Override
    public int getPillId() {
        return PillType.EUR.id;
    }

    @Override
    public String[] getTargetCurrencies() {
        return PillCurrencies.getTargetCurrencies("EUR");
    }

    @Override
    public String getTargetSelection() {
        String selection = PillStackConfig.eurTargetCurrency.String();
        return "EUR".equalsIgnoreCase(selection) ? PillCurrencies.AUTO : selection;
    }

    @Override
    public void setTargetSelection(String currency) {
        PillStackConfig.eurTargetCurrency.setConfigString(currency);
    }
}
