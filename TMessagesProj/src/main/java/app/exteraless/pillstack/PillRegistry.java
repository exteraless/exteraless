package app.exteraless.pillstack;

import android.content.Context;

import androidx.annotation.Keep;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.IconBackgroundColors;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.exteraless.pillstack.pills.BasePill;
import app.exteraless.pillstack.pills.BtcPill;
import app.exteraless.pillstack.pills.CachePill;
import app.exteraless.pillstack.pills.CpuPill;
import app.exteraless.pillstack.pills.DcPingPill;
import app.exteraless.pillstack.pills.EthPill;
import app.exteraless.pillstack.pills.EurPill;
import app.exteraless.pillstack.pills.GhostPill;
import app.exteraless.pillstack.pills.GoldPill;
import app.exteraless.pillstack.pills.GramPill;
import app.exteraless.pillstack.pills.NetSpeedPill;
import app.exteraless.pillstack.pills.ProxyPill;
import app.exteraless.pillstack.pills.RamPill;
import app.exteraless.pillstack.pills.UsdPill;
import app.exteraless.pillstack.pills.WeatherPill;

/**
 * Реестр доступных пилюль: имя, иконка, цвета и фабрика вью.
 *
 * Реестр открытый: {@link #register(PillInfo)} / {@link #unregister(int)} можно звать в рантайме,
 * раскладка при этом чинится ({@link PillStackConfig#sanitizePills()}) и полоса пересобирается.
 * Пакетную регистрацию оборачивать в {@link #beginTransaction()} / {@link #endTransaction()},
 * чтобы не дёргать перестроение на каждый вызов.
 */
public class PillRegistry {

    public interface PillCreator {
        BasePill create(Context context, Theme.ResourcesProvider resourcesProvider);
    }

    public static class PillInfo {
        public final int id;
        public final CharSequence name;
        public final int iconRes;
        public final int iconColorTop;
        public final int iconColorBottom;
        public final PillCreator creator;

        public PillInfo(int id, CharSequence name, int iconRes, int iconColorTop, int iconColorBottom, PillCreator creator) {
            this.id = id;
            this.name = name;
            this.iconRes = iconRes;
            this.iconColorTop = iconColorTop;
            this.iconColorBottom = iconColorBottom;
            this.creator = creator;
        }

        public int id() {
            return id;
        }

        public CharSequence name() {
            return name;
        }

        public int iconRes() {
            return iconRes;
        }

        public int iconColorTop() {
            return iconColorTop;
        }

        public int iconColorBottom() {
            return iconColorBottom;
        }

        public PillCreator creator() {
            return creator;
        }
    }

    private static final Map<Integer, PillInfo> registry = new LinkedHashMap<>();
    private static volatile boolean batchRegistration;

    static {
        beginTransaction();
        registerDefaultPills();
        endTransaction();
    }

    /** Цвета берутся из общей палитры {@link IconBackgroundColors}, а не подбираются на глаз. */
    private static void registerDefaultPills() {
        register(new PillInfo(PillType.WEATHER.id, LocaleController.getString(R.string.PillStackWeather),
                R.drawable.weather_cloudy,
                IconBackgroundColors.BLUE_ALT.top, IconBackgroundColors.BLUE_ALT.bottom,
                WeatherPill::new));
        register(new PillInfo(PillType.GRAM.id, "GRAM",
                R.drawable.settings_gram_24,
                IconBackgroundColors.BLUE_LIGHT.top, IconBackgroundColors.BLUE_LIGHT.bottom,
                GramPill::new));
        register(new PillInfo(PillType.BTC.id, "BTC",
                R.drawable.pillstack_btc_settings,
                IconBackgroundColors.ORANGE_BRIGHT.top, IconBackgroundColors.ORANGE_BRIGHT.bottom,
                BtcPill::new));
        register(new PillInfo(PillType.USD.id, "USD",
                R.drawable.pillstack_usd_settings,
                IconBackgroundColors.GREEN_DEEP.top, IconBackgroundColors.GREEN_DEEP.bottom,
                UsdPill::new));
        register(new PillInfo(PillType.CACHE.id, LocaleController.getString(R.string.StorageUsage),
                R.drawable.msg_filled_storageusage,
                IconBackgroundColors.BLUE_DEEP.top, IconBackgroundColors.BLUE_DEEP.bottom,
                CachePill::new));
        register(new PillInfo(PillType.PROXY.id, LocaleController.getString(R.string.PillStackProxy),
                R.drawable.drawer_proxy_on,
                IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                ProxyPill::new));
        register(new PillInfo(PillType.GHOST.id, LocaleController.getString(R.string.GhostMode),
                R.drawable.ayu_ghost,
                IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom,
                GhostPill::new));
        register(new PillInfo(PillType.RAM.id, LocaleController.getString(R.string.PillStackRam),
                R.drawable.pillstack_ram,
                IconBackgroundColors.CYAN.top, IconBackgroundColors.CYAN.bottom,
                RamPill::new));
        register(new PillInfo(PillType.CPU.id, LocaleController.getString(R.string.PillStackCpu),
                R.drawable.pillstack_cpu,
                IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                CpuPill::new));
        register(new PillInfo(PillType.NET_SPEED.id, LocaleController.getString(R.string.PillStackNetSpeed),
                R.drawable.pillstack_netspeed,
                IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom,
                NetSpeedPill::new));
        register(new PillInfo(PillType.DC_PING.id, LocaleController.getString(R.string.PillStackDcPing),
                R.drawable.pillstack_ping,
                IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom,
                DcPingPill::new));
        register(new PillInfo(PillType.GOLD.id, LocaleController.getString(R.string.PillStackGold),
                R.drawable.pillstack_gold,
                IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom,
                GoldPill::new));
        register(new PillInfo(PillType.ETH.id, "ETH",
                R.drawable.pillstack_eth,
                IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom,
                EthPill::new));
        register(new PillInfo(PillType.EUR.id, "EUR",
                R.drawable.pillstack_eur,
                IconBackgroundColors.BLUE_DEEP.top, IconBackgroundColors.BLUE_DEEP.bottom,
                EurPill::new));
    }

    // ---- Пакетная регистрация ----

    /** Отключает перестроение полосы до {@link #endTransaction()}. */
    @Keep
    public static void beginTransaction() {
        batchRegistration = true;
    }

    /** Включает перестроение обратно и один раз чинит + оповещает раскладку. */
    @Keep
    public static void endTransaction() {
        batchRegistration = false;
        if (PillStackConfig.isConfigLoaded()) {
            PillStackConfig.sanitizePills();
            PillStackEvents.notifyLayoutChanged();
        }
    }

    // ---- Реестр ----

    public static void register(PillInfo info) {
        if (info == null) {
            return;
        }
        synchronized (registry) {
            registry.put(info.id, info);
        }
        invalidate();
    }

    /** Убирает пилюлю из реестра; из раскладки её вычистит sanitizePills. */
    @Keep
    public static void unregister(int id) {
        boolean removed;
        synchronized (registry) {
            removed = registry.remove(id) != null;
        }
        if (removed) {
            invalidate();
        }
    }

    /** Переносит пилюлю в активные, если она зарегистрирована и ещё не активна. */
    @Keep
    public static void activatePill(int id) {
        if (!isRegistered(id) || PillStackConfig.getActivePills().contains(id)) {
            return;
        }
        PillStackConfig.setPillActive(id, true);
        PillStackEvents.notifyLayoutChanged();
    }

    private static void invalidate() {
        if (batchRegistration) {
            return;
        }
        PillStackConfig.sanitizePills();
        PillStackEvents.notifyLayoutChanged();
    }

    public static PillInfo getPillInfo(int id) {
        synchronized (registry) {
            return registry.get(id);
        }
    }

    public static Collection<PillInfo> getRegisteredPills() {
        synchronized (registry) {
            return new ArrayList<>(registry.values());
        }
    }

    public static List<Integer> getRegisteredIds() {
        synchronized (registry) {
            return new ArrayList<>(registry.keySet());
        }
    }

    public static boolean isRegistered(int id) {
        synchronized (registry) {
            return registry.containsKey(id);
        }
    }

    public static BasePill createPill(int id, Context context, Theme.ResourcesProvider resourcesProvider) {
        PillInfo info = getPillInfo(id);
        if (info == null) {
            return null;
        }
        try {
            return info.creator.create(context, resourcesProvider);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }
}
