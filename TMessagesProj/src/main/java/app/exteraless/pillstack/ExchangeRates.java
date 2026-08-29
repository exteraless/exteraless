package app.exteraless.pillstack;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import org.json.JSONObject;
import org.telegram.PhoneFormat.CallingCodeInfo;
import org.telegram.PhoneFormat.PhoneFormat;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import tw.nekomimi.nekogram.utils.HttpClient;

/**
 * Курсы валют с Coinbase. Внутри всё хранится как «сколько USD стоит одна единица валюты»,
 * поэтому любую пару можно получить делением.
 */
public class ExchangeRates {

    private static final String URL = "https://api.coinbase.com/v2/exchange-rates?currency=USD";
    private static final long CACHE_TTL = 5 * 60 * 1000L;

    public static final String[] MAIN_CURRENCIES = {
            "USD", "EUR", "RUB", "GBP", "KZT", "TRY", "UAH", "PLN", "AED", "CNY",
            "JPY", "BYN", "ILS", "CZK", "INR", "TON", "BTC", "ETH", "SOL"
    };

    /** Криптовалюты и «якорные» фиаты — используются пилюлями курсов как базовая валюта. */
    public static final String[] CRYPTO_CURRENCIES = {"BTC", "ETH", "SOL", "TON", "USD", "EUR"};

    /**
     * Смена целевой валюты в настройках должна приводить к пересчёту, а не к отдаче
     * старого кэша: подписываемся один раз на события Pill Stack и сбрасываем TTL.
     */
    private static final PillStackEvents.Listener settingsListener = new PillStackEvents.Listener() {
        @Override
        public void onPillStackSettingsChanged(int[] pillIds) {
            if (PillStackEvents.shouldUpdatePill(pillIds,
                    PillType.GRAM.id, PillType.BTC.id, PillType.USD.id,
                    PillType.ETH.id, PillType.EUR.id, PillType.GOLD.id)) {
                clearCache();
            }
        }
    };

    static {
        PillStackEvents.addListener(settingsListener);
    }

    public static class State {
        private final Map<String, BigDecimal> usdRates;

        public State(Map<String, BigDecimal> usdRates) {
            this.usdRates = usdRates;
        }

        public BigDecimal getUsdRate(String code) {
            return code == null ? null : usdRates.get(PillCurrencies.normalize(code));
        }

        /** Сколько единиц {@code target} стоит одна единица {@code base}. */
        public BigDecimal getRate(String base, String target) {
            BigDecimal baseRate = getUsdRate(base);
            BigDecimal targetRate = getUsdRate(target);
            if (baseRate == null || targetRate == null || targetRate.signum() == 0) {
                return null;
            }
            return baseRate.divide(targetRate, 12, RoundingMode.HALF_UP);
        }

        public Map<String, BigDecimal> usdRates() {
            return usdRates;
        }
    }

    private static final Object sync = new Object();
    private static final ArrayList<Utilities.Callback<State>> pendingCallbacks = new ArrayList<>();
    private static boolean requestInFlight;
    private static State cacheValue;
    private static long cacheTimestamp;

    public static void clearCache() {
        cacheTimestamp = 0;
    }

    private static boolean isStale() {
        return getCached() == null || cacheTimestamp == 0 || System.currentTimeMillis() - cacheTimestamp >= CACHE_TTL;
    }

    public static State getCached() {
        if (cacheValue == null) {
            try {
                String raw = PillStackConfig.ratesCache.String();
                if (!TextUtils.isEmpty(raw)) {
                    cacheValue = deserialize(raw);
                    cacheTimestamp = PillStackConfig.ratesCacheTime.Long();
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return cacheValue;
    }

    public static void fetch(Utilities.Callback<State> callback) {
        if (callback == null) {
            return;
        }
        final State cached = getCached();
        if (cached != null && !isStale()) {
            AndroidUtilities.runOnUIThread(() -> callback.run(cached));
            return;
        }
        boolean startRequest;
        synchronized (sync) {
            pendingCallbacks.add(callback);
            startRequest = !requestInFlight;
            requestInFlight = true;
        }
        if (!startRequest) {
            return;
        }
        try {
            HttpClient.INSTANCE.getInstance()
                    .newCall(new Request.Builder().url(URL).build())
                    .enqueue(new Callback() {
                        @Override
                        public void onFailure(@NonNull Call call, @NonNull IOException e) {
                            FileLog.e(e);
                            complete(getCached());
                        }

                        @Override
                        public void onResponse(@NonNull Call call, @NonNull Response response) {
                            try (ResponseBody body = response.body()) {
                                if (!response.isSuccessful() || body == null) {
                                    complete(getCached());
                                    return;
                                }
                                State state = parse(body.string());
                                if (state != null) {
                                    cacheValue = state;
                                    cacheTimestamp = System.currentTimeMillis();
                                    saveCache(state);
                                }
                                complete(state != null ? state : getCached());
                            } catch (Exception e) {
                                FileLog.e(e);
                                complete(getCached());
                            }
                        }
                    });
        } catch (Exception e) {
            FileLog.e(e);
            complete(getCached());
        }
    }

    private static void complete(State state) {
        final ArrayList<Utilities.Callback<State>> callbacks;
        synchronized (sync) {
            requestInFlight = false;
            callbacks = new ArrayList<>(pendingCallbacks);
            pendingCallbacks.clear();
        }
        AndroidUtilities.runOnUIThread(() -> {
            for (Utilities.Callback<State> callback : callbacks) {
                callback.run(state);
            }
        });
    }

    private static State parse(String json) {
        try {
            JSONObject rates = new JSONObject(json).getJSONObject("data").getJSONObject("rates");
            HashMap<String, BigDecimal> result = new HashMap<>();
            for (String code : MAIN_CURRENCIES) {
                BigDecimal usdRate = parseUsdRate(code, rates);
                if (usdRate != null) {
                    result.put(code, usdRate);
                }
            }
            return result.isEmpty() ? null : new State(result);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /** В ответе Coinbase «сколько единиц валюты за 1 USD», нам нужно обратное. */
    private static BigDecimal parseUsdRate(String code, JSONObject rates) {
        if ("USD".equals(code)) {
            return BigDecimal.ONE;
        }
        String value = rates.optString(code, null);
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        try {
            BigDecimal decimal = new BigDecimal(value);
            if (decimal.signum() == 0) {
                return null;
            }
            return BigDecimal.ONE.divide(decimal, 16, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveCache(State state) {
        try {
            PillStackConfig.ratesCache.setConfigString(serialize(state));
            PillStackConfig.ratesCacheTime.setConfigLong(System.currentTimeMillis());
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static String serialize(State state) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, BigDecimal> entry : state.usdRates().entrySet()) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue().toPlainString());
        }
        return builder.toString();
    }

    private static State deserialize(String raw) {
        HashMap<String, BigDecimal> result = new HashMap<>();
        for (String part : raw.split(",")) {
            int index = part.indexOf('=');
            if (index <= 0) {
                continue;
            }
            try {
                result.put(part.substring(0, index), new BigDecimal(part.substring(index + 1)));
            } catch (Exception ignore) {
            }
        }
        return result.isEmpty() ? null : new State(result);
    }

    public static boolean isSupportedCurrency(String code) {
        String normalized = PillCurrencies.normalize(code);
        for (String candidate : MAIN_CURRENCIES) {
            if (candidate.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** AUTO → валюта страны номера телефона аккаунта, если она нам известна, иначе USD. */
    public static String resolveTargetCurrency(String selection) {
        String normalized = PillCurrencies.normalize(selection);
        if (!PillCurrencies.AUTO.equals(normalized)) {
            return TextUtils.isEmpty(normalized) || !isSupportedCurrency(normalized) ? "USD" : normalized;
        }
        String code = currencyOfCountry(phoneCountry());
        return code == null ? "USD" : code;
    }

    private static String phoneCountry() {
        try {
            TLRPC.User user = UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser();
            if (user == null || TextUtils.isEmpty(user.phone)) {
                return null;
            }
            String phone = PhoneFormat.stripExceptNumbers(user.phone);
            CallingCodeInfo info = PhoneFormat.getInstance().findCallingCodeInfo(phone);
            if (info == null) {
                return null;
            }
            if ("7".equals(info.callingCode)) {
                return phone.startsWith("76") || phone.startsWith("77") ? "KZ" : "RU";
            }
            return info.countries.isEmpty() ? null : info.countries.get(0).toUpperCase(Locale.US);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String currencyOfCountry(String country) {
        if (TextUtils.isEmpty(country) || country.length() != 2) {
            return null;
        }
        try {
            Currency currency = Currency.getInstance(new Locale("", country.toUpperCase(Locale.US)));
            if (currency == null) {
                return null;
            }
            String code = PillCurrencies.normalize(currency.getCurrencyCode());
            return isSupportedCurrency(code) ? code : null;
        } catch (Exception ignore) {
            return null;
        }
    }
}
