package app.exteraless.pillstack.pills;

import android.annotation.SuppressLint;
import android.content.Context;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.IconBackgroundColors;

import app.exteraless.pillstack.PillStackConfig;
import app.exteraless.pillstack.PillType;

/** Курс Ethereum. */
@SuppressLint("ViewConstructor")
public class EthPill extends RatePill {

    private static final RateCache CACHE = new RateCache();

    public EthPill(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider, CACHE, "ETH", 2, R.drawable.pillstack_eth,
                new ColoredBackground(IconBackgroundColors.BLUE_DEEP.top,
                        IconBackgroundColors.BLUE_DEEP.bottom));
    }

    @Override
    public int getPillId() {
        return PillType.ETH.id;
    }

    @Override
    public String getTargetSelection() {
        return PillStackConfig.ethTargetCurrency.String();
    }

    @Override
    public void setTargetSelection(String currency) {
        PillStackConfig.ethTargetCurrency.setConfigString(currency);
    }
}
