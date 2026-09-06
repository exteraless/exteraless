package app.exteraless.plugins.ui.catalog;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;

import app.exteraless.plugins.catalog.CatalogPlugin;

final class CatalogChecksCell extends FrameLayout implements Theme.Colorable {

    static final class Factory extends UItem.UItemFactory<CatalogChecksCell> {
        static { setup(new Factory()); }

        @Override
        public CatalogChecksCell createView(Context context, RecyclerListView listView,
                                            int currentAccount, int classGuid,
                                            Theme.ResourcesProvider resourcesProvider) {
            return new CatalogChecksCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter,
                             UniversalRecyclerView listView) {
            ((CatalogChecksCell) view).set((CatalogPlugin) item.object, divider);
        }

        @Override
        public boolean equals(UItem first, UItem second) {
            return first.id == second.id;
        }

        @Override
        public boolean contentsEquals(UItem first, UItem second) {
            CatalogPlugin a = first.object instanceof CatalogPlugin
                    ? (CatalogPlugin) first.object : null;
            CatalogPlugin b = second.object instanceof CatalogPlugin
                    ? (CatalogPlugin) second.object : null;
            return a != null && b != null && TextUtils.equals(a.slug, b.slug)
                    && TextUtils.equals(a.checkSummary, b.checkSummary);
        }

        static UItem asChecks(int id, CatalogPlugin plugin) {
            UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.object = plugin;
            return item;
        }
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private final SettingsActivity.SettingCell.Background iconBackground;
    private final ImageView iconView;
    private final TextView titleView;
    private final TextView statusView;
    private final LinearLayout chipsLayout;
    private CatalogPlugin plugin;
    private boolean needDivider;

    private CatalogChecksCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setBackground(Theme.getSelectorDrawable(false));

        FrameLayout iconLayout = new FrameLayout(context);
        iconBackground = new SettingsActivity.SettingCell.Background();
        iconLayout.setBackground(iconBackground);
        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iconView.setImageResource(R.drawable.msg2_policy);
        iconView.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        iconLayout.addView(iconView, LayoutHelper.createFrame(22, 22, Gravity.CENTER));
        addView(iconLayout, LayoutHelper.createFrameRelatively(30, 30,
                Gravity.START | Gravity.CENTER_VERTICAL, 17, 0, 0, 0));

        LinearLayout textLayout = new LinearLayout(context);
        textLayout.setOrientation(LinearLayout.VERTICAL);
        addView(textLayout, LayoutHelper.createFrameRelatively(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL,
                64, 0, 110, 0));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setText(getString(R.string.PluginCatalogAiChecks));
        textLayout.addView(titleView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        statusView.setTypeface(AndroidUtilities.bold());
        statusView.setSingleLine(true);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        textLayout.addView(statusView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

        chipsLayout = new LinearLayout(context);
        chipsLayout.setOrientation(LinearLayout.HORIZONTAL);
        chipsLayout.setGravity(Gravity.CENTER_VERTICAL);
        addView(chipsLayout, LayoutHelper.createFrameRelatively(LayoutHelper.WRAP_CONTENT,
                LayoutHelper.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL,
                0, 0, 14, 0));

        updateColors();
    }

    void set(CatalogPlugin plugin, boolean divider) {
        this.plugin = plugin;
        needDivider = divider;
        setWillNotDraw(!divider);

        int tone = CatalogUi.overallTone(plugin);
        IconBackgroundColors colors = tone > 0 ? IconBackgroundColors.GREEN
                : tone < 0 ? IconBackgroundColors.RED
                : IconBackgroundColors.ORANGE_BRIGHT;
        iconBackground.setColor(colors.top, colors.bottom);

        CharSequence summary = CatalogUi.overallLabel(plugin);
        statusView.setText(summary);
        statusView.setTextColor(toneColor(tone));

        chipsLayout.removeAllViews();
        addChip(plugin.securityCheck, false);
        addChip(plugin.performanceCheck, true);

        setContentDescription(titleView.getText() + ". " + summary);
        invalidate();
    }

    private void addChip(CatalogPlugin.AiCheck check, boolean performance) {
        if (check == null || check.score < 0) return;
        int color = toneColor(CatalogUi.checkTone(check));

        LinearLayout chip = new LinearLayout(getContext());
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(AndroidUtilities.dp(7), AndroidUtilities.dp(3),
                AndroidUtilities.dp(8), AndroidUtilities.dp(3));
        chip.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(10),
                Theme.multAlpha(color, 0.12f)));

        ImageView icon = new ImageView(getContext());
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setImageResource(performance ? R.drawable.msg_speed : R.drawable.msg2_policy);
        icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        chip.addView(icon, LayoutHelper.createLinear(13, 13,
                Gravity.CENTER_VERTICAL, 0, 0, 3, 0));

        TextView score = new TextView(getContext());
        score.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        score.setTypeface(AndroidUtilities.bold());
        score.setTextColor(color);
        score.setText(String.valueOf(check.score));
        chip.addView(score, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT,
                LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        chipsLayout.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT,
                LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL,
                chipsLayout.getChildCount() == 0 ? 0 : 5, 0, 0, 0));
    }

    private int toneColor(int tone) {
        return CatalogUi.toneColor(tone, resourcesProvider);
    }

    @Override
    public void updateColors() {
        titleView.setTextColor(Theme.getColor(
                Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        if (plugin != null) set(plugin, needDivider);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (needDivider) {
            float start = AndroidUtilities.dp(64);
            if (LocaleController.isRTL) {
                canvas.drawLine(0, getHeight() - 1, getWidth() - start,
                        getHeight() - 1, Theme.dividerPaint);
            } else {
                canvas.drawLine(start, getHeight() - 1, getWidth(),
                        getHeight() - 1, Theme.dividerPaint);
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(
                AndroidUtilities.dp(58) + (needDivider ? 1 : 0), MeasureSpec.EXACTLY));
    }
}
