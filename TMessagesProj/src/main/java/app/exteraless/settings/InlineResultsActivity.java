package app.exteraless.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.IntConsumer;

import app.exteraless.chats.ChatsConfig;
import app.exteraless.math.InlineOptions;
import app.exteraless.math.InlineResults;
import app.exteraless.math.MathExpression;
import app.exteraless.pillstack.ExchangeRates;
import tw.nekomimi.nekogram.config.ConfigItem;

/** Settings and a live cheat sheet for the results shown after "=" in the message field. */
public class InlineResultsActivity extends BaseFragment {

    private static final int ID_MASTER = 1;
    private static final int ID_CALCULATOR = 2;
    private static final int ID_CURRENCIES = 3;
    private static final int ID_UNITS = 4;
    private static final int ID_TIME_ZONES = 5;
    private static final int ID_GROUPING = 6;
    private static final int ID_ANGLE = 7;
    private static final int ID_TARGET_CURRENCY = 8;
    private static final int ID_MEASURE = 9;
    private static final int ID_BOT = 10;
    private static final int ID_EXAMPLE_BASE = 100;

    private static final String[][] EXAMPLES = {
            {"(120 + 80) / 4 =", "√144 + 2^3 =", "max(12, 40, 7) =", "sin(30) =", "hex(255) =", "dec(ff, 16) =", "0xff + 1 ="},
            {"2300 - 15% =", "12% of 1200 =", "300k / 12 ="},
            {"100€ =", "500 stars =", "100 dollars to euros =", "5k ₽ to $ ="},
            {"72°F =", "10 miles =", "5 kg to lb =", "100 km/h to mph =", "90 min =", "2 h + 45 min ="},
            {"6pm est =", "12:00 in tokyo =", "now in new york ="},
    };
    private static final String[][] EXAMPLES_CYRILLIC = {
            {"(120 + 80) / 4 =", "√144 + 2^3 =", "max(12, 40, 7) =", "sin(30) =", "hex(255) =", "dec(ff, 16) =", "0xff + 1 ="},
            {"2300 - 15% =", "12% от 1200 =", "300к / 12 ="},
            {"100€ =", "500 звёзд =", "100 долларов в евро =", "5к ₽ в $ ="},
            {"72°F =", "10 миль =", "5 кг в фунтах =", "100 км/ч в mph =", "90 мин =", "2 ч + 45 мин ="},
            {"18:00 utc =", "12:00 в токио =", "сейчас в нью-йорке ="},
    };
    private static final int[] GROUP_TITLES = {
            R.string.OEInlineDocArithmetic,
            R.string.OEInlineDocPercent,
            R.string.OEInlineCurrencies,
            R.string.OEInlineUnits,
            R.string.OEInlineTimeZones,
    };

    private UniversalRecyclerView listView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.OEInlineTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, (item, view, position, x, y) -> false);
        listView.setSections();
        listView.adapter.setApplyBackground(false);
        listView.setClipToPadding(false);
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(listView);
        fragmentView = contentView;
        return fragmentView;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        if (listView != null) {
            listView.setPadding(0, 0, 0, bottom);
        }
    }

    private void update() {
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private static UItem check(int id, int title, ConfigItem item) {
        return UItem.asCheck(id, getString(title)).setChecked(item.Bool());
    }

    private String angleValue() {
        return getString(ChatsConfig.inlineMathDegrees.Bool() ? R.string.OEInlineAngleDegrees : R.string.OEInlineAngleRadians);
    }

    private String measureValue() {
        switch (ChatsConfig.inlineMathMeasureSystem.Int()) {
            case InlineResults.MEASURE_METRIC:
                return getString(R.string.OEInlineMeasureMetric);
            case InlineResults.MEASURE_IMPERIAL:
                return getString(R.string.OEInlineMeasureImperial);
            default:
                return getString(R.string.OEInlineAuto);
        }
    }

    private String currencyValue() {
        String selected = ChatsConfig.inlineMathTargetCurrency.String();
        return selected == null || selected.isEmpty() ? getString(R.string.OEInlineAuto) : selected;
    }

    private String[][] examples() {
        Locale locale = org.telegram.messenger.LocaleController.getInstance().getCurrentLocale();
        String language = locale == null ? "" : locale.getLanguage();
        return language.equals("ru") || language.equals("uk") || language.equals("be") ? EXAMPLES_CYRILLIC : EXAMPLES;
    }

    private char decimalSeparator() {
        Locale locale = org.telegram.messenger.LocaleController.getInstance().getCurrentLocale();
        return DecimalFormatSymbols.getInstance(locale == null ? Locale.US : locale).getDecimalSeparator();
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        ChatsConfig.ensureLoaded();
        items.add(UItem.asRippleCheck(ID_MASTER, getString(R.string.OEInlineShow)).setChecked(ChatsConfig.inlineMathResult.Bool()));
        items.add(UItem.asShadow(getString(R.string.OEChatsInlineMathInfo)));

        if (ChatsConfig.inlineMathResult.Bool()) {
            items.add(UItem.asHeader(getString(R.string.OEInlineFeatures)));
            items.add(check(ID_CALCULATOR, R.string.OEInlineCalculator, ChatsConfig.inlineMathCalculator));
            items.add(check(ID_CURRENCIES, R.string.OEInlineCurrencies, ChatsConfig.inlineMathCurrency));
            items.add(check(ID_UNITS, R.string.OEInlineUnits, ChatsConfig.inlineMathUnits));
            items.add(check(ID_TIME_ZONES, R.string.OEInlineTimeZones, ChatsConfig.inlineMathTimeZones));
            items.add(UItem.asShadow(null));

            items.add(UItem.asHeader(getString(R.string.Settings)));
            items.add(check(ID_GROUPING, R.string.OEInlineGrouping, ChatsConfig.inlineMathGrouping));
            items.add(UItem.asButton(ID_ANGLE, getString(R.string.OEInlineAngle), angleValue()));
            if (ChatsConfig.inlineMathUnits.Bool()) {
                items.add(UItem.asButton(ID_MEASURE, getString(R.string.OEInlineMeasure), measureValue()));
            }
            if (ChatsConfig.inlineMathCurrency.Bool()) {
                items.add(UItem.asButton(ID_TARGET_CURRENCY, getString(R.string.OEInlineTargetCurrency), currencyValue()));
                items.add(check(ID_BOT, R.string.OEChatsInlineMathCurrency, ChatsConfig.inlineMathCalcmula));
                items.add(UItem.asShadow(getString(R.string.OEChatsInlineMathCurrencyInfo)));
            } else {
                items.add(UItem.asShadow(null));
            }
        }

        String[][] examples = examples();
        InlineOptions options = InlineResults.options(decimalSeparator(), this::update, true);
        for (int group = 0; group < examples.length; group++) {
            items.add(UItem.asHeader(getString(GROUP_TITLES[group])));
            for (int i = 0; i < examples[group].length; i++) {
                String example = examples[group][i];
                MathExpression.Suggestion result = MathExpression.suggestionAt(example, example.length(), options);
                items.add(UItem.asButton(ID_EXAMPLE_BASE + group * 10 + i, example, result == null ? "" : result.value));
            }
            items.add(UItem.asShadow(group == examples.length - 1 ? getString(R.string.OEInlineTapToCopy) : null));
        }
    }

    private void toggle(ConfigItem item) {
        item.setConfigBool(!item.Bool());
        update();
    }

    private void pick(CharSequence title, CharSequence[] options, IntConsumer onPick) {
        if (getParentActivity() == null) {
            return;
        }
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(title)
                .setItems(options, (dialog, which) -> {
                    onPick.accept(which);
                    update();
                })
                .create());
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_MASTER:
                toggle(ChatsConfig.inlineMathResult);
                return;
            case ID_CALCULATOR:
                toggle(ChatsConfig.inlineMathCalculator);
                return;
            case ID_CURRENCIES:
                toggle(ChatsConfig.inlineMathCurrency);
                return;
            case ID_UNITS:
                toggle(ChatsConfig.inlineMathUnits);
                return;
            case ID_TIME_ZONES:
                toggle(ChatsConfig.inlineMathTimeZones);
                return;
            case ID_GROUPING:
                toggle(ChatsConfig.inlineMathGrouping);
                return;
            case ID_BOT:
                toggle(ChatsConfig.inlineMathCalcmula);
                return;
            case ID_ANGLE:
                pick(getString(R.string.OEInlineAngle), new CharSequence[]{getString(R.string.OEInlineAngleDegrees), getString(R.string.OEInlineAngleRadians)},
                        which -> ChatsConfig.inlineMathDegrees.setConfigBool(which == 0));
                return;
            case ID_MEASURE:
                pick(getString(R.string.OEInlineMeasure), new CharSequence[]{getString(R.string.OEInlineAuto), getString(R.string.OEInlineMeasureMetric), getString(R.string.OEInlineMeasureImperial)},
                        which -> ChatsConfig.inlineMathMeasureSystem.setConfigInt(which));
                return;
            case ID_TARGET_CURRENCY:
                pickCurrency();
                return;
            default:
                break;
        }
        if (item.id >= ID_EXAMPLE_BASE && item.text != null) {
            AndroidUtilities.addToClipboard(item.text);
            BulletinFactory.of(this).createCopyBulletin(getString(R.string.TextCopied)).show();
        }
    }

    private void pickCurrency() {
        ArrayList<String> codes = new ArrayList<>(Arrays.asList(ExchangeRates.MAIN_CURRENCIES));
        codes.remove("TON");
        codes.add("GRAM");
        CharSequence[] options = new CharSequence[codes.size() + 1];
        options[0] = getString(R.string.OEInlineAuto);
        for (int i = 0; i < codes.size(); i++) {
            options[i + 1] = codes.get(i);
        }
        pick(getString(R.string.OEInlineTargetCurrency), options,
                which -> ChatsConfig.inlineMathTargetCurrency.setConfigString(which == 0 ? "" : codes.get(which - 1)));
    }
}
