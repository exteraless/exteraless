package app.exteraless.math;

import android.os.SystemClock;
import android.text.TextUtils;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import app.exteraless.chats.ChatsConfig;
import app.exteraless.pillstack.ExchangeRates;
import app.exteraless.pillstack.PillCurrencies;

/** Turns the settings into {@link InlineOptions} and feeds them cached exchange rates. */
public final class InlineResults {

    public static final int MEASURE_AUTO = 0;
    public static final int MEASURE_METRIC = 1;
    public static final int MEASURE_IMPERIAL = 2;

    private static final Set<String> IMPERIAL_REGIONS = new HashSet<>(Arrays.asList("US", "LR", "MM"));
    private static final Set<String> CYRILLIC_LANGUAGES = new HashSet<>(Arrays.asList("ru", "uk", "be"));
    private static final long RETRY_MS = 30_000;

    private static long requestedAt;
    private static boolean requesting;

    private InlineResults() {
    }

    private static String rateCode(String code) {
        return "GRAM".equals(code) ? "TON" : code;
    }

    private static final class CachedRates implements InlineOptions.Rates {
        private final Runnable onUpdated;

        CachedRates(Runnable onUpdated) {
            this.onUpdated = onUpdated;
        }

        @Override
        public boolean supports(String code) {
            return "XTR".equals(code) || ExchangeRates.isSupportedCurrency(rateCode(code));
        }

        @Override
        public Double rate(String from, String to) {
            Double fromUsd = usdPrice(from);
            Double toUsd = usdPrice(to);
            return fromUsd == null || toUsd == null || toUsd == 0 ? null : fromUsd / toUsd;
        }

        private Double usdPrice(String code) {
            if ("XTR".equals(code)) {
                float rate = MessagesController.getInstance(UserConfig.selectedAccount).starsUsdSellRate1000;
                return rate > 0 ? rate / 100000d : null;
            }
            requestRates(onUpdated);
            ExchangeRates.State state = ExchangeRates.getCached();
            BigDecimal price = state == null ? null : state.getUsdRate(rateCode(code));
            return price == null ? null : price.doubleValue();
        }
    }

    private static void requestRates(Runnable onUpdated) {
        long now = SystemClock.elapsedRealtime();
        if (requesting || (requestedAt != 0 && now - requestedAt < RETRY_MS)) {
            return;
        }
        requesting = true;
        requestedAt = now;
        ExchangeRates.fetch(state -> {
            requesting = false;
            if (onUpdated != null) {
                onUpdated.run();
            }
        });
    }

    /** True until the first rates have arrived, a bot lookup would race them. */
    static boolean ratesPending() {
        return requesting && ExchangeRates.getCached() == null;
    }

    public static boolean isImperial() {
        int system = ChatsConfig.inlineMathMeasureSystem.Int();
        if (system == MEASURE_AUTO) {
            Locale locale = LocaleController.getInstance().getSystemDefaultLocale();
            return locale != null && IMPERIAL_REGIONS.contains(locale.getCountry());
        }
        return system == MEASURE_IMPERIAL;
    }

    private static String localCurrency(InlineOptions.Rates rates) {
        String selected = ChatsConfig.inlineMathTargetCurrency.String();
        if (!TextUtils.isEmpty(selected) && rates.supports(selected)) {
            return selected;
        }
        return ExchangeRates.resolveTargetCurrency(PillCurrencies.AUTO);
    }

    private static boolean cyrillicLabels() {
        Locale locale = LocaleController.getInstance().getCurrentLocale();
        return locale != null && CYRILLIC_LANGUAGES.contains(locale.getLanguage());
    }

    public static InlineOptions options(char decimalSeparator, Runnable onRatesUpdated) {
        return options(decimalSeparator, onRatesUpdated, false);
    }

    /** {@code everything} ignores the feature switches, the settings screen uses it for its examples. */
    public static InlineOptions options(char decimalSeparator, Runnable onRatesUpdated, boolean everything) {
        ChatsConfig.ensureLoaded();
        InlineOptions options = new InlineOptions();
        options.decimalSeparator = decimalSeparator;
        options.arithmetic = everything || ChatsConfig.inlineMathCalculator.Bool();
        options.units = everything || ChatsConfig.inlineMathUnits.Bool();
        options.degrees = ChatsConfig.inlineMathDegrees.Bool();
        options.grouping = ChatsConfig.inlineMathGrouping.Bool();
        options.imperial = isImperial();
        options.cyrillicLabels = cyrillicLabels();
        if (everything || ChatsConfig.inlineMathCurrency.Bool()) {
            options.rates = new CachedRates(onRatesUpdated);
            options.localCurrency = localCurrency(options.rates);
        }
        if (everything || ChatsConfig.inlineMathTimeZones.Bool()) {
            options.localZone = ZoneId.systemDefault();
        }
        return options;
    }
}
