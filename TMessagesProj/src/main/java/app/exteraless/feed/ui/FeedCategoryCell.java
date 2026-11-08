package app.exteraless.feed.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.Objects;

import app.exteraless.feed.FeedConfig;

@SuppressLint("ViewConstructor")
public class FeedCategoryCell extends FrameLayout {

    private static final int HEIGHT_DP = 50;

    private final int currentAccount;
    private final Theme.ResourcesProvider resourcesProvider;
    private final BackupImageView iconView;
    private final TextView titleView;
    private final ImageView visibilityView;
    private boolean needDivider;

    public FeedCategoryCell(Context context, int account, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        currentAccount = account;
        this.resourcesProvider = resourcesProvider;
        setWillNotDraw(false);
        boolean rtl = LocaleController.isRTL;

        iconView = new BackupImageView(context);
        addView(iconView, LayoutHelper.createFrame(24, 24, Gravity.CENTER_VERTICAL | (rtl ? Gravity.RIGHT : Gravity.LEFT), 20, 0, 20, 0));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        titleView.setLines(1);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL | (rtl ? Gravity.RIGHT : Gravity.LEFT), rtl ? 104 : 64, 0, rtl ? 64 : 104, 0));

        visibilityView = new ImageView(context);
        visibilityView.setScaleType(ImageView.ScaleType.CENTER);
        visibilityView.setColorFilter(iconFilter());
        visibilityView.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), 1));
        addView(visibilityView, LayoutHelper.createFrame(40, 40, Gravity.CENTER_VERTICAL | (rtl ? Gravity.LEFT : Gravity.RIGHT), 52, 0, 52, 0));

        ImageView reorderView = new ImageView(context);
        reorderView.setScaleType(ImageView.ScaleType.CENTER);
        reorderView.setImageResource(R.drawable.list_reorder);
        reorderView.setColorFilter(iconFilter());
        addView(reorderView, LayoutHelper.createFrame(40, 40, Gravity.CENTER_VERTICAL | (rtl ? Gravity.LEFT : Gravity.RIGHT), 8, 0, 8, 0));
    }

    private PorterDuffColorFilter iconFilter() {
        return new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon, resourcesProvider), PorterDuff.Mode.MULTIPLY);
    }

    public void set(FeedConfig.Category category, boolean divider, View.OnClickListener onVisibilityClick) {
        needDivider = divider;
        titleView.setText(category.getTitle());
        if (category.getEmojiId() != 0) {
            iconView.setColorFilter(null);
            iconView.setAnimatedEmojiDrawable(AnimatedEmojiDrawable.make(currentAccount, AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW, category.getEmojiId()));
            iconView.setEmojiColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider), PorterDuff.Mode.SRC_IN));
        } else {
            iconView.setAnimatedEmojiDrawable(null);
            iconView.setImageResource(R.drawable.msg_folders);
            iconView.setColorFilter(iconFilter());
        }
        boolean hidden = category.isHidden();
        visibilityView.setImageResource(hidden ? R.drawable.menu_hide_gift : R.drawable.msg_view_file);
        visibilityView.setContentDescription(LocaleController.getString(hidden ? R.string.FeedCategoryShow : R.string.FeedCategoryHide));
        visibilityView.setOnClickListener(onVisibilityClick);
        float alpha = hidden ? 0.5f : 1f;
        titleView.setAlpha(alpha);
        iconView.setAlpha(alpha);
        visibilityView.setAlpha(alpha);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(HEIGHT_DP) + (needDivider ? 1 : 0), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (needDivider) {
            canvas.drawLine(LocaleController.isRTL ? 0 : AndroidUtilities.dp(64), getHeight() - 1,
                    getWidth() - (LocaleController.isRTL ? AndroidUtilities.dp(64) : 0), getHeight() - 1, Theme.dividerPaint);
        }
    }

    public static class Factory extends UItem.UItemFactory<FeedCategoryCell> {
        static {
            setup(new Factory());
        }

        @Override
        public FeedCategoryCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new FeedCategoryCell(context, currentAccount, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            if (view instanceof FeedCategoryCell && item.object instanceof FeedConfig.Category) {
                ((FeedCategoryCell) view).set((FeedConfig.Category) item.object, divider, item.clickCallback);
            }
        }

        @Override
        public boolean equals(UItem a, UItem b) {
            return a.id == b.id;
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            return Objects.equals(a.object, b.object);
        }

        public static UItem of(int id, FeedConfig.Category category, View.OnClickListener onVisibilityClick) {
            UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.object = category;
            item.clickCallback = onVisibilityClick;
            return item;
        }
    }
}
