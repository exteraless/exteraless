package app.exteraless.drawer;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

import app.exteraless.appearance.AppearanceConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CombinedDrawable;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet;
import org.telegram.ui.Components.Premium.PremiumGradient;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.LoginActivity;

import tw.nekomimi.nekogram.helpers.PasscodeHelper;

/**
 * Раскрывающийся список аккаунтов под шапкой шторки.
 * exteraGram: {@code com/exteragram/messenger/drawer/DrawerAccountPickerView.java} (858 строк).
 *
 * Не переносится «бейдж exteraGram» ({@code BadgesController} / {@code BadgeDTO}) — это
 * запрос к серверу exteraGram, которого у нас нет. Из-за этого убраны {@code badgeOverride},
 * {@code loadAccounts(BadgeDTO)} и второй правый drawable у имени.
 */
public class DrawerAccountPickerView extends FrameLayout {

    private static final int COLOR_KEY_BACKGROUND = Theme.key_windowBackgroundGray;
    private static final int COLOR_KEY_SELECTOR = Theme.key_listSelector;
    private static final int COLOR_KEY_SURFACE = Theme.key_windowBackgroundWhite;
    private static final int COLOR_KEY_TEXT = Theme.key_windowBackgroundWhiteBlackText;
    private static final int COLOR_KEY_STATUS = Theme.key_profile_verifiedBackground;
    private static final int COLOR_KEY_ACCENT = Theme.key_featuredStickers_addButton;
    private static final int COLOR_KEY_ADD_ICON = Theme.key_featuredStickers_buttonText;

    @FunctionalInterface
    public interface OnAccountLongClick {
        void onLongClick(int account, View view);
    }

    private final ArrayList<Integer> accounts = new ArrayList<>();
    private final AccountAdapter adapter;
    private final RecyclerView recyclerView;
    private final FrameLayout clipWrapper;
    private final ItemTouchHelper itemTouchHelper;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clipMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint topGradientPaint = new Paint();
    private final Paint bottomGradientPaint = new Paint();
    private final RectF bgRect = new RectF();
    private final float cornerRadius = AndroidUtilities.dp(16.0f);

    private LinearGradient topGradient;
    private LinearGradient bottomGradient;
    private int lastHeight;
    private int currentAnimatedHeight = -1;
    private ValueAnimator expandAnimator;
    private View draggingItemView;
    private boolean expanded;

    private Runnable onAccountSelected;
    private OnAccountLongClick onAccountLongClick;

    public DrawerAccountPickerView(Context context) {
        super(context);
        expanded = MessagesController.getGlobalMainSettings().getBoolean("accountsShown", true);

        clipMaskPaint.setStyle(Paint.Style.FILL);
        clipMaskPaint.setColor(0xff000000);
        clipMaskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        topGradientPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        bottomGradientPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));

        clipWrapper = new FrameLayout(context) {
            @Override
            protected void dispatchDraw(Canvas canvas) {
                bgPaint.setColor(Theme.getColor(COLOR_KEY_BACKGROUND));
                bgRect.set(0.0f, 0.0f, getWidth(), getHeight());
                canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint);

                final int save = canvas.saveLayer(0.0f, 0.0f, getWidth(), getHeight(), null);
                super.dispatchDraw(canvas);

                if (topGradient == null || getHeight() != lastHeight) {
                    lastHeight = getHeight();
                    topGradient = new LinearGradient(0.0f, 0.0f, 0.0f, AndroidUtilities.dp(16.0f),
                            new int[]{0xff000000, 0}, null, Shader.TileMode.CLAMP);
                    topGradientPaint.setShader(topGradient);
                    bottomGradient = new LinearGradient(0.0f, getHeight(), 0.0f, getHeight() - AndroidUtilities.dp(16.0f),
                            new int[]{0xff000000, 0}, null, Shader.TileMode.CLAMP);
                    bottomGradientPaint.setShader(bottomGradient);
                }

                final int fade = AndroidUtilities.dp(16.0f);
                final int scrollOffset = recyclerView.computeVerticalScrollOffset();
                final int scrollLeft = Math.max(0,
                        recyclerView.computeVerticalScrollRange() - recyclerView.computeVerticalScrollExtent() - scrollOffset);
                final float topAlpha = Math.min(1.0f, Math.max(0.0f, scrollOffset / (float) fade));
                final float bottomAlpha = Math.min(1.0f, scrollLeft / (float) fade);
                if (topAlpha > 0.0f) {
                    topGradientPaint.setAlpha((int) (topAlpha * 255.0f));
                    canvas.drawRect(0.0f, 0.0f, getWidth(), fade, topGradientPaint);
                }
                if (bottomAlpha > 0.0f) {
                    bottomGradientPaint.setAlpha((int) (bottomAlpha * 255.0f));
                    canvas.drawRect(0.0f, getHeight() - fade, getWidth(), getHeight(), bottomGradientPaint);
                }
                canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, clipMaskPaint);
                canvas.restoreToCount(save);
            }

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                final int maxHeight = getMaxListHeight();
                final int width = MeasureSpec.getSize(widthMeasureSpec);
                measureChildren(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.EXACTLY));
                if (currentAnimatedHeight >= 0) {
                    setMeasuredDimension(width, currentAnimatedHeight);
                    return;
                }
                final int heightSize = MeasureSpec.getSize(heightMeasureSpec);
                if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED || heightSize > maxHeight) {
                    heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        clipWrapper.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius);
            }
        });
        clipWrapper.setClipToOutline(true);
        addView(clipWrapper, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 0.0f, Gravity.TOP, 12.0f, 0.0f, 12.0f, 0.0f));

        adapter = new AccountAdapter();
        recyclerView = new RecyclerView(context);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        recyclerView.setAdapter(adapter);
        final int listPadding = AndroidUtilities.dp(4.0f);
        recyclerView.setPadding(listPadding, listPadding, listPadding, listPadding);
        recyclerView.setClipToPadding(false);
        recyclerView.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
                final int position = parent.getChildAdapterPosition(view);
                if (position < 0 || position >= state.getItemCount() - 1) {
                    return;
                }
                outRect.bottom = listPadding;
            }
        });
        recyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        recyclerView.setVerticalScrollBarEnabled(false);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                clipWrapper.invalidate();
            }
        });
        clipWrapper.addView(recyclerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final RecyclerView.ChildDrawingOrderCallback drawingOrderCallback = this::resolveDragDrawingOrder;
        itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(RecyclerView rv, RecyclerView.ViewHolder holder) {
                if (holder.getAdapterPosition() >= accounts.size()) {
                    return 0;
                }
                return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }

            @Override
            public boolean onMove(RecyclerView rv, RecyclerView.ViewHolder from, RecyclerView.ViewHolder to) {
                final int fromPosition = from.getAdapterPosition();
                final int toPosition = to.getAdapterPosition();
                if (fromPosition >= accounts.size() || toPosition >= accounts.size()) {
                    return false;
                }
                adapter.swapElements(fromPosition, toPosition);
                return true;
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder holder, int direction) {
            }

            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder holder, int actionState) {
                if (actionState != ItemTouchHelper.ACTION_STATE_DRAG || holder == null) {
                    return;
                }
                draggingItemView = holder.itemView;
                draggingItemView.setPressed(false);
                draggingItemView.jumpDrawablesToCurrentState();
                recyclerView.setChildDrawingOrderCallback(drawingOrderCallback);
                recyclerView.invalidate();
            }

            @Override
            public void onChildDraw(Canvas canvas, RecyclerView rv, RecyclerView.ViewHolder holder,
                                    float dX, float dY, int actionState, boolean isCurrentlyActive) {
                holder.itemView.setTranslationX(dX);
                holder.itemView.setTranslationY(dY);
            }

            @Override
            public void clearView(RecyclerView rv, RecyclerView.ViewHolder holder) {
                holder.itemView.setTranslationX(0.0f);
                holder.itemView.setTranslationY(0.0f);
                holder.itemView.setPressed(false);
                if (draggingItemView == holder.itemView) {
                    draggingItemView = null;
                }
                rv.setChildDrawingOrderCallback(null);
                rv.invalidate();
            }
        });
        itemTouchHelper.attachToRecyclerView(recyclerView);

        if (!expanded) {
            setVisibility(View.GONE);
            return;
        }
        loadAccounts();
        setVisibility(View.VISIBLE);
        final ViewGroup.LayoutParams lp = clipWrapper.getLayoutParams();
        lp.height = LayoutHelper.WRAP_CONTENT;
        clipWrapper.setLayoutParams(lp);
    }

    /** exteraGram: высота списка = 48dp * min(itemCount, 5.5) + 4dp сверху и снизу. */
    private int getMaxListHeight() {
        final int itemCount = adapter.getItemCount();
        return (int) ((AndroidUtilities.dp(48.0f) * (itemCount <= 6 ? itemCount : 5.5f))
                + (AndroidUtilities.dp(4.0f) * 2));
    }

    /** Перетаскиваемая строка рисуется поверх остальных. */
    private int resolveDragDrawingOrder(int childCount, int i) {
        if (draggingItemView != null) {
            final int index = recyclerView.indexOfChild(draggingItemView);
            if (index >= 0) {
                if (i == childCount - 1) {
                    return index;
                }
                if (i >= index) {
                    return i + 1;
                }
            }
        }
        return i;
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void toggleExpand() {
        setExpanded(!expanded);
    }

    public void setOnAccountSelected(Runnable onAccountSelected) {
        this.onAccountSelected = onAccountSelected;
    }

    public void setOnAccountLongClick(OnAccountLongClick onAccountLongClick) {
        this.onAccountLongClick = onAccountLongClick;
    }

    /** Порядок по {@code loginTime}. */
    public void loadAccounts() {
        accounts.clear();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated() && !PasscodeHelper.isAccountHidden(a)) {
                accounts.add(a);
            }
        }
        // Компаратор восстановлен по поведению:
        // но swapElements меняет местами именно loginTime — сортировка по нему.
        accounts.sort(Comparator.comparingLong(account -> UserConfig.getInstance(account).loginTime));
        adapter.notifyDataSetChanged();
    }

    public void updateColors() {
        bgPaint.setColor(Theme.getColor(COLOR_KEY_BACKGROUND));
        invalidate();
        adapter.notifyDataSetChanged();
    }

    public void updateUnreadCounters() {
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            final View child = recyclerView.getChildAt(i);
            if (child instanceof AccountRowView) {
                ((AccountRowView) child).updateUnreadCounter();
            }
        }
    }

    public void dispose() {
        if (expandAnimator != null) {
            expandAnimator.cancel();
            expandAnimator = null;
        }
        draggingItemView = null;
        recyclerView.setChildDrawingOrderCallback(null);
        recyclerView.stopScroll();
    }

    /** 250 мс по высоте. */
    public void setExpanded(boolean expand) {
        if (expanded == expand) {
            return;
        }
        expanded = expand;
        MessagesController.getGlobalMainSettings().edit().putBoolean("accountsShown", expand).apply();
        if (expand) {
            loadAccounts();
            setVisibility(View.VISIBLE);
        }
        if (expandAnimator != null) {
            expandAnimator.cancel();
        }
        int startHeight = currentAnimatedHeight;
        if (startHeight < 0) {
            startHeight = clipWrapper.getLayoutParams().height;
            if (startHeight < 0) {
                startHeight = clipWrapper.getHeight();
            }
            if (startHeight < 0) {
                startHeight = expand ? 0 : clipWrapper.getMeasuredHeight();
            }
        }
        currentAnimatedHeight = -1;
        final View parent = (View) getParent();
        final int parentWidth = parent != null ? parent.getMeasuredWidth() : getMeasuredWidth();
        clipWrapper.measure(
                MeasureSpec.makeMeasureSpec(Math.max(0, parentWidth - AndroidUtilities.dp(24.0f)), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(getMaxListHeight(), MeasureSpec.AT_MOST));
        final int endHeight = expand ? clipWrapper.getMeasuredHeight() : 0;
        currentAnimatedHeight = startHeight;

        final ValueAnimator animator = ValueAnimator.ofInt(startHeight, endHeight);
        expandAnimator = animator;
        animator.setDuration(250L);
        animator.setInterpolator(CubicBezierInterpolator.DEFAULT);
        animator.addUpdateListener(a -> {
            currentAnimatedHeight = (int) a.getAnimatedValue();
            clipWrapper.requestLayout();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (cancelled || expandAnimator != animation) {
                    return;
                }
                expandAnimator = null;
                currentAnimatedHeight = -1;
                if (!expanded) {
                    setVisibility(View.GONE);
                    return;
                }
                final ViewGroup.LayoutParams lp = clipWrapper.getLayoutParams();
                lp.height = LayoutHelper.WRAP_CONTENT;
                clipWrapper.setLayoutParams(lp);
            }
        });
        animator.start();
    }

    // ---- добавление аккаунта ----

    private boolean canAddAccount() {
        return getAvailableAccountForAdd() != null;
    }

    /**
     * exteraGram: {@code freeAccountsForAdd()} — без премиума доступна лишь половина слотов.
     * У exteraGram {@code MAX_ACCOUNT_COUNT} = 16 и вычитается 8; у нас константа 10,
     * поэтому вычитается её половина.
     */
    private int freeAccountsForAdd() {
        int free = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) {
                free++;
            }
        }
        return UserConfig.hasPremiumOnAccounts() ? free : free - UserConfig.MAX_ACCOUNT_COUNT / 2;
    }

    private Integer getAvailableAccountForAdd() {
        if (freeAccountsForAdd() <= 0) {
            return null;
        }
        for (int a = UserConfig.MAX_ACCOUNT_COUNT - 1; a >= 0; a--) {
            if (!UserConfig.getInstance(a).isClientActivated()) {
                return a;
            }
        }
        return null;
    }

    private void openAddAccountFlow() {
        final Activity activity = AndroidUtilities.findActivity(getContext());
        final LaunchActivity launchActivity = activity instanceof LaunchActivity
                ? (LaunchActivity) activity : LaunchActivity.instance;
        if (launchActivity == null) {
            return;
        }
        final Integer account = getAvailableAccountForAdd();
        if (account != null) {
            launchActivity.presentFragment(new LoginActivity(account));
            return;
        }
        if (UserConfig.hasPremiumOnAccounts()) {
            return;
        }
        final BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
        if (lastFragment == null) {
            return;
        }
        lastFragment.showDialog(new LimitReachedBottomSheet(lastFragment, launchActivity,
                LimitReachedBottomSheet.TYPE_ACCOUNTS, lastFragment.getCurrentAccount(), null));
    }

    private static Drawable createAccountItemRippleDrawable() {
        return Theme.createRadSelectorDrawable(Theme.getColor(COLOR_KEY_SELECTOR), 12, 12);
    }

    private static Drawable createSelectedAccountBackgroundDrawable() {
        return Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(12.0f),
                Theme.getColor(COLOR_KEY_SURFACE), Theme.getColor(COLOR_KEY_SELECTOR));
    }

    // ---- строка аккаунта ----

    /** 44dp, аватар 34dp. */
    public static class AccountRowView extends FrameLayout {

        private final AvatarDrawable avatarDrawable;
        private final BackupImageView avatarView;
        private final SimpleTextView nameView;
        private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable premiumStatusDrawable;
        private final DrawerAccountUnreadBadge unreadBadge;
        private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF avatarRect = new RectF();

        private boolean selected;

        public AccountRowView(Context context) {
            super(context);
            setWillNotDraw(false);
            setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.MATCH_PARENT, AndroidUtilities.dp(44.0f)));
            setBackground(createAccountItemRippleDrawable());

            avatarDrawable = new AvatarDrawable();
            avatarDrawable.setTextSize(AndroidUtilities.dp(20.0f));
            avatarView = new BackupImageView(context);
            updateAvatarRadius();
            addView(avatarView, LayoutHelper.createFrame(34, 34.0f, Gravity.LEFT | Gravity.CENTER_VERTICAL, 8.0f, 0.0f, 0.0f, 0.0f));

            nameView = new SimpleTextView(context);
            nameView.setTextSize(15);
            nameView.setTypeface(AndroidUtilities.bold());
            nameView.setTextColor(Theme.getColor(COLOR_KEY_TEXT));
            nameView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            nameView.setEllipsizeByGradient(true);
            nameView.setCanHideRightDrawable(false);
            nameView.setRightDrawableOutside(true);
            addView(nameView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
                    Gravity.LEFT, 54.0f, 0.0f, 12.0f, 0.0f));

            premiumStatusDrawable = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(nameView, AndroidUtilities.dp(18.0f));
            unreadBadge = new DrawerAccountUnreadBadge();

            checkPaint.setStyle(Paint.Style.STROKE);
            checkPaint.setStrokeWidth(AndroidUtilities.dp(1.67f));
            checkPaint.setStrokeCap(Paint.Cap.ROUND);
            checkPaint.setStrokeJoin(Paint.Join.ROUND);
        }

        public void bind(int account) {
            final TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
            if (user == null) {
                return;
            }
            updateAvatarRadius();
            avatarDrawable.setInfo(account, user);
            nameView.setTextColor(Theme.getColor(COLOR_KEY_TEXT));
            nameView.setText(ContactsController.formatName(user.first_name, user.last_name));
            avatarView.getImageReceiver().setCurrentAccount(account);
            avatarView.setForUserOrChat(user, avatarDrawable);
            premiumStatusDrawable.setCurrentAccount(account);

            final int statusColor = Theme.getColor(COLOR_KEY_STATUS);
            final long emojiStatusId = DialogObject.getEmojiStatusDocumentId(user.emoji_status);
            final boolean premium = MessagesController.getInstance(account).isPremiumUser(user);
            Drawable rightDrawable = null;
            if (emojiStatusId != 0) {
                premiumStatusDrawable.set(emojiStatusId, false);
                rightDrawable = premiumStatusDrawable;
            } else if (premium) {
                premiumStatusDrawable.set(PremiumGradient.getInstance().premiumStarDrawableMini, false);
                rightDrawable = premiumStatusDrawable;
            } else {
                premiumStatusDrawable.set((Drawable) null, false);
            }
            premiumStatusDrawable.setColor(statusColor);
            premiumStatusDrawable.setParticles(DialogObject.isEmojiStatusCollectible(user.emoji_status), false);
            nameView.setRightDrawable(rightDrawable);

            unreadBadge.bind(account, nameView);
            checkPaint.setColor(Theme.getColor(COLOR_KEY_ACCENT));

            selected = account == UserConfig.selectedAccount;
            final float scale = selected ? 0.785f : 1.0f;
            avatarView.setScaleX(scale);
            avatarView.setScaleY(scale);
            setBackground(selected ? createSelectedAccountBackgroundDrawable() : createAccountItemRippleDrawable());
            setPadding(0, 0, 0, 0);
            invalidate();
        }

        public void updateUnreadCounter() {
            unreadBadge.update(nameView);
            invalidate();
        }

        private void updateAvatarRadius() {
            avatarView.getImageReceiver().setAvatarCornersApplied(true);
            avatarView.setRoundRadius(AppearanceConfig.getAvatarCorners(AndroidUtilities.dp(34)));
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            unreadBadge.draw(this, canvas);
            if (!selected) {
                return;
            }
            final float half = checkPaint.getStrokeWidth() / 2.0f;
            avatarRect.set(avatarView.getLeft() + half, avatarView.getTop() + half,
                    avatarView.getRight() - half, avatarView.getBottom() - half);
            final float radius = AppearanceConfig.getAvatarCorners(AndroidUtilities.dp(34));
            canvas.drawRoundRect(avatarRect, radius, radius, checkPaint);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            premiumStatusDrawable.attach();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            premiumStatusDrawable.detach();
        }
    }

    public static class AddAccountView extends LinearLayout {

        private final Drawable circleDrawable;
        private final Drawable plusDrawable;
        private final SimpleTextView textView;

        public AddAccountView(Context context) {
            super(context);
            setOrientation(LinearLayout.HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.MATCH_PARENT, AndroidUtilities.dp(44.0f)));

            final ImageView imageView = new ImageView(context);
            imageView.setScaleType(ImageView.ScaleType.CENTER);
            circleDrawable = ResourcesCompat.getDrawable(context.getResources(), R.drawable.poll_add_circle, null);
            plusDrawable = ResourcesCompat.getDrawable(context.getResources(), R.drawable.poll_add_plus, null);
            if (circleDrawable != null) {
                circleDrawable.mutate();
            }
            if (plusDrawable != null) {
                plusDrawable.mutate();
            }
            final CombinedDrawable combinedDrawable = new CombinedDrawable(circleDrawable, plusDrawable) {
                @Override
                public void setColorFilter(ColorFilter colorFilter) {
                }
            };
            combinedDrawable.setCustomSize(AndroidUtilities.dp(24.0f), AndroidUtilities.dp(24.0f));
            imageView.setImageDrawable(combinedDrawable);
            addView(imageView, LayoutHelper.createLinear(34, 34, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

            textView = new SimpleTextView(context);
            textView.setTextSize(15);
            textView.setTypeface(AndroidUtilities.bold());
            textView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            textView.setText(LocaleController.getString(R.string.AddAccount));
            addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT,
                    Gravity.CENTER_VERTICAL, 12, 0, 12, 0));

            updateColors();
        }

        public void updateColors() {
            setBackground(createAccountItemRippleDrawable());
            if (circleDrawable != null) {
                circleDrawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(COLOR_KEY_ACCENT), PorterDuff.Mode.SRC_IN));
            }
            if (plusDrawable != null) {
                plusDrawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(COLOR_KEY_ADD_ICON), PorterDuff.Mode.SRC_IN));
            }
            textView.setTextColor(Theme.getColor(COLOR_KEY_TEXT));
        }
    }

    // ---- адаптер ----

    private class AccountAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int VIEW_TYPE_ACCOUNT = 0;
        private static final int VIEW_TYPE_ADD = 1;

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final View view = viewType == VIEW_TYPE_ADD
                    ? new AddAccountView(parent.getContext())
                    : new AccountRowView(parent.getContext());
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (getItemViewType(position) == VIEW_TYPE_ACCOUNT) {
                bindAccountViewHolder(holder, position);
            } else {
                bindAddAccountViewHolder(holder);
            }
        }

        @Override
        public int getItemCount() {
            return accounts.size() + (canAddAccount() ? 1 : 0);
        }

        @Override
        public int getItemViewType(int position) {
            return position < accounts.size() ? VIEW_TYPE_ACCOUNT : VIEW_TYPE_ADD;
        }

        private void bindAccountViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (!(holder.itemView instanceof AccountRowView)) {
                return;
            }
            final AccountRowView rowView = (AccountRowView) holder.itemView;
            final Integer account = accounts.get(position);
            rowView.bind(account);
            rowView.setOnClickListener(v -> {
                if (account == UserConfig.selectedAccount) {
                    return;
                }
                if (onAccountSelected != null) {
                    onAccountSelected.run();
                }
                final Context context = getContext();
                if (context instanceof LaunchActivity) {
                    ((LaunchActivity) context).switchToAccount(account, true);
                }
            });
            rowView.setOnLongClickListener(v -> {
                // exteraGram: текущий аккаунт тащится, остальные открывают превью.
                if (account == UserConfig.selectedAccount) {
                    itemTouchHelper.startDrag(holder);
                    return true;
                }
                if (onAccountLongClick != null) {
                    onAccountLongClick.onLongClick(account, v);
                }
                return true;
            });
        }

        private void bindAddAccountViewHolder(RecyclerView.ViewHolder holder) {
            if (holder.itemView instanceof AddAccountView) {
                ((AddAccountView) holder.itemView).updateColors();
            }
            holder.itemView.setOnClickListener(v -> {
                if (onAccountSelected != null) {
                    onAccountSelected.run();
                }
                AndroidUtilities.runOnUIThread(DrawerAccountPickerView.this::openAddAccountFlow, 150L);
            });
        }

        /** Порядок хранится в {@code loginTime}. */
        void swapElements(int from, int to) {
            if (from < 0 || to < 0 || from >= accounts.size() || to >= accounts.size()) {
                return;
            }
            final UserConfig first = UserConfig.getInstance(accounts.get(from));
            final UserConfig second = UserConfig.getInstance(accounts.get(to));
            final int loginTime = first.loginTime;
            first.loginTime = second.loginTime;
            second.loginTime = loginTime;
            first.saveConfig(false);
            second.saveConfig(false);
            Collections.swap(accounts, from, to);
            notifyItemMoved(from, to);
        }
    }
}
