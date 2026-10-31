package app.exteraless.player;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

public class PlayerQueueSheet extends BottomSheet implements NotificationCenter.NotificationCenterDelegate {

    private final PlayerColors colors;
    private final int account;
    private final FrameLayout root;
    private final RecyclerListView list;
    private final LinearLayoutManager layoutManager;
    private final TextView subtitle;
    private final TextView chip;
    private final GradientDrawable chipBg = new GradientDrawable();
    private final PlayerIcon chipIcon = PlayerIcon.stroke(PlayerIcon.SHUFFLE, 18);
    private final Adapter adapter = new Adapter();
    private final ArrayList<MessageObject> items = new ArrayList<>();
    private boolean touchInList;
    private boolean dragging;
    private ItemTouchHelper touchHelper;

    public PlayerQueueSheet(Context context, PlayerColors colors, Theme.ResourcesProvider resourcesProvider) {
        super(context, false, resourcesProvider);
        this.colors = colors;
        occupyNavigationBar = true;
        drawNavigationBar = false;
        setApplyTopPadding(false);
        setApplyBottomPadding(false);
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        account = playing != null ? playing.currentAccount : currentAccount;
        currentAccount = account;

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colors.surfaceLow);
        bg.setCornerRadii(new float[]{dp(28), dp(28), dp(28), dp(28), 0, 0, 0, 0});
        root = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int h = (int) (MeasureSpec.getSize(heightMeasureSpec) * 0.78f);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            }

            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    touchInList = ev.getY() >= list.getTop();
                }
                boolean result = super.dispatchTouchEvent(ev);
                if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && touchInList) {
                    touchInList = false;
                    container.requestDisallowInterceptTouchEvent(false);
                }
                return result;
            }
        };
        root.setBackground(bg);
        containerView = root;

        View handle = new View(context);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setCornerRadius(dp(2));
        handleBg.setColor(ColorUtils.setAlphaComponent(colors.onSurfaceVariant, 0x73));
        handle.setBackground(handleBg);
        root.addView(handle, LayoutHelper.createFrame(32, 4, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 18, 0, 0));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(24), 0, dp(24), 0);
        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(colors.onSurface);
        title.setText(getString(R.string.OEPlayerQueue));
        titles.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        subtitle = new TextView(context);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setTextColor(colors.onSurfaceVariant);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        header.addView(titles, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        chip = new TextView(context);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        chip.setTypeface(AndroidUtilities.bold());
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(12), 0, dp(16), 0);
        chip.setCompoundDrawablePadding(dp(8));
        chip.setCompoundDrawablesWithIntrinsicBounds(chipIcon, null, null, null);
        chip.setText(getString(R.string.OEPlayerShuffle));
        chip.setBackground(chipBg);
        chip.setOnClickListener(v -> {
            MediaController.getInstance().setPlaybackOrderType(SharedConfig.shuffleMusic ? 0 : 2);
            updateChip();
            syncItems(true);
            PlayerSheet.onModesChanged();
        });
        header.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 40, 12, 0, 0, 0));
        root.addView(header, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 72, Gravity.TOP, 0, 34, 0, 0));

        list = new RecyclerListView(context) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                setPadding(dp(12), 0, dp(12), dp(24) + getBottomInset());
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        layoutManager = new LinearLayoutManager(context);
        list.setLayoutManager(layoutManager);
        syncItems(true);
        list.setAdapter(adapter);
        list.setClipToPadding(false);
        list.setVerticalScrollBarEnabled(false);
        list.setSelectorDrawableColor(0);
        list.setOnItemClickListener((view, position) -> onItemClick(position));
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                int last = layoutManager.findLastVisibleItemPosition();
                if (last >= adapter.getItemCount() - 5) {
                    MediaController.getInstance().loadMoreMusic();
                }
            }
        });
        root.addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 34 + 72 + 8, 0, 0));
        touchHelper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                return makeMovementFlags(SharedConfig.shuffleMusic ? 0 : ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return !SharedConfig.shuffleMusic;
            }

            @Override
            public boolean isItemViewSwipeEnabled() {
                return false;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder source, @NonNull RecyclerView.ViewHolder target) {
                return moveItem(source.getAdapterPosition(), target.getAdapterPosition());
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }

            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
                super.onSelectedChanged(viewHolder, actionState);
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null && viewHolder.itemView instanceof Row) {
                    dragging = true;
                    container.requestDisallowInterceptTouchEvent(true);
                    viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    ((Row) viewHolder.itemView).setDragging(true);
                }
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                dragging = false;
                if (viewHolder.itemView instanceof Row) {
                    ((Row) viewHolder.itemView).setDragging(false);
                }
            }
        });
        touchHelper.attachToRecyclerView(list);

        updateChip();
        updateSubtitle();
        int index = indexOfPlaying();
        if (index > 0) {
            layoutManager.scrollToPositionWithOffset(index, dp(72));
        }
    }

    @Override
    protected boolean canDismissWithSwipe() {
        return !dragging && (!touchInList || !list.canScrollVertically(-1));
    }

    private int playlistIndex(int position) {
        return direct() ? position : items.size() - 1 - position;
    }

    private boolean moveItem(int from, int to) {
        if (from < 0 || to < 0 || from >= items.size() || to >= items.size() || from == to) {
            return false;
        }
        if (!MediaController.getInstance().movePlaylistItem(playlistIndex(from), playlistIndex(to))) {
            return false;
        }
        items.add(to, items.remove(from));
        adapter.notifyItemMoved(from, to);
        return true;
    }

    @Override
    public void show() {
        super.show();
        NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.addObserver(this, NotificationCenter.messagePlayingDidStart);
        nc.addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        nc.addObserver(this, NotificationCenter.messagePlayingDidReset);
        nc.addObserver(this, NotificationCenter.moreMusicDidLoad);
        nc.addObserver(this, NotificationCenter.musicDidLoad);
        AndroidUtilities.setLightNavigationBar(this, ColorUtils.calculateLuminance(colors.surfaceLow) > 0.5);
    }

    @Override
    public void dismiss() {
        super.dismiss();
        NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.removeObserver(this, NotificationCenter.messagePlayingDidStart);
        nc.removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        nc.removeObserver(this, NotificationCenter.messagePlayingDidReset);
        nc.removeObserver(this, NotificationCenter.moreMusicDidLoad);
        nc.removeObserver(this, NotificationCenter.musicDidLoad);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagePlayingDidReset) {
            MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            if (mo == null || !mo.isMusic()) {
                dismiss();
                return;
            }
        }
        if (id == NotificationCenter.moreMusicDidLoad || id == NotificationCenter.musicDidLoad) {
            syncItems(true);
            updateSubtitle();
            return;
        }
        if (syncItems(false)) {
            updateSubtitle();
            return;
        }
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child instanceof Row) {
                ((Row) child).updateState();
            }
        }
    }

    private void updateChip() {
        boolean on = SharedConfig.shuffleMusic;
        chipBg.setCornerRadius(dp(on ? 12 : 20));
        chipBg.setColor(on ? colors.secondaryContainer : 0);
        chipBg.setStroke(on ? 0 : dp(1), colors.outlineVariant);
        int fg = on ? colors.onSecondaryContainer : colors.onSurfaceVariant;
        chip.setTextColor(fg);
        chipIcon.setColor(fg);
    }

    private void updateSubtitle() {
        int count = MediaController.getInstance().getPlaylist().size();
        subtitle.setText(LocaleController.formatPluralString("OEPlayerTracks", count));
    }

    private static boolean direct() {
        return MediaController.getInstance().currentSavedMusicList != null ? !SharedConfig.playOrderReversed : SharedConfig.playOrderReversed;
    }

    private MessageObject itemAt(int position) {
        if (position < 0 || position >= items.size()) {
            return null;
        }
        return items.get(position);
    }

    private boolean syncItems(boolean force) {
        ArrayList<MessageObject> playlist = MediaController.getInstance().getPlaylist();
        ArrayList<MessageObject> ordered = new ArrayList<>(playlist);
        if (!direct()) {
            Collections.reverse(ordered);
        }
        boolean changed = force || ordered.size() != items.size();
        for (int i = 0; !changed && i < ordered.size(); i++) {
            changed = ordered.get(i) != items.get(i);
        }
        if (!changed) {
            return false;
        }
        items.clear();
        items.addAll(ordered);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        return true;
    }

    private static MessageObject currentItem() {
        MediaController mc = MediaController.getInstance();
        MessageObject playing = mc.getPlayingMessageObject();
        if (playing == null) {
            return null;
        }
        ArrayList<MessageObject> playlist = mc.getPlaylist();
        for (int i = 0; i < playlist.size(); i++) {
            if (playlist.get(i) == playing) {
                return playing;
            }
        }
        for (int i = 0; i < playlist.size(); i++) {
            MessageObject mo = playlist.get(i);
            if (mo.getDialogId() == playing.getDialogId() && mo.getId() == playing.getId()) {
                return mo;
            }
        }
        String key = PlayerArt.key(playing);
        for (int i = 0; i < playlist.size(); i++) {
            MessageObject mo = playlist.get(i);
            if (key.equals(PlayerArt.key(mo))) {
                return mo;
            }
        }
        return null;
    }

    private int indexOfPlaying() {
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        int count = adapter.getItemCount();
        for (int i = 0; i < count; i++) {
            if (itemAt(i) == playing) {
                return i;
            }
        }
        return -1;
    }

    private void onItemClick(int position) {
        MessageObject mo = itemAt(position);
        if (mo == null) {
            return;
        }
        MediaController mc = MediaController.getInstance();
        if (mc.isPlayingMessage(mo)) {
            if (mc.isMessagePaused()) {
                mc.playMessage(mo);
            } else {
                mc.pauseMessage(mo);
            }
        } else {
            mc.findMessageInPlaylistAndPlay(mo);
        }
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Row row = new Row(parent.getContext());
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));
            return new RecyclerListView.Holder(row);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((Row) holder.itemView).bind(itemAt(position));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private class Row extends FrameLayout {

        private final CoverImage cover;
        private final TextView title;
        private final TextView artist;
        private final TextView duration;
        private final Equalizer equalizer;
        private final View handle;
        private final GradientDrawable bg = new GradientDrawable();
        private MessageObject message;
        private boolean draggingRow;

        Row(Context context) {
            super(context);
            bg.setCornerRadius(dp(20));
            setBackground(bg);
            setPadding(dp(10), 0, dp(14), 0);
            cover = new CoverImage(context, 22);
            cover.setRadius(dp(14));
            cover.setColors(colors.primaryContainer, colors.onPrimaryContainer);
            addView(cover, LayoutHelper.createFrame(52, 52, Gravity.LEFT | Gravity.CENTER_VERTICAL));
            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            title.setTypeface(AndroidUtilities.bold());
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            artist = new TextView(context);
            artist.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            artist.setSingleLine(true);
            artist.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(artist, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 66, 0, 88, 0));
            duration = new TextView(context);
            duration.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            duration.setFontFeatureSettings("tnum");
            duration.setTextColor(colors.onSurfaceVariant);
            addView(duration, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 36, 0));
            equalizer = new Equalizer(context);
            addView(equalizer, LayoutHelper.createFrame(22, 20, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 36, 0));
            handle = new View(context);
            PlayerIcon handleIcon = PlayerIcon.stroke(PlayerIcon.DRAG, 22);
            handleIcon.setColor(colors.onSurfaceVariant);
            handle.setBackground(handleIcon);
            handle.setContentDescription(getString(R.string.OEPlayerDragToReorder));
            handle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !SharedConfig.shuffleMusic) {
                    RecyclerView.ViewHolder holder = list.findContainingViewHolder(this);
                    if (holder != null) {
                        touchHelper.startDrag(holder);
                    }
                }
                return false;
            });
            addView(handle, LayoutHelper.createFrame(22, 22, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        }

        void setDragging(boolean value) {
            draggingRow = value;
            setElevation(value ? dp(4) : 0);
            updateState();
        }

        void bind(MessageObject mo) {
            message = mo;
            if (mo == null) {
                return;
            }
            cover.setMessage(mo);
            title.setText(mo.getMusicTitle());
            artist.setText(mo.getMusicAuthor());
            int d = (int) Math.round(mo.getDuration());
            duration.setText(String.format(Locale.US, "%d:%02d", d / 60, d % 60));
            updateState();
        }

        void updateState() {
            boolean current = message != null && message == currentItem();
            bg.setColor(current ? colors.secondaryContainer : draggingRow ? colors.surfaceHigh : 0);
            handle.setVisibility(SharedConfig.shuffleMusic ? INVISIBLE : VISIBLE);
            title.setTextColor(current ? colors.onSecondaryContainer : colors.onSurface);
            artist.setTextColor(current ? colors.onSecondaryContainer : colors.onSurfaceVariant);
            duration.setVisibility(current ? GONE : VISIBLE);
            equalizer.setVisibility(current ? VISIBLE : GONE);
            equalizer.setPlaying(current && !MediaController.getInstance().isMessagePaused());
        }
    }

    private class Equalizer extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private boolean playing;
        private float phase;
        private long lastFrame;

        Equalizer(Context context) {
            super(context);
            paint.setColor(colors.primary);
        }

        void setPlaying(boolean value) {
            if (playing != value) {
                playing = value;
                lastFrame = 0;
                invalidate();
            }
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            long now = SystemClock.elapsedRealtime();
            float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
            if (playing) {
                phase += 6f * dt;
            }
            float[] still = {6, 11, 8};
            float barW = dp(4);
            float gap = dp(3);
            float total = barW * 3 + gap * 2;
            float x = (getWidth() - total) / 2f;
            float bottom = getHeight();
            for (int k = 0; k < 3; k++) {
                float h = playing ? dp(5 + Math.abs((float) Math.sin(phase * 1.6f + k * 2.1f)) * 13) : dp(still[k]);
                rect.set(x, bottom - h, x + barW, bottom);
                canvas.drawRoundRect(rect, dp(2), dp(2), paint);
                x += barW + gap;
            }
            if (playing) {
                postInvalidateOnAnimation();
            } else {
                lastFrame = 0;
            }
        }
    }
}
