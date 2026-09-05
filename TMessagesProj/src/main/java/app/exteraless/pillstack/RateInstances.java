package app.exteraless.pillstack;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.IconBackgroundColors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import app.exteraless.pillstack.pills.RatePill;

public final class RateInstances {

    public static final int FIRST_ID = 100;
    public static final int MAX_COUNT = 12;

    public static final String[] BASE_CURRENCIES = {
            "USD", "EUR", "RUB", "GBP", "KZT", "TRY", "UAH", "PLN", "AED", "CNY",
            "JPY", "BYN", "ILS", "CZK", "INR", "TON", "BTC", "ETH", "SOL", "XAU"
    };

    public static final class Instance {
        public final int id;
        public String from;
        public String to;

        Instance(int id, String from, String to) {
            this.id = id;
            this.from = from;
            this.to = to;
        }
    }

    private static final Object SYNC = new Object();
    private static final LinkedHashMap<Integer, Instance> INSTANCES = new LinkedHashMap<>();
    private static boolean loaded;

    private RateInstances() {
    }

    public static void ensureLoaded() {
        synchronized (SYNC) {
            if (loaded) {
                return;
            }
            loaded = true;
            parse(PillStackConfig.rateInstancesRaw.String());
        }
        migrateLegacyPills();
    }

    private static void parse(String raw) {
        INSTANCES.clear();
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        for (String part : raw.split(";")) {
            String entry = part.trim();
            int colon = entry.indexOf(':');
            int arrow = entry.indexOf('>');
            if (colon <= 0 || arrow <= colon) {
                continue;
            }
            int id;
            try {
                id = Integer.parseInt(entry.substring(0, colon).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (id < FIRST_ID || INSTANCES.containsKey(id)) {
                continue;
            }
            String from = normalizeBase(entry.substring(colon + 1, arrow));
            String to = PillCurrencies.normalize(entry.substring(arrow + 1));
            if (from.isEmpty() || to.isEmpty()) {
                continue;
            }
            INSTANCES.put(id, new Instance(id, from, to));
        }
    }

    private static void persist() {
        StringBuilder builder = new StringBuilder();
        for (Instance instance : INSTANCES.values()) {
            if (builder.length() > 0) {
                builder.append(';');
            }
            builder.append(instance.id).append(':').append(instance.from).append('>').append(instance.to);
        }
        PillStackConfig.rateInstancesRaw.setConfigString(builder.toString());
    }

    public static String normalizeBase(String code) {
        String normalized = PillCurrencies.normalize(code);
        if ("GOLD".equals(normalized)) {
            return "XAU";
        }
        if ("GRAM".equals(normalized)) {
            return "TON";
        }
        return normalized;
    }

    public static List<Instance> getAll() {
        ensureLoaded();
        synchronized (SYNC) {
            return new ArrayList<>(INSTANCES.values());
        }
    }

    public static Instance get(int id) {
        ensureLoaded();
        synchronized (SYNC) {
            return INSTANCES.get(id);
        }
    }

    public static boolean isRateInstance(int id) {
        return id >= FIRST_ID;
    }

    public static boolean canAddMore() {
        return getAll().size() < MAX_COUNT;
    }

    public static Instance create(String from, String to) {
        ensureLoaded();
        final Instance created;
        synchronized (SYNC) {
            if (INSTANCES.size() >= MAX_COUNT) {
                return null;
            }
            int id = FIRST_ID;
            while (INSTANCES.containsKey(id)) {
                id++;
            }
            created = new Instance(id, normalizeBase(from), PillCurrencies.normalize(to));
            INSTANCES.put(id, created);
            persist();
        }
        PillRegistry.register(describe(created));
        return created;
    }

    public static void remove(int id) {
        ensureLoaded();
        synchronized (SYNC) {
            if (INSTANCES.remove(id) == null) {
                return;
            }
            persist();
        }
        PillStackConfig.getActivePills().remove(Integer.valueOf(id));
        PillStackConfig.getHiddenPills().remove(Integer.valueOf(id));
        PillRegistry.unregister(id);
        PillStackConfig.savePillsLayout();
    }

    public static void setPair(int id, String from, String to) {
        final Instance instance = get(id);
        if (instance == null) {
            return;
        }
        synchronized (SYNC) {
            instance.from = normalizeBase(from);
            instance.to = PillCurrencies.normalize(to);
            persist();
        }
        PillRegistry.register(describe(instance));
        PillStackEvents.notifySettingsChanged(id);
    }

    public static void registerAll() {
        PillRegistry.beginTransaction();
        try {
            ensureLoaded();
            for (Instance instance : getAll()) {
                PillRegistry.register(describe(instance));
            }
        } finally {
            PillRegistry.endTransaction();
        }
    }

    public static PillRegistry.PillInfo describe(Instance instance) {
        return new PillRegistry.PillInfo(instance.id, getLabel(instance),
                getBaseSettingsIcon(instance.from),
                getBaseColorTop(instance.from), getBaseColorBottom(instance.from),
                (context, resourcesProvider) -> new RatePill(context, resourcesProvider, instance.id));
    }

    public static CharSequence getLabel(Instance instance) {
        return getBaseLabel(instance.from) + " → " + PillCurrencies.getTargetCurrencyLabel(instance.to);
    }

    public static String getBaseLabel(String code) {
        if ("XAU".equals(code)) {
            return LocaleController.getString(R.string.PillStackGold);
        }
        return code;
    }

    public static int getBaseIcon(String code) {
        switch (code) {
            case "XAU": return R.drawable.pillstack_gold;
            case "BTC": return R.drawable.pillstack_btc;
            case "ETH": return R.drawable.pillstack_eth;
            case "TON": return R.drawable.mini_gram_16;
            case "EUR": return R.drawable.pillstack_eur;
            default: return R.drawable.pillstack_usd;
        }
    }

    public static int getBaseSettingsIcon(String code) {
        switch (code) {
            case "XAU": return R.drawable.pillstack_gold;
            case "BTC": return R.drawable.pillstack_btc_settings;
            case "ETH": return R.drawable.pillstack_eth;
            case "TON": return R.drawable.settings_gram_24;
            case "EUR": return R.drawable.pillstack_eur;
            default: return R.drawable.pillstack_usd_settings;
        }
    }

    public static int getBaseColorTop(String code) {
        return baseColors(code).top;
    }

    public static int getBaseColorBottom(String code) {
        return baseColors(code).bottom;
    }

    private static IconBackgroundColors baseColors(String code) {
        switch (code) {
            case "XAU": return IconBackgroundColors.ORANGE;
            case "BTC": return IconBackgroundColors.ORANGE_BRIGHT;
            case "ETH": return IconBackgroundColors.PURPLE;
            case "TON": return IconBackgroundColors.BLUE_LIGHT;
            case "EUR": return IconBackgroundColors.BLUE_DEEP;
            default: return IconBackgroundColors.GREEN_DEEP;
        }
    }

    public static int getScale(String code) {
        return "TON".equals(code) ? 3 : 2;
    }

    private static final int[] LEGACY_IDS = {
            PillType.GRAM.id, PillType.BTC.id, PillType.USD.id,
            PillType.GOLD.id, PillType.ETH.id, PillType.EUR.id
    };

    private static final String[] LEGACY_BASES = {"TON", "BTC", "USD", "XAU", "ETH", "EUR"};

    private static void migrateLegacyPills() {
        if (PillStackConfig.rateInstancesMigrated.Bool()) {
            return;
        }
        PillStackConfig.rateInstancesMigrated.setConfigBool(true);
        final List<Integer> active = PillStackConfig.getActivePills();
        final List<Integer> hidden = PillStackConfig.getHiddenPills();
        boolean changed = false;
        for (int a = 0; a < LEGACY_IDS.length; a++) {
            final int legacyId = LEGACY_IDS[a];
            final int activeIndex = active.indexOf(legacyId);
            final int hiddenIndex = hidden.indexOf(legacyId);
            if (activeIndex < 0 && hiddenIndex < 0) {
                continue;
            }
            final Instance instance = create(LEGACY_BASES[a], legacyTarget(legacyId));
            if (instance == null) {
                continue;
            }
            if (activeIndex >= 0) {
                active.set(activeIndex, instance.id);
            } else {
                hidden.set(hiddenIndex, instance.id);
            }
            changed = true;
        }
        if (changed) {
            PillStackConfig.savePillsLayout();
        }
    }

    private static String legacyTarget(int legacyId) {
        final String selection;
        if (legacyId == PillType.GRAM.id) {
            selection = PillStackConfig.gramTargetCurrency.String();
        } else if (legacyId == PillType.BTC.id) {
            selection = PillStackConfig.btcTargetCurrency.String();
        } else if (legacyId == PillType.USD.id) {
            selection = PillStackConfig.usdTargetCurrency.String();
        } else if (legacyId == PillType.GOLD.id) {
            selection = PillStackConfig.goldTargetCurrency.String();
        } else if (legacyId == PillType.ETH.id) {
            selection = PillStackConfig.ethTargetCurrency.String();
        } else if (legacyId == PillType.EUR.id) {
            selection = PillStackConfig.eurTargetCurrency.String();
        } else {
            selection = PillCurrencies.AUTO;
        }
        return selection == null || selection.isEmpty() ? PillCurrencies.AUTO : selection;
    }

    public static String[] getBaseCurrencies() {
        return Arrays.copyOf(BASE_CURRENCIES, BASE_CURRENCIES.length);
    }

    public static String defaultBase() {
        return "USD";
    }
}
