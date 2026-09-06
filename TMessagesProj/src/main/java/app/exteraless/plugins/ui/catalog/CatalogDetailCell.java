package app.exteraless.plugins.ui.catalog;

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
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;

final class CatalogDetailCell extends FrameLayout implements Theme.Colorable {

    static final class ActionFactory extends UItem.UItemFactory<TextDetailSettingsCell> {
        static { setup(new ActionFactory()); }

        @Override
        public TextDetailSettingsCell createView(Context context, RecyclerListView listView,
                                                  int currentAccount, int classGuid,
                                                  Theme.ResourcesProvider resourcesProvider) {
            TextDetailSettingsCell cell = new TextDetailSettingsCell(context);
            cell.setMultilineDetail(true);
            return cell;
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter,
                             UniversalRecyclerView listView) {
            ((TextDetailSettingsCell) view).setTextAndValueAndIcon(
                    item.text.toString(), item.subtext, item.iconResId, divider);
        }

        @Override
        public boolean equals(UItem first, UItem second) {
            return first.id == second.id;
        }

        @Override
        public boolean contentsEquals(UItem first, UItem second) {
            return first.id == second.id
                    && first.iconResId == second.iconResId
                    && TextUtils.equals(first.text, second.text)
                    && TextUtils.equals(first.subtext, second.subtext);
        }

        static UItem asAction(int id, int icon, CharSequence title, CharSequence value) {
            UItem item = UItem.ofFactory(ActionFactory.class);
            item.id = id;
            item.iconResId = icon;
            item.text = title;
            item.subtext = value;
            return item;
        }
    }

    static final class Factory extends UItem.UItemFactory<CatalogDetailCell> {
        static { setup(new Factory()); }

        @Override
        public CatalogDetailCell createView(Context context, RecyclerListView listView,
                                            int currentAccount, int classGuid,
                                            Theme.ResourcesProvider resourcesProvider) {
            return new CatalogDetailCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter,
                             UniversalRecyclerView listView) {
            ((CatalogDetailCell) view).set(item.iconResId,
                    (IconBackgroundColors) item.object, item.text, item.subtext, divider);
        }

        @Override
        public boolean isClickable() {
            return false;
        }

        @Override
        public boolean equals(UItem first, UItem second) {
            return first.id == second.id;
        }

        @Override
        public boolean contentsEquals(UItem first, UItem second) {
            return first.id == second.id
                    && first.iconResId == second.iconResId
                    && first.object == second.object
                    && TextUtils.equals(first.text, second.text)
                    && TextUtils.equals(first.subtext, second.subtext);
        }

        static UItem asDetail(int id, int icon, IconBackgroundColors colors,
                              CharSequence title, CharSequence value) {
            UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.iconResId = icon;
            item.object = colors;
            item.text = title;
            item.subtext = value;
            return item;
        }
    }

    private final Theme.ResourcesProvider resourcesProvider;
    private final SettingsActivity.SettingCell.Background iconBackground;
    private final ImageView iconView;
    private final TextView titleView;
    private final TextView valueView;
    private boolean needDivider;

    private CatalogDetailCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;

        FrameLayout iconLayout = new FrameLayout(context);
        iconBackground = new SettingsActivity.SettingCell.Background();
        iconLayout.setBackground(iconBackground);
        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iconView.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        iconLayout.addView(iconView, LayoutHelper.createFrame(22, 22, Gravity.CENTER));
        addView(iconLayout, LayoutHelper.createFrameRelatively(30, 30,
                Gravity.START | Gravity.CENTER_VERTICAL, 17, 0, 0, 0));

        LinearLayout textLayout = new LinearLayout(context);
        textLayout.setOrientation(LinearLayout.VERTICAL);
        addView(textLayout, LayoutHelper.createFrameRelatively(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL,
                64, 0, 17, 0));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        textLayout.addView(titleView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        valueView = new TextView(context);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        valueView.setSingleLine(true);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        textLayout.addView(valueView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

        updateColors();
    }

    void set(int icon, IconBackgroundColors colors, CharSequence title, CharSequence value,
             boolean divider) {
        needDivider = divider;
        setWillNotDraw(!divider);
        iconView.setImageResource(icon);
        IconBackgroundColors tile = colors == null ? IconBackgroundColors.BLUE : colors;
        iconBackground.setColor(tile.top, tile.bottom);
        titleView.setText(title);
        valueView.setText(value);
        valueView.setVisibility(TextUtils.isEmpty(value) ? GONE : VISIBLE);
        setContentDescription(title + ". " + value);
        invalidate();
    }

    @Override
    public void updateColors() {
        titleView.setTextColor(Theme.getColor(
                Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        valueView.setTextColor(Theme.getColor(
                Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
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
