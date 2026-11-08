package app.exteraless.feed.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.ChatSearchTabs;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.TopicsTabsView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.GradientClip;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import app.exteraless.feed.FeedConfig;

public class FeedCategoryTabsView extends FrameLayout {

    private static final int ADD_ITEM_ID = -2;

    public interface Delegate {
        void onCategorySelected(int id);

        void onCreateCategory();

        void onEditCategory(FeedConfig.Category category);

        void onHideCategory(FeedConfig.Category category);

        void onDeleteCategory(FeedConfig.Category category);
    }

    private final int currentAccount;
    private final BaseFragment fragment;
    private final Delegate delegate;
    private final Theme.ResourcesProvider resourcesProvider;
    private final UniversalRecyclerView listView;
    private final int[] location = new int[2];

    private HashMap<Integer, TLRPC.TL_forumTopic> topics = new HashMap<>();
    private int selectedCategoryId;
    private int drawnSelectedCategoryId;
    private int animateFromCategoryId;

    public FeedCategoryTabsView(Context context, BaseFragment fragment, int account, Theme.ResourcesProvider resourcesProvider, Delegate delegate) {
        super(context);
        this.fragment = fragment;
        this.currentAccount = account;
        this.resourcesProvider = resourcesProvider;
        this.delegate = delegate;
        selectedCategoryId = FeedConfig.getInstance(account).getSelectedCategoryId();
        drawnSelectedCategoryId = selectedCategoryId;
        listView = new UniversalRecyclerView(context, account, 0, this::fillItems, this::onItemClick, this::onItemLongClick, resourcesProvider) {
            private final AnimatedFloat clipLeft = new AnimatedFloat(this, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
            private final AnimatedFloat clipRight = new AnimatedFloat(this, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
            private final AnimatedFloat tabProgress = new AnimatedFloat(this, 420, CubicBezierInterpolator.EASE_OUT_QUINT);
            private final GradientClip clip = new GradientClip();
            private final RectF selectionRect = new RectF();
            private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void dispatchDraw(Canvas canvas) {
                float left = clipLeft.set(canScrollHorizontally(-1));
                float right = clipRight.set(canScrollHorizontally(1));
                boolean clipped = left > 0 || right > 0;
                if (clipped) {
                    canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), 255);
                }
                super.dispatchDraw(canvas);
                drawSelection(canvas);
                if (clipped) {
                    canvas.save();
                    if (left > 0) {
                        AndroidUtilities.rectTmp.set(0, 0, AndroidUtilities.dp(12), getHeight());
                        clip.draw(canvas, AndroidUtilities.rectTmp, GradientClip.LEFT, left);
                    }
                    if (right > 0) {
                        AndroidUtilities.rectTmp.set(getWidth() - AndroidUtilities.dp(12), 0, getWidth(), getHeight());
                        clip.draw(canvas, AndroidUtilities.rectTmp, GradientClip.RIGHT, right);
                    }
                    canvas.restore();
                    canvas.restore();
                }
            }

            private void drawSelection(Canvas canvas) {
                if (drawnSelectedCategoryId != selectedCategoryId) {
                    animateFromCategoryId = drawnSelectedCategoryId;
                    tabProgress.force(0);
                }
                drawnSelectedCategoryId = selectedCategoryId;
                View selected = null;
                View previous = null;
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    UItem item = adapter.getItem(getChildAdapterPosition(child));
                    if (item == null || item.id == ADD_ITEM_ID) {
                        continue;
                    }
                    if (item.id == selectedCategoryId) {
                        selected = child;
                    }
                    if (item.id == animateFromCategoryId) {
                        previous = child;
                    }
                }
                if (selected == null) {
                    return;
                }
                setTabRect(selectionRect, selected);
                if (previous != null) {
                    setTabRect(AndroidUtilities.rectTmp, previous);
                    AndroidUtilities.lerp(AndroidUtilities.rectTmp, selectionRect, tabProgress.set(1), selectionRect);
                }
                selectionPaint.setColor(ColorUtils.setAlphaComponent(
                        Theme.getColor(Theme.key_featuredStickers_addButton, FeedCategoryTabsView.this.resourcesProvider), 31));
                canvas.drawRoundRect(selectionRect, AndroidUtilities.dp(14), AndroidUtilities.dp(14), selectionPaint);
            }

            private void setTabRect(RectF rect, View view) {
                rect.set(view.getX() + AndroidUtilities.dp(1), view.getY() + AndroidUtilities.dp(4),
                        view.getX() + view.getWidth() - AndroidUtilities.dp(1), view.getY() + getHeight() - AndroidUtilities.dp(4));
            }

            @Override
            public Integer getSelectorColor(int position) {
                return 0;
            }
        };
        listView.setWillNotDraw(false);
        listView.adapter.setApplyBackground(false);
        listView.makeHorizontal();
        listView.setPadding(AndroidUtilities.dp(3), 0, AndroidUtilities.dp(3), 0);
        listView.setClipToPadding(false);
        addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 36, Gravity.CENTER_VERTICAL));
    }

    public void attachTo(ChatSearchTabs host) {
        if (host.tabs != null) {
            host.tabs.setVisibility(View.GONE);
        }
        host.setPadding(0, AndroidUtilities.dp(7), 0, AndroidUtilities.dp(7));
        host.addView(this, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL, 7, 0, 7, 0));
    }

    public boolean isTouchInside(MotionEvent event) {
        if (event == null || !isShown()) {
            return false;
        }
        getLocationOnScreen(location);
        return event.getRawX() >= location[0] && event.getRawX() < location[0] + getWidth()
                && event.getRawY() >= location[1] && event.getRawY() < location[1] + getHeight();
    }

    public boolean hasCategories() {
        return !FeedConfig.getInstance(currentAccount).getVisibleCategories().isEmpty();
    }

    public void update() {
        selectedCategoryId = FeedConfig.getInstance(currentAccount).getSelectedCategoryId();
        listView.adapter.update(true);
        listView.invalidate();
        List<FeedConfig.Category> visible = FeedConfig.getInstance(currentAccount).getVisibleCategories();
        int position = 0;
        for (int i = 0; i < visible.size(); i++) {
            if (visible.get(i).getId() == selectedCategoryId) {
                position = i + 1;
                break;
            }
        }
        listView.smoothScrollToPosition(position);
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        HashMap<Integer, TLRPC.TL_forumTopic> previous = topics;
        topics = new HashMap<>();
        items.add(TopicsTabsView.HorizontalTabView.Factory.asAll(false, false).setChecked(selectedCategoryId == 0));
        for (FeedConfig.Category category : FeedConfig.getInstance(currentAccount).getVisibleCategories()) {
            TLRPC.TL_forumTopic topic = previous.get(category.getId());
            if (topic == null) {
                topic = new TLRPC.TL_forumTopic();
            }
            topic.id = category.getId();
            topic.title = category.getTitle();
            topic.icon_emoji_id = category.getEmojiId();
            topics.put(category.getId(), topic);
            items.add(TopicsTabsView.HorizontalTabView.Factory.asTab(0L, topic, false).setChecked(category.getId() == selectedCategoryId));
        }
        items.add(TopicsTabsView.HorizontalTabView.Factory.asAdd());
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ADD_ITEM_ID) {
            delegate.onCreateCategory();
        } else if (item.id != selectedCategoryId) {
            delegate.onCategorySelected(item.id);
        }
    }

    private boolean onItemLongClick(UItem item, View view, int position, float x, float y) {
        FeedConfig.Category category = item.id > 0 ? FeedConfig.getInstance(currentAccount).getCategory(item.id) : null;
        if (category == null) {
            return false;
        }
        ItemOptions.makeOptions(fragment, view)
                .add(R.drawable.msg_edit, LocaleController.getString(R.string.FeedCategoryEdit), () -> delegate.onEditCategory(category))
                .add(R.drawable.msg_view_file, LocaleController.getString(R.string.FeedCategoryHide), () -> delegate.onHideCategory(category))
                .add(R.drawable.msg_delete, LocaleController.getString(R.string.FeedCategoryDelete), true, () -> delegate.onDeleteCategory(category))
                .setScrimViewBackground(new TabScrimDrawable(Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground, resourcesProvider)))
                .setGravity(Gravity.CENTER_HORIZONTAL)
                .show();
        return true;
    }

    private static final class TabScrimDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();

        TabScrimDrawable(int color) {
            paint.setColor(color);
        }

        @Override
        public void draw(Canvas canvas) {
            bounds.set(getBounds());
            bounds.inset(AndroidUtilities.dp(1), (bounds.height() - AndroidUtilities.dp(28)) / 2f);
            canvas.drawRoundRect(bounds, AndroidUtilities.dp(14), AndroidUtilities.dp(14), paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
