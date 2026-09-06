package app.exteraless.pillstack;

import androidx.annotation.NonNull;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import tw.nekomimi.nekogram.utils.HttpClient;

/**
 * Спотовая цена золота с gold-api.com (бесплатный эндпоинт без ключа).
 * Храним одно число — цену тройской унции в USD — с кэшем и склейкой
 * параллельных запросов, как в {@link ExchangeRates}.
 */
public class GoldPrice {

    private static final String URL = "https://api.gold-api.com/price/XAU";
    private static final long CACHE_TTL = 5 * 60 * 1000L;

    private static final Object sync = new Object();
    private static final ArrayList<Utilities.Callback<BigDecimal>> pendingCallbacks = new ArrayList<>();
    private static boolean requestInFlight;
    private static BigDecimal cacheValue;
    private static long cacheTimestamp;

    public static void clearCache() {
        cacheTimestamp = 0;
    }

    public static BigDecimal getCached() {
        if (cacheValue == null) {
            try {
                String raw = PillStackConfig.goldPriceCache.String();
                if (raw != null && !raw.isEmpty()) {
                    cacheValue = new BigDecimal(raw);
                    cacheTimestamp = PillStackConfig.goldPriceCacheTime.Long();
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return cacheValue;
    }

    private static boolean isStale() {
        return getCached() == null || cacheTimestamp == 0 || System.currentTimeMillis() - cacheTimestamp >= CACHE_TTL;
    }

    public static void fetch(Utilities.Callback<BigDecimal> callback) {
        if (callback == null) {
            return;
        }
        final BigDecimal cached = getCached();
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
                                BigDecimal price = parse(body.string());
                                if (price != null) {
                                    cacheValue = price;
                                    cacheTimestamp = System.currentTimeMillis();
                                    saveCache(price);
                                }
                                complete(price != null ? price : getCached());
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

    private static void complete(BigDecimal price) {
        final ArrayList<Utilities.Callback<BigDecimal>> callbacks;
        synchronized (sync) {
            requestInFlight = false;
            callbacks = new ArrayList<>(pendingCallbacks);
            pendingCallbacks.clear();
        }
        AndroidUtilities.runOnUIThread(() -> {
            for (Utilities.Callback<BigDecimal> callback : callbacks) {
                callback.run(price);
            }
        });
    }

    private static BigDecimal parse(String json) {
        try {
            double price = new JSONObject(json).optDouble("price", 0);
            return price > 0 ? BigDecimal.valueOf(price) : null;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void saveCache(BigDecimal price) {
        try {
            PillStackConfig.goldPriceCache.setConfigString(price.toPlainString());
            PillStackConfig.goldPriceCacheTime.setConfigLong(System.currentTimeMillis());
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
