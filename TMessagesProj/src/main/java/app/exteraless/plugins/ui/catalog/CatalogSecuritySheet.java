package app.exteraless.plugins.ui.catalog;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.PorterDuff;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import app.exteraless.plugins.catalog.CatalogPlugin;

final class CatalogSecuritySheet extends BottomSheet {

    private final Theme.ResourcesProvider sheetResourcesProvider;

    CatalogSecuritySheet(Context context, Theme.ResourcesProvider resourcesProvider,
                         CatalogPlugin plugin, String storeName) {
        super(context, false, resourcesProvider);
        this.sheetResourcesProvider = resourcesProvider;
        setApplyBottomPadding(false);
        setApplyTopPadding(false);

        int accent = toneColor(CatalogUi.overallTone(plugin));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        FrameLayout iconFrame = new FrameLayout(context);
        iconFrame.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(72),
                Theme.multAlpha(accent, 0.12f)));
        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setImageResource(R.drawable.msg2_policy);
        icon.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
        iconFrame.addView(icon, LayoutHelper.createFrame(40, 40, Gravity.CENTER));
        content.addView(iconFrame, LayoutHelper.createLinear(72, 72,
                Gravity.CENTER_HORIZONTAL, 0, 22, 0, 0));

        CharSequence summary = CatalogUi.overallLabel(plugin);
        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        title.setGravity(Gravity.CENTER);
        title.setText(summary);
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 40, 14, 40, 0));

        TextView subtitle = new TextView(context);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setText(getString(R.string.PluginCatalogSecuritySheetTitle));
        content.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 21, 4, 21, 0));

        addCheckCard(content, plugin.securityCheck, false);
        addCheckCard(content, plugin.performanceCheck, true);

        if (!TextUtils.isEmpty(storeName)) {
            TextView checkedBy = new TextView(context);
            checkedBy.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            checkedBy.setTextColor(Theme.getColor(Theme.key_dialogTextGray2,
                    resourcesProvider));
            checkedBy.setGravity(Gravity.CENTER);
            checkedBy.setText(LocaleController.formatString(
                    R.string.PluginCatalogSecurityModel, storeName));
            content.addView(checkedBy, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT, 21, 12, 21, 0));
        }

        ButtonWithCounterView button = new ButtonWithCounterView(context, true, resourcesProvider);
        button.setText(getString(R.string.OK), false);
        button.setOnClickListener(v -> dismiss());
        content.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48,
                16, 20, 16, 16));

        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        setCustomView(scroll);
    }

    private void addCheckCard(LinearLayout content, CatalogPlugin.AiCheck check,
                              boolean performance) {
        if (check == null) return;
        Context context = getContext();
        int tone = CatalogUi.checkTone(check);
        int accent = toneColor(tone);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12),
                AndroidUtilities.dp(14), AndroidUtilities.dp(12));
        card.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12),
                Theme.multAlpha(accent, 0.07f)));
        content.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 16, 14, 16, 0));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT));

        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setImageResource(performance ? R.drawable.msg_speed : R.drawable.msg2_policy);
        icon.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
        header.addView(icon, LayoutHelper.createLinear(20, 20,
                Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        name.setTypeface(AndroidUtilities.bold());
        name.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, sheetResourcesProvider));
        name.setText(getString(performance ? R.string.PluginCatalogPerformance
                : R.string.PluginCatalogSecurity));
        header.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f,
                Gravity.CENTER_VERTICAL));

        if (check.score >= 0) {
            TextView score = new TextView(context);
            score.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            score.setTypeface(AndroidUtilities.bold());
            score.setTextColor(accent);
            score.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(3),
                    AndroidUtilities.dp(10), AndroidUtilities.dp(3));
            score.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(11),
                    Theme.multAlpha(accent, 0.12f)));
            score.setText(LocaleController.formatString(
                    R.string.PluginCatalogSecurityScore, check.score));
            header.addView(score, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT,
                    LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
        }

        TextView verdict = new TextView(context);
        verdict.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        verdict.setTypeface(AndroidUtilities.bold());
        verdict.setTextColor(accent);
        verdict.setText(CatalogUi.checkVerdict(check, performance));
        card.addView(verdict, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        if (!TextUtils.isEmpty(check.shortDescription)) {
            TextView description = new TextView(context);
            description.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            description.setTextColor(Theme.getColor(Theme.key_dialogTextBlack,
                    sheetResourcesProvider));
            description.setLineSpacing(AndroidUtilities.dp(2), 1f);
            description.setText(check.shortDescription);
            card.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        }

        for (String issue : check.issues) {
            TextView row = new TextView(context);
            row.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            row.setTextColor(Theme.getColor(Theme.key_dialogTextBlack,
                    sheetResourcesProvider));
            row.setLineSpacing(AndroidUtilities.dp(1), 1f);
            row.setText("•  " + issue);
            card.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT, 4, 6, 0, 0));
        }
    }

    private int toneColor(int tone) {
        return CatalogUi.toneColor(tone, sheetResourcesProvider);
    }
}
