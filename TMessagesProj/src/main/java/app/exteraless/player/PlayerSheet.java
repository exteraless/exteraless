package app.exteraless.player;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Shader;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.transition.ChangeBounds;
import android.transition.TransitionManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.audioinfo.AudioInfo;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AudioPlayerAlert;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

import app.exteraless.appearance.AppearanceConfig;

public class PlayerSheet extends BottomSheet implements NotificationCenter.NotificationCenterDelegate {

    public static PlayerSheet instance;

    private static final float[] SPEEDS = {1f, 1.2f, 1.5f, 1.7f, 2f, 0.5f};
    private static final CubicBezierInterpolator EMPHASIZED = new CubicBezierInterpolator(0.2, 0, 0, 1);
    private static final PathInterpolator BACK_GESTURE = new PathInterpolator(0.1f, 0.1f, 0f, 1f);

    private final LaunchActivity activity;
    private final int account;
    private final boolean dark;
    private final int fallbackSeed;
    private final FrameLayout root;
    private final PlayerLayout layout;
    private final FrameLayout header;
    private final ImageView collapseButton;
    private final ImageView moreButton;
    private final PlayerIcon collapseIcon = PlayerIcon.stroke(PlayerIcon.CHEVRON_DOWN, 24);
    private final PlayerIcon moreIcon = PlayerIcon.fill(PlayerIcon.MORE, 24);
    private final TextView headerLabel;
    private final TextView headerTitle;
    private final CoverImage cover;
    private final LinearLayout titleRow;
    private final TextView titleView;
    private final TextView artistView;
    private final MorphButton likeButton;
    private final PlayerIcon heartIcon = PlayerIcon.stroke(PlayerIcon.HEART, 24);
    private final LinearLayout lyricsPanel;
    private final CoverImage smallCover;
    private final TextView smallTitle;
    private final TextView smallArtist;
    private final LyricsView lyricsView;
    private final TextView sourceView;
    private final WavySeekBar seekBar;
    private final TextView bubble;
    private final GradientDrawable bubbleBg = new GradientDrawable();
    private final LinearLayout timeRow;
    private final TextView timeNow;
    private final TextView timeLeft;
    private final ControlsRow controls;
    private final LinearLayout group;
    private final MorphButton speedButton;
    private final MorphButton lyricsButton;
    private final MorphButton queueButton;
    private final LinearLayout headerCenter;
    private final LinearLayout titles;
    private final View titleSpacer;
    private final CoverScrim coverScrim;
    private final boolean previewMode;
    private FrameLayout editorPanel;
    private PlayerStyleEditor styleEditor;
    private final GradientDrawable editorBackground = new GradientDrawable();
    private final GradientDrawable editorDoneBackground = new GradientDrawable();
    private TextView editorTitle;
    private TextView editorReset;
    private TextView editorDone;
    private boolean editing;
    private boolean islandActions;
    private final GradientDrawable islandBackground = new GradientDrawable();
    private float editorFraction;
    private float editScale = 1f;
    private float editTop;
    private ValueAnimator editorAnimator;
    private int coverStyle = PlayerStyle.COVER_CARD;
    private int[] blockOrder = PlayerStyle.order();
    private ValueAnimator spinAnimator;
    private Bitmap backdrop;
    private String backdropKey;
    private LinearGradient backgroundGradient;
    private int backgroundGradientKey;
    private final Paint backdropPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect backdropSrc = new Rect();
    private final RectF previewRect = new RectF();
    private final Path previewPath = new Path();
    private final Bulletin.Delegate bulletinDelegate = new Bulletin.Delegate() {
        @Override
        public int getBottomOffset(int tag) {
            return getBottomInset();
        }
    };

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF morphFrom = new RectF();
    private final RectF morphTo = new RectF();
    private final RectF morphCoverFrom = new RectF();
    private final RectF morphCoverTo = new RectF();
    private final RectF morphCoverBase = new RectF();
    private final RectF morphRect = new RectF();
    private final RectF morphCover = new RectF();
    private final Rect morphSrc = new Rect();
    private final Path morphPath = new Path();
    private final Paint morphPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private PlayerMiniView miniSource;
    private View morphCoverView;
    private float morphCoverBaseRadius;
    private boolean backPreview;
    private float backProgress;
    private int backDirection;
    private ValueAnimator backAnimator;
    private boolean skipMorph;
    private boolean closing;
    private boolean detached;
    private ValueAnimator morphAnimator;
    private float morphProgress = -1f;
    private float morphFromRadius;
    private float morphToRadius;
    private float morphCoverFromRadius;
    private float morphCoverToRadius;
    private int morphFromColor;
    private Bitmap morphSnapshot;
    private Bitmap morphCoverBitmap;
    private int morphBarsState = -1;
    private boolean underLightStatus;
    private boolean underLightNav;
    private final RectF backgroundRect = new RectF();
    private PlayerColors colors;
    private ValueAnimator colorAnimator;
    private MessageObject current;
    private String currentKey;
    private boolean lyricsMode;
    private float lyricsFraction;
    private boolean landscape;
    private int columnLeft;
    private ValueAnimator lyricsAnimator;
    private String lyricsKey;
    private Lyrics fileLyrics;
    private boolean seeking;
    private float seekProgress;
    private Boolean lightBars;
    private boolean touchInLyrics;
    private float dragStartX;
    private float dragStartY;
    private boolean dragTracking;
    private boolean dragActive;
    private boolean coverAnimating;
    private boolean morphUnderlay;
    private boolean morphAnchorBottom = true;
    private boolean insetsReady;
    private final int touchSlop;

    public PlayerSheet(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, false, resourcesProvider);
        Activity found = AndroidUtilities.findActivity(context);
        activity = found instanceof LaunchActivity ? (LaunchActivity) found : LaunchActivity.instance;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        occupyNavigationBar = true;
        drawNavigationBar = false;
        fullWidth = true;
        setApplyTopPadding(false);
        setApplyBottomPadding(false);

        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        previewMode = playing == null || !playing.isMusic();
        if (previewMode) {
            playing = null;
        }
        account = playing != null ? playing.currentAccount : UserConfig.selectedAccount;
        currentAccount = account;
        dark = PlayerColors.isDark(resourcesProvider);
        fallbackSeed = PlayerColors.noCoverSeed(resourcesProvider);
        Integer seed = PlayerStyle.themeColors() ? null : PlayerArt.cachedSeed(playing);
        colors = PlayerColors.fromSeed(seed != null ? seed : fallbackSeed, dark);

        root = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.EXACTLY));
            }

            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                if (morphProgress >= 0f || closing) {
                    return true;
                }
                int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    touchInLyrics = lyricsMode && lyricsView.getVisibility() == VISIBLE && hitInRoot(lyricsView, ev.getX(), ev.getY());
                }
                boolean result = super.dispatchTouchEvent(ev);
                if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && touchInLyrics) {
                    touchInLyrics = false;
                    container.requestDisallowInterceptTouchEvent(false);
                }
                return result;
            }

            @Override
            public void setTranslationY(float translationY) {
                super.setTranslationY(translationY);
                invalidate();
            }

            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                if (morphProgress < 0f) {
                    super.dispatchDraw(canvas);
                    return;
                }
                drawMorphBackground(canvas);
                if (morphUnderlay) {
                    drawMorphSnapshot(canvas);
                    drawMorphBackdrop(canvas);
                    drawMorphCover(canvas);
                    super.dispatchDraw(canvas);
                    canvas.restore();
                } else {
                    super.dispatchDraw(canvas);
                    drawMorphSnapshot(canvas);
                    canvas.restore();
                    if (!backPreview) {
                        drawMorphCover(canvas);
                    }
                }
            }

            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                if (morphProgress >= 0f) {
                    return;
                }
                float r = dp(28) * Math.max(0f, Math.min(1f, getTranslationY() / dp(56)));
                backgroundPaint.setColor(editorFraction > 0f ? ColorUtils.blendARGB(colors.surface, colors.surfaceLow, editorFraction) : colors.surface);
                backgroundRect.set(0, 0, getWidth(), getHeight() + r);
                canvas.drawRoundRect(backgroundRect, r, r, backgroundPaint);
            }

            @Override
            protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                super.onSizeChanged(w, h, oldw, oldh);
                if (editorPanel != null) {
                    post(() -> {
                        measureEditor();
                        setEditorFraction(editorFraction);
                    });
                }
            }

            @Override
            protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                Bulletin.addDelegate(this, bulletinDelegate);
            }

            @Override
            protected void onDetachedFromWindow() {
                super.onDetachedFromWindow();
                Bulletin.removeDelegate(this);
            }
        };
        root.setWillNotDraw(false);
        containerView = root;

        layout = new PlayerLayout(context);
        root.addView(layout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        header = new FrameLayout(context);
        collapseButton = new ImageView(context);
        collapseButton.setScaleType(ImageView.ScaleType.CENTER);
        collapseButton.setImageDrawable(collapseIcon);
        collapseButton.setContentDescription(getString(R.string.Close));
        collapseButton.setOnClickListener(v -> dismiss());
        header.addView(collapseButton, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL));
        moreButton = new ImageView(context);
        moreButton.setScaleType(ImageView.ScaleType.CENTER);
        moreButton.setImageDrawable(moreIcon);
        moreButton.setContentDescription(getString(R.string.AccDescrMoreOptions));
        moreButton.setOnClickListener(this::showMenu);
        header.addView(moreButton, LayoutHelper.createFrame(48, 48, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        headerCenter = new LinearLayout(context);
        headerCenter.setOrientation(LinearLayout.VERTICAL);
        headerCenter.setGravity(Gravity.CENTER_HORIZONTAL);
        headerLabel = new TextView(context);
        headerLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        headerLabel.setLetterSpacing(0.08f);
        headerLabel.setAllCaps(true);
        headerLabel.setSingleLine(true);
        headerLabel.setEllipsize(TextUtils.TruncateAt.END);
        headerCenter.addView(headerLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        headerTitle = new TextView(context);
        headerTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        headerTitle.setTypeface(AndroidUtilities.bold());
        headerTitle.setSingleLine(true);
        headerTitle.setEllipsize(TextUtils.TruncateAt.END);
        headerCenter.addView(headerTitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 2, 0, 0));
        header.addView(headerCenter, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 56, 0, 56, 0));
        layout.addView(header);

        cover = new CoverImage(context, 120);
        cover.setRadius(dp(28));
        cover.setListener(fallbackSeed, this::onSeed);
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cover.getRadius());
            }
        });
        cover.setElevation(dp(14));
        cover.setOnTouchListener(this::onCoverTouch);
        layout.addView(cover);

        coverScrim = new CoverScrim(context);
        coverScrim.setVisibility(View.GONE);
        layout.addView(coverScrim);

        titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleSpacer = new View(context);
        titleSpacer.setVisibility(View.GONE);
        titleRow.addView(titleSpacer, LayoutHelper.createLinear(60, 1));
        titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setSingleLine(true);
        titleView.setHorizontallyScrolling(false);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        artistView = new TextView(context);
        artistView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        artistView.setSingleLine(true);
        artistView.setHorizontallyScrolling(false);
        artistView.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(artistView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        titleRow.addView(titles, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        likeButton = new MorphButton(context, heartIcon);
        likeButton.setRadius(dp(24), false);
        likeButton.setOnClickListener(v -> toggleProfile());
        titleRow.addView(likeButton, LayoutHelper.createLinear(48, 48, Gravity.CENTER_VERTICAL, 12, 0, 0, 0));
        layout.addView(titleRow);

        lyricsPanel = new LinearLayout(context);
        lyricsPanel.setOrientation(LinearLayout.VERTICAL);
        lyricsPanel.setVisibility(View.GONE);
        LinearLayout smallRow = new LinearLayout(context);
        smallRow.setOrientation(LinearLayout.HORIZONTAL);
        smallRow.setGravity(Gravity.CENTER_VERTICAL);
        smallCover = new CoverImage(context, 28);
        smallCover.setRadius(dp(16));
        smallRow.addView(smallCover, LayoutHelper.createLinear(56, 56));
        LinearLayout smallTitles = new LinearLayout(context);
        smallTitles.setOrientation(LinearLayout.VERTICAL);
        smallTitle = new TextView(context);
        smallTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        smallTitle.setTypeface(AndroidUtilities.bold());
        smallTitle.setSingleLine(true);
        smallTitle.setEllipsize(TextUtils.TruncateAt.END);
        smallTitles.addView(smallTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        smallArtist = new TextView(context);
        smallArtist.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        smallArtist.setSingleLine(true);
        smallArtist.setEllipsize(TextUtils.TruncateAt.END);
        smallTitles.addView(smallArtist, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        smallRow.addView(smallTitles, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 14, 0, 0, 0));
        lyricsPanel.addView(smallRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
        lyricsView = new LyricsView(context);
        lyricsView.setDelegate(new LyricsView.Delegate() {
            @Override
            public void onAction(int state) {
                if (state == LyricsView.STATE_OFFER) {
                    AppearanceConfig.lrclibAllowed.setConfigBool(true);
                }
                fetchLyrics();
            }

            @Override
            public void onSeek(long ms) {
                MessageObject mo = current;
                if (mo != null) {
                    MediaController.getInstance().seekToProgressMs(mo, ms);
                }
            }
        });
        lyricsPanel.addView(lyricsView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f, 0, 16, 0, 0));
        sourceView = new TextView(context);
        sourceView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        sourceView.setSingleLine(true);
        lyricsPanel.addView(sourceView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 16, 0, 8, 0, 0));
        layout.addView(lyricsPanel);

        seekBar = new WavySeekBar(context);
        seekBar.setContentDescription(getString(R.string.OEPlayerSeek));
        seekBar.setDelegate(new WavySeekBar.Delegate() {
            @Override
            public void onSeekStart() {
                seeking = true;
                bubble.animate().cancel();
                bubble.setVisibility(View.VISIBLE);
                bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start();
            }

            @Override
            public void onSeekMove(float progress) {
                seekProgress = progress;
                updateTimes();
                layoutBubble();
            }

            @Override
            public void onSeekEnd(float progress, boolean commit) {
                seeking = false;
                MessageObject mo = current;
                if (commit && mo != null) {
                    MediaController.getInstance().seekToProgress(mo, progress);
                    mo.audioProgress = progress;
                    mo.audioProgressSec = (int) (mo.getDuration() * progress);
                }
                bubble.animate().cancel();
                bubble.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(150).withEndAction(() -> bubble.setVisibility(View.GONE)).start();
                updateProgress();
            }
        });
        layout.addView(seekBar);

        timeRow = new LinearLayout(context);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeNow = new TextView(context);
        timeNow.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        timeNow.setFontFeatureSettings("tnum");
        timeRow.addView(timeNow, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        timeLeft = new TextView(context);
        timeLeft.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        timeLeft.setFontFeatureSettings("tnum");
        timeLeft.setGravity(Gravity.RIGHT);
        timeRow.addView(timeLeft, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        layout.addView(timeRow);

        controls = new ControlsRow(context);
        controls.shuffle.setOnClickListener(v -> {
            MediaController.getInstance().setPlaybackOrderType(SharedConfig.shuffleMusic || SharedConfig.playOrderReversed ? 0 : 2);
            updateModes(true);
        });
        controls.repeat.setOnClickListener(v -> {
            int mode = SharedConfig.repeatMode;
            SharedConfig.setRepeatMode(mode == 0 ? 1 : mode == 1 ? 2 : 0);
            updateModes(true);
        });
        controls.shuffle.setOnLongClickListener(v -> {
            showPlaybackOptions(v);
            return true;
        });
        controls.repeat.setOnLongClickListener(v -> {
            showPlaybackOptions(v);
            return true;
        });
        controls.prev.setOnClickListener(v -> MediaController.getInstance().playPreviousMessage());
        controls.next.setOnClickListener(v -> MediaController.getInstance().playNextMessage());
        controls.play.setOnClickListener(v -> togglePlay());
        controls.shuffle.setContentDescription(getString(R.string.ShuffleList));
        controls.prev.setContentDescription(getString(R.string.AccDescrPrevious));
        controls.next.setContentDescription(getString(R.string.Next));
        layout.addView(controls);

        group = new LinearLayout(context);
        group.setOrientation(LinearLayout.HORIZONTAL);
        speedButton = new MorphButton(context, PlayerIcon.stroke(PlayerIcon.SPEED, 20));
        speedButton.setRadius(dp(26), dp(8), false);
        speedButton.setOnClickListener(v -> cycleSpeed());
        group.addView(speedButton, LayoutHelper.createLinear(0, 52, 1f));
        lyricsButton = new MorphButton(context, PlayerIcon.stroke(PlayerIcon.LYRICS, 20));
        lyricsButton.setText(getString(R.string.OEPlayerLyrics));
        lyricsButton.setRadius(dp(8), false);
        lyricsButton.setOnClickListener(v -> setLyricsMode(!lyricsMode, true));
        group.addView(lyricsButton, LayoutHelper.createLinear(0, 52, 1f, 4, 0, 4, 0));
        queueButton = new MorphButton(context, PlayerIcon.stroke(PlayerIcon.QUEUE, 20));
        queueButton.setText(getString(R.string.OEPlayerQueue));
        queueButton.setRadius(dp(8), dp(26), false);
        queueButton.setOnClickListener(v -> openQueue());
        group.addView(queueButton, LayoutHelper.createLinear(0, 52, 1f));
        layout.addView(group);

        bubble = new TextView(context);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        bubble.setTypeface(AndroidUtilities.bold());
        bubble.setFontFeatureSettings("tnum");
        bubble.setPadding(dp(12), dp(6), dp(12), dp(6));
        bubbleBg.setCornerRadius(dp(16));
        bubble.setBackground(bubbleBg);
        bubble.setVisibility(View.GONE);
        bubble.setAlpha(0f);
        layout.addView(bubble);

        applyStyle(false);
        applyColors(colors);
        if (previewMode) {
            bindPreview();
        } else {
            bind(playing, false);
        }
    }

    private boolean hitInRoot(View view, float x, float y) {
        float left = 0;
        float top = 0;
        View v = view;
        while (v != null && v != root) {
            left += v.getLeft() + v.getTranslationX();
            top += v.getTop() + v.getTranslationY();
            if (!(v.getParent() instanceof View)) {
                return false;
            }
            v = (View) v.getParent();
        }
        return v == root && x >= left && x < left + view.getWidth() && y >= top && y < top + view.getHeight();
    }

    @Override
    protected boolean canDismissWithSwipe() {
        if (morphProgress >= 0f || closing || editing || editorFraction > 0f) {
            return false;
        }
        return !touchInLyrics || !lyricsView.canScrollUp();
    }

    @Override
    public void show() {
        super.show();
        instance = this;
        NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.addObserver(this, NotificationCenter.messagePlayingDidReset);
        nc.addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        nc.addObserver(this, NotificationCenter.messagePlayingDidStart);
        nc.addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        nc.addObserver(this, NotificationCenter.fileLoaded);
        nc.addObserver(this, NotificationCenter.audioInfoLoaded);
        nc.addObserver(this, NotificationCenter.musicIdsLoaded);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.messagePlayingSpeedChanged);
        applySystemBars(true);
        if (miniSource != null && startAnimationRunnable != null) {
            root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    Runnable start = startAnimationRunnable;
                    if (start != null && !isDismissed() && (!insetsReady || root.getWidth() == 0)) {
                        return true;
                    }
                    root.getViewTreeObserver().removeOnPreDrawListener(this);
                    if (start != null && !isDismissed()) {
                        AndroidUtilities.cancelRunOnUIThread(start);
                        start.run();
                    }
                    return true;
                }
            });
        }
    }

    @Override
    protected void onInsetsChanged() {
        insetsReady = true;
        padEditorPanel();
    }

    private void padEditorPanel() {
        if (editorPanel != null) {
            editorPanel.setPadding(super.getLeftInset(), 0, super.getRightInset(), 0);
        }
    }

    @Override
    public int getLeftInset() {
        return 0;
    }

    @Override
    public int getRightInset() {
        return 0;
    }

    public void setTransitionSource(PlayerMiniView source) {
        miniSource = source;
    }

    public static void openStyleEditor(org.telegram.ui.ActionBar.BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        if (instance != null && !instance.isDismissed()) {
            instance.openEditor();
            return;
        }
        PlayerSheet sheet = new PlayerSheet(fragment.getParentActivity(), fragment.getResourceProvider());
        fragment.showDialog(sheet);
        sheet.root.post(sheet::openEditor);
    }

    public void dismissImmediately() {
        skipMorph = true;
        dismiss();
    }

    @Override
    public void dismiss() {
        if (closing) {
            return;
        }
        if (editing && !skipMorph && !isDismissed()) {
            closeEditor();
            return;
        }
        if (!skipMorph && miniSource != null && !isDismissed()) {
            if (morphProgress >= 0f) {
                boolean preview = backPreview;
                closing = true;
                detach();
                cancelBackAnimator();
                if (preview) {
                    backPreview = false;
                    morphCoverView.setVisibility(View.INVISIBLE);
                    setMorphUnderlay(morphCoverView == cover && coverIsBackdrop());
                }
                startMorph(morphProgress, 0f, preview ? 380 : 300, this::finishMorphClose);
                return;
            }
            float ty = root.getTranslationY();
            root.setTranslationY(0);
            if (prepareMorph(true)) {
                closing = true;
                detach();
                cancelSheetAnimation();
                morphTo.set(0, ty, root.getWidth(), root.getHeight() + ty);
                morphToRadius = dp(28) * clamp01(ty / dp(56));
                morphCoverTo.offset(0, ty);
                startMorph(1f, 0f, 380, this::finishMorphClose);
                return;
            }
            root.setTranslationY(ty);
        }
        detach();
        if (morphAnimator != null) {
            morphAnimator.removeAllListeners();
            morphAnimator.cancel();
            morphAnimator = null;
        }
        if (morphProgress >= 0f) {
            cancelBackAnimator();
            backPreview = false;
            restoreMorphCover();
            clearMorph();
        }
        if (miniSource != null) {
            miniSource.setTransitionHidden(false);
        }
        super.dismiss();
    }

    private void finishMorphClose() {
        if (miniSource != null) {
            miniSource.setTransitionHidden(false);
        }
        root.setVisibility(View.INVISIBLE);
        backDrawable.setAlpha(0);
        skipDismissAnimation();
        super.dismiss();
    }

    @Override
    public void dismissInternal() {
        if (miniSource != null) {
            miniSource.setTransitionHidden(false);
        }
        super.dismissInternal();
    }

    @Override
    protected boolean onCustomOpenAnimation() {
        root.setTranslationY(0);
        if (!prepareMorph(true)) {
            return false;
        }
        morphTo.set(0, 0, root.getWidth(), root.getHeight());
        morphToRadius = 0;
        startMorph(0f, 1f, 420, () -> {
            clearMorph();
            restoreMorphCover();
            cover.setTranslationZ(-cover.getElevation());
            cover.animate().translationZ(0).setDuration(250).start();
            morphBarsState = -1;
            applySystemBars(true);
            onOpenAnimationEnd();
            if (delegate != null) {
                delegate.onOpenAnimationEnd();
            }
        });
        return true;
    }

    @Override
    protected boolean onCustomBackStarted(int swipeDirection) {
        if (editing) {
            return true;
        }
        if (miniSource == null || skipMorph) {
            return false;
        }
        if (closing || (morphProgress >= 0f && !backPreview)) {
            return true;
        }
        if (backPreview) {
            cancelBackAnimator();
        } else if (root.getTranslationY() != 0f || !prepareMorph(false)) {
            return false;
        } else {
            morphCoverBase.set(morphCoverTo);
            morphCoverBaseRadius = morphCoverToRadius;
            morphProgress = 1f;
            backProgress = 0f;
            backPreview = true;
        }
        backDirection = swipeDirection;
        applyBackPreview();
        return true;
    }

    @Override
    protected void onCustomBackProgressed(float progress) {
        if (editing || !backPreview || closing) {
            return;
        }
        cancelBackAnimator();
        backProgress = progress;
        applyBackPreview();
    }

    @Override
    protected void onCustomBackCancelled() {
        if (editing || !backPreview || closing) {
            return;
        }
        cancelBackAnimator();
        backAnimator = ValueAnimator.ofFloat(backProgress, 0f);
        backAnimator.addUpdateListener(a -> {
            backProgress = (float) a.getAnimatedValue();
            applyBackPreview();
        });
        backAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                backAnimator = null;
                backPreview = false;
                clearMorph();
                if (miniSource != null) {
                    miniSource.setTransitionHidden(false);
                }
                morphBarsState = -1;
                applySystemBars(true);
            }
        });
        backAnimator.setDuration((long) (250 * clamp01(backProgress)));
        backAnimator.setInterpolator(CubicBezierInterpolator.DEFAULT);
        backAnimator.start();
    }

    private void applyBackPreview() {
        float t = BACK_GESTURE.getInterpolation(clamp01(backProgress));
        float w = root.getWidth();
        float h = root.getHeight();
        float s = 1f - 0.1f * t;
        float inset = (w - w * s) / 2f;
        float left = inset + Math.max(0f, inset - dp(8)) * backDirection;
        float top = Math.max(h / 2f, Math.min(h, morphFrom.centerY())) * (1f - s);
        morphTo.set(left, top, left + w * s, top + h * s);
        morphToRadius = dp(28) * t;
        morphCoverTo.set(left + morphCoverBase.left * s, top + morphCoverBase.top * s, left + morphCoverBase.right * s, top + morphCoverBase.bottom * s);
        morphCoverToRadius = morphCoverBaseRadius * s;
        applyMorph();
    }

    private void cancelBackAnimator() {
        if (backAnimator != null) {
            backAnimator.removeAllListeners();
            backAnimator.cancel();
            backAnimator = null;
        }
    }

    private void restoreMorphCover() {
        if (morphCoverView == cover) {
            cover.setVisibility(lyricsFraction >= 1f && coverStyle != PlayerStyle.COVER_FULL ? View.INVISIBLE : View.VISIBLE);
        } else if (morphCoverView != null) {
            morphCoverView.setVisibility(View.VISIBLE);
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static void lerpRect(RectF a, RectF b, float t, RectF out) {
        out.set(a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t, a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t);
    }

    private void rectInRoot(View view, RectF out) {
        float x = 0;
        float y = 0;
        View v = view;
        while (v != null && v != root) {
            x += v.getLeft() + v.getTranslationX();
            y += v.getTop() + v.getTranslationY();
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        float px = view.getPivotX();
        float py = view.getPivotY();
        float sx = view.getScaleX();
        float sy = view.getScaleY();
        out.set(x + px * (1f - sx), y + py * (1f - sy), x + px + (view.getWidth() - px) * sx, y + py + (view.getHeight() - py) * sy);
    }

    private boolean prepareMorph(boolean hideCover) {
        PlayerMiniView mini = miniSource;
        if (skipMorph || editing || editorFraction > 0f || mini == null || !mini.canTransition() || root.getWidth() == 0 || !root.isAttachedToWindow()) {
            return false;
        }
        if (lyricsAnimator != null && lyricsAnimator.isRunning()) {
            lyricsAnimator.end();
        }
        View coverView = lyricsFraction >= 0.5f ? smallCover : cover;
        morphCoverView = coverView;
        int[] loc = new int[2];
        root.getLocationOnScreen(loc);
        mini.getCardRect(morphFrom);
        morphFrom.offset(-loc[0], -loc[1]);
        morphAnchorBottom = morphFrom.centerY() > root.getHeight() / 2f;
        mini.getCoverRect(morphCoverFrom);
        morphCoverFrom.offset(-loc[0], -loc[1]);
        morphFromRadius = mini.getCardRadius();
        morphCoverFromRadius = mini.getCoverRadius();
        morphFromColor = mini.getCardColor();
        recycleSnapshot();
        morphSnapshot = mini.captureCard();
        Bitmap bitmap = coverView instanceof CoverImage ? ((CoverImage) coverView).getImageReceiver().getBitmap() : null;
        morphCoverBitmap = bitmap != null ? bitmap : mini.getCoverBitmap();
        rectInRoot(coverView, morphCoverTo);
        morphCoverToRadius = coverView == cover ? cover.getRadius() * cover.getScaleX() : dp(16);
        if (activity != null && activity.getWindow() != null) {
            int flags = activity.getWindow().getDecorView().getSystemUiVisibility();
            underLightStatus = (flags & View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0;
            underLightNav = (flags & View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) != 0;
        }
        morphBarsState = -1;
        mini.setTransitionHidden(true);
        if (hideCover) {
            coverView.setVisibility(View.INVISIBLE);
        }
        setMorphUnderlay(hideCover && coverView == cover && coverIsBackdrop());
        seekBar.setFrozen(true);
        if (spinAnimator != null) {
            spinAnimator.pause();
        }
        layout.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        layout.buildLayer();
        return true;
    }

    private void startMorph(float from, float to, long duration, Runnable onEnd) {
        if (morphAnimator != null) {
            morphAnimator.removeAllListeners();
            morphAnimator.cancel();
        }
        morphProgress = from;
        applyMorph();
        morphAnimator = ValueAnimator.ofFloat(from, to);
        morphAnimator.addUpdateListener(a -> {
            morphProgress = (float) a.getAnimatedValue();
            applyMorph();
        });
        morphAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                morphAnimator = null;
                onEnd.run();
            }
        });
        morphAnimator.setDuration(duration);
        morphAnimator.setInterpolator(EMPHASIZED);
        morphAnimator.start();
    }

    private void applyMorph() {
        float p = morphProgress;
        lerpRect(morphFrom, morphTo, p, morphRect);
        float s = morphRect.width() / Math.max(1, root.getWidth());
        layout.setPivotX(0);
        layout.setPivotY(0);
        layout.setScaleX(s);
        layout.setScaleY(s);
        layout.setTranslationX(morphRect.left);
        layout.setTranslationY(morphAnchorBottom ? morphRect.bottom - layout.getHeight() * s : morphRect.top);
        layout.setAlpha(clamp01(p / 0.2f));
        backDrawable.setAlpha(dimBehind ? (int) (dimBehindAlpha * clamp01(p)) : 0);
        updateMorphBars();
        root.invalidate();
    }

    private void clearMorph() {
        morphProgress = -1f;
        layout.setScaleX(1f);
        layout.setScaleY(1f);
        layout.setTranslationX(0);
        layout.setTranslationY(0);
        layout.setAlpha(1f);
        layout.setLayerType(View.LAYER_TYPE_NONE, null);
        setMorphUnderlay(false);
        seekBar.setFrozen(false);
        if (spinAnimator != null) {
            spinAnimator.resume();
        }
        recycleSnapshot();
        morphCoverBitmap = null;
        root.invalidate();
    }

    private boolean coverIsBackdrop() {
        return coverStyle != PlayerStyle.COVER_CARD && coverStyle != PlayerStyle.COVER_DISC;
    }

    private void setMorphUnderlay(boolean value) {
        if (morphUnderlay == value) {
            return;
        }
        morphUnderlay = value;
        coverScrim.setVisibility(value ? View.INVISIBLE : View.VISIBLE);
        layout.invalidate();
    }

    private void recycleSnapshot() {
        if (morphSnapshot != null) {
            morphSnapshot.recycle();
            morphSnapshot = null;
        }
    }

    private void updateMorphBars() {
        int state = morphProgress > 0.5f ? 1 : 0;
        if (state == morphBarsState) {
            return;
        }
        morphBarsState = state;
        if (state == 1) {
            boolean light = colors.lightStatusBar();
            setBars(light, light);
        } else {
            setBars(underLightStatus, underLightNav);
        }
    }

    private void drawMorphBackground(Canvas canvas) {
        float p = morphProgress;
        lerpRect(morphFrom, morphTo, p, morphRect);
        float r = morphFromRadius + (morphToRadius - morphFromRadius) * p;
        morphPath.rewind();
        morphPath.addRoundRect(morphRect, r, r, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(morphPath);
        backgroundPaint.setColor(ColorUtils.blendARGB(morphFromColor, colors.surface, clamp01(p / 0.5f)));
        canvas.drawRect(morphRect, backgroundPaint);
    }

    private void drawMorphSnapshot(Canvas canvas) {
        Bitmap snapshot = morphSnapshot;
        if (snapshot == null || snapshot.isRecycled()) {
            return;
        }
        float a = 1f - clamp01(morphProgress / 0.2f);
        if (a <= 0f) {
            return;
        }
        canvas.save();
        float s = morphRect.width() / snapshot.getWidth();
        canvas.translate(morphRect.left, morphAnchorBottom ? morphRect.bottom - snapshot.getHeight() * s : morphRect.top);
        canvas.scale(s, s);
        morphPaint.setAlpha((int) (255 * a));
        canvas.drawBitmap(snapshot, 0, 0, morphPaint);
        canvas.restore();
    }

    private void drawMorphBackdrop(Canvas canvas) {
        float alpha = layout.getAlpha();
        if (alpha <= 0f || coverStyle == PlayerStyle.COVER_FULL || PlayerStyle.background() == PlayerStyle.BACKGROUND_SOLID) {
            return;
        }
        int w = layout.getWidth();
        int h = layout.getHeight();
        int count = canvas.save();
        canvas.translate(layout.getLeft() + layout.getTranslationX(), layout.getTop() + layout.getTranslationY());
        canvas.scale(layout.getScaleX(), layout.getScaleY());
        if (alpha < 1f) {
            canvas.saveLayerAlpha(0, 0, w, h, (int) (255 * alpha));
        }
        drawBackdrop(canvas, w, h);
        canvas.restoreToCount(count);
    }

    private void drawMorphCover(Canvas canvas) {
        float p = morphProgress;
        lerpRect(morphCoverFrom, morphCoverTo, p, morphCover);
        float r = morphCoverFromRadius + (morphCoverToRadius - morphCoverFromRadius) * p;
        morphPath.rewind();
        morphPath.addRoundRect(morphCover, r, r, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(morphPath);
        Bitmap bitmap = morphCoverBitmap;
        if (bitmap != null && !bitmap.isRecycled() && bitmap.getWidth() > 0 && bitmap.getHeight() > 0) {
            int bw = bitmap.getWidth();
            int bh = bitmap.getHeight();
            float ratio = morphCover.width() / Math.max(1f, morphCover.height());
            if (bw / (float) bh > ratio) {
                int w = (int) (bh * ratio);
                int x = (bw - w) / 2;
                morphSrc.set(x, 0, x + w, bh);
            } else {
                int h = (int) (bw / ratio);
                int y = (bh - h) / 2;
                morphSrc.set(0, y, bw, y + h);
            }
            morphPaint.setAlpha(255);
            canvas.drawBitmap(bitmap, morphSrc, morphCover, morphPaint);
        } else {
            backgroundPaint.setColor(colors.primaryContainer);
            canvas.drawRect(morphCover, backgroundPaint);
        }
        if (morphUnderlay) {
            coverScrim.drawMorph(canvas, morphCover, clamp01(p));
        }
        canvas.restore();
        if (morphUnderlay) {
            coverScrim.drawMorphExtension(canvas, morphCover, clamp01(p));
        }
    }

    private void detach() {
        if (detached) {
            return;
        }
        detached = true;
        if (instance == this) {
            instance = null;
        }
        NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.removeObserver(this, NotificationCenter.messagePlayingDidReset);
        nc.removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        nc.removeObserver(this, NotificationCenter.messagePlayingDidStart);
        nc.removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        nc.removeObserver(this, NotificationCenter.fileLoaded);
        nc.removeObserver(this, NotificationCenter.audioInfoLoaded);
        nc.removeObserver(this, NotificationCenter.musicIdsLoaded);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.messagePlayingSpeedChanged);
        if (colorAnimator != null) {
            colorAnimator.cancel();
        }
        if (spinAnimator != null) {
            spinAnimator.cancel();
            spinAnimator = null;
        }
    }

    public static void onModesChanged() {
        if (instance != null) {
            instance.updateModes(true);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagePlayingDidStart) {
            MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            if (mo == null || !mo.isMusic()) {
                dismissImmediately();
                return;
            }
            bind(mo, true);
        } else if (id == NotificationCenter.messagePlayingDidReset) {
            MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            if (mo == null || !mo.isMusic()) {
                dismissImmediately();
            } else {
                bind(mo, true);
            }
        } else if (id == NotificationCenter.messagePlayingPlayStateChanged) {
            updatePlayState(true);
            updateProgress();
        } else if (id == NotificationCenter.messagePlayingProgressDidChanged) {
            updateProgress();
        } else if (id == NotificationCenter.audioInfoLoaded) {
            MessageObject mo = current;
            if (mo != null && PlayerArt.isPlaying(mo)) {
                cover.setMessage(mo);
                smallCover.setMessage(mo);
                if (lyricsMode && lyricsView.getState() != LyricsView.STATE_LYRICS) {
                    lyricsKey = null;
                    loadLyrics();
                }
            }
        } else if (id == NotificationCenter.fileLoaded) {
            MessageObject mo = current;
            if (mo != null && mo.getDocument() != null && TextUtils.equals((String) args[0], FileLoader.getAttachFileName(mo.getDocument()))) {
                cover.setMessage(mo);
                smallCover.setMessage(mo);
                if (lyricsMode && (lyricsView.getState() != LyricsView.STATE_LYRICS)) {
                    lyricsKey = null;
                    loadLyrics();
                }
            }
        } else if (id == NotificationCenter.musicIdsLoaded) {
            updateLike(true);
        } else if (id == NotificationCenter.messagePlayingSpeedChanged) {
            updateSpeed();
        }
    }

    private void bind(MessageObject mo, boolean animated) {
        if (mo == null) {
            return;
        }
        if (previewMode) {
            layout.setEnabled(true);
        }
        String key = PlayerArt.key(mo);
        boolean changed = !TextUtils.equals(key, currentKey);
        current = mo;
        currentKey = key;
        titleView.setText(mo.getMusicTitle());
        artistView.setText(mo.getMusicAuthor());
        smallTitle.setText(mo.getMusicTitle());
        smallArtist.setText(mo.getMusicAuthor());
        cover.setMessage(mo);
        smallCover.setMessage(mo);
        if (changed) {
            Integer seed = PlayerStyle.themeColors() ? null : PlayerArt.cachedSeed(mo);
            if (seed != null) {
                animateColors(PlayerColors.fromSeed(seed, dark), animated);
            } else if (PlayerStyle.themeColors() || PlayerArt.fileCover(mo) == null && PlayerArt.fullLocation(mo) == null && PlayerArt.thumbLocation(mo) == null) {
                animateColors(PlayerColors.fromSeed(fallbackSeed, dark), animated);
            }
            updateBackdrop();
            lyricsKey = null;
            if (lyricsMode) {
                loadLyrics();
            }
        }
        updateHeader();
        updateLike(animated);
        updateModes(animated);
        updateSpeed();
        updatePlayState(animated);
        updateProgress();
    }

    private void onSeed(MessageObject mo, int seed) {
        if (mo != current) {
            return;
        }
        if (!PlayerStyle.themeColors()) {
            animateColors(PlayerColors.fromSeed(seed, dark), true);
        }
        updateBackdrop();
        coverScrim.invalidate();
    }

    private void updateHeader() {
        MessageObject mo = current;
        if (mo == null) {
            return;
        }
        MessagesController.SavedMusicList saved = MediaController.getInstance().currentSavedMusicList;
        if (saved != null) {
            headerLabel.setText(getString(R.string.OEPlayerFromProfile));
            headerTitle.setText(DialogObject.getShortName(saved.currentAccount, saved.dialogId));
        } else if (MediaController.getInstance().currentPlaylistIsGlobalSearch()) {
            headerLabel.setText(getString(R.string.OEPlayerFromSearch));
            headerTitle.setText(getString(R.string.OEPlayerMusic));
        } else {
            long did = mo.getDialogId();
            headerLabel.setText(getString(R.string.OEPlayerFromChat));
            if (did == UserConfig.getInstance(account).getClientUserId()) {
                headerTitle.setText(getString(R.string.SavedMessages));
            } else {
                headerTitle.setText(DialogObject.getName(account, did));
            }
        }
    }

    private void updateLike(boolean animated) {
        MessageObject mo = current;
        boolean visible = PlayerStyle.showLike() && mo != null && mo.getDocument() != null && !PlayerActions.noForwards(mo);
        likeButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        titleSpacer.setVisibility(visible && PlayerStyle.titleCentered() ? View.VISIBLE : View.GONE);
        if (!visible) {
            return;
        }
        boolean saved = PlayerActions.isSavedToProfile(mo);
        likeButton.setActive(saved, animated);
        likeButton.setRadius(dp(saved ? 14 : 24), animated);
        heartIcon.setFillStroke(saved);
        likeButton.setContentDescription(getString(saved ? R.string.ProfilePlaylistRemoveFromProfile : R.string.AudioSaveToMyProfile));
    }

    private void toggleProfile() {
        MessageObject mo = current;
        if (mo == null || PlayerActions.isProfileSavePending(mo)) {
            return;
        }
        boolean save = !PlayerActions.isSavedToProfile(mo);
        likeButton.setActive(save, true);
        likeButton.setRadius(dp(save ? 14 : 24), true);
        heartIcon.setFillStroke(save);
        PlayerActions.saveToProfile(mo, save, error -> {
            Theme.ResourcesProvider rp = colors.provider(resourcesProvider);
            if (error != null) {
                updateLike(true);
                BulletinFactory.of(root, rp).showForError(error);
                return;
            }
            MessagesController.SavedMusicList list = MediaController.getInstance().currentSavedMusicList;
            if (!save && list != null && list.dialogId == UserConfig.getInstance(mo.currentAccount).getClientUserId()) {
                list.remove(mo);
                if (list.list.isEmpty()) {
                    MediaController.getInstance().cleanup();
                    dismissImmediately();
                    return;
                }
                NotificationCenter.getInstance(mo.currentAccount).postNotificationName(NotificationCenter.musicListLoaded, list);
            }
            updateLike(true);
            BulletinFactory.of(root, rp).createSimpleBulletin(save ? R.raw.saved_messages : R.raw.ic_delete,
                    getString(save ? R.string.AudioSaveToMyProfileSaved : R.string.AudioSaveToMyProfileUnsaved)).show();
        });
    }

    private void updateModes(boolean animated) {
        int mode = SharedConfig.repeatMode;
        boolean repeatActive = mode == 1 || mode == 2;
        boolean reversed = SharedConfig.playOrderReversed;
        controls.setModes(SharedConfig.shuffleMusic, reversed, repeatActive, mode == 2, mode == AudioPlayerAlert.PLAY_ONCE, animated);
        controls.shuffle.setContentDescription(getString(reversed ? R.string.ReverseOrder : R.string.ShuffleList));
        controls.repeat.setContentDescription(getString(mode == AudioPlayerAlert.PLAY_ONCE ? R.string.PlayOnce : mode == 2 ? R.string.AccDescrRepeatOne : mode == 1 ? R.string.AccDescrRepeatList : R.string.AccDescrRepeatOff));
    }

    private void showPlaybackOptions(View anchor) {
        ItemOptions o = ItemOptions.makeOptions(container, colors.provider(resourcesProvider), anchor, true);
        addPlaybackOptions(o, null);
        o.show();
    }

    private void addPlaybackOptions(ItemOptions o, Runnable close) {
        int mode = SharedConfig.repeatMode;
        o.addChecked(mode == AudioPlayerAlert.PLAY_ONCE, R.drawable.photo_timer, getString(R.string.PlayOnce), () -> toggleRepeatMode(AudioPlayerAlert.PLAY_ONCE, close));
        o.addChecked(mode == 2, R.drawable.player_new_repeatone, getString(R.string.RepeatSong), () -> toggleRepeatMode(2, close));
        o.addChecked(mode == 1, R.drawable.player_new_repeatall, getString(R.string.RepeatList), () -> toggleRepeatMode(1, close));
        o.addGap();
        o.addChecked(SharedConfig.shuffleMusic, R.drawable.player_new_shuffle, getString(R.string.ShuffleList), () -> togglePlaybackOrder(2, close));
        o.addChecked(SharedConfig.playOrderReversed, R.drawable.player_new_order, getString(R.string.ReverseOrder), () -> togglePlaybackOrder(1, close));
    }

    private void toggleRepeatMode(int mode, Runnable close) {
        SharedConfig.setRepeatMode(SharedConfig.repeatMode == mode ? 0 : mode);
        updateModes(true);
        if (close != null) {
            close.run();
        }
    }

    private void togglePlaybackOrder(int type, Runnable close) {
        boolean on = type == 1 ? SharedConfig.playOrderReversed : SharedConfig.shuffleMusic;
        MediaController.getInstance().setPlaybackOrderType(on ? 0 : type);
        updateModes(true);
        if (close != null) {
            close.run();
        }
    }

    private void updateSpeed() {
        float speed = MediaController.getInstance().getPlaybackSpeed(true);
        DecimalFormat format = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(currentLocale()));
        String label = format.format(speed) + "×";
        speedButton.setText(islandActions ? null : label);
        speedButton.setBadge(islandActions && Math.abs(speed - 1f) > 0.01f ? format.format(speed) : null, speedButton.isLaidOut());
        speedButton.setContentDescription(getString(R.string.OEPlayerSpeed) + " " + label);
    }

    private static Locale currentLocale() {
        Locale locale = LocaleController.getInstance().getCurrentLocale();
        return locale != null ? locale : Locale.getDefault();
    }

    private void cycleSpeed() {
        float speed = MediaController.getInstance().getPlaybackSpeed(true);
        int index = -1;
        for (int i = 0; i < SPEEDS.length; i++) {
            if (Math.abs(SPEEDS[i] - speed) < 0.01f) {
                index = i;
                break;
            }
        }
        float next = index < 0 ? 1f : SPEEDS[(index + 1) % SPEEDS.length];
        MediaController.getInstance().setPlaybackSpeed(true, next);
        updateSpeed();
    }

    private void togglePlay() {
        MediaController mc = MediaController.getInstance();
        if (mc.isDownloadingCurrentMessage()) {
            return;
        }
        MessageObject mo = mc.getPlayingMessageObject();
        if (mc.isMessagePaused()) {
            mc.playMessage(mo);
        } else {
            mc.pauseMessage(mo);
        }
    }

    private void updatePlayState(boolean animated) {
        boolean playing = previewMode || !MediaController.getInstance().isMessagePaused();
        controls.setPlaying(playing, animated);
        controls.play.setContentDescription(getString(playing ? R.string.AccActionPause : R.string.AccActionPlay));
        seekBar.setPlaying(playing);
        updateSpin();
        boolean framed = coverStyle == PlayerStyle.COVER_CARD || coverStyle == PlayerStyle.COVER_DISC;
        float scale = playing || !framed || previewMode ? 1f : 0.92f;
        if (cover.getScaleX() != scale) {
            if (animated) {
                cover.animate().scaleX(scale).scaleY(scale).setDuration(450).setInterpolator(EMPHASIZED).start();
            } else {
                cover.setScaleX(scale);
                cover.setScaleY(scale);
            }
        }
    }

    private float displayProgress() {
        MessageObject mo = current;
        if (seeking) {
            return seekProgress;
        }
        return mo != null ? mo.audioProgress : 0f;
    }

    private void updateProgress() {
        MessageObject mo = current;
        if (mo == null) {
            return;
        }
        if (!seeking) {
            seekBar.setProgress(mo.audioProgress);
        }
        updateTimes();
        if (lyricsMode) {
            lyricsView.setPosition((long) (mo.audioProgress * mo.getDuration() * 1000));
        }
    }

    private void updateTimes() {
        MessageObject mo = current;
        if (mo == null) {
            return;
        }
        int duration = (int) Math.round(mo.getDuration());
        int now = seeking ? (int) (duration * seekProgress) : Math.min(duration, mo.audioProgressSec);
        String a = formatTime(now);
        String b = "−" + formatTime(Math.max(0, duration - now));
        if (!TextUtils.equals(timeNow.getText(), a)) {
            timeNow.setText(a);
            bubble.setText(a);
        }
        if (!TextUtils.equals(timeLeft.getText(), b)) {
            timeLeft.setText(b);
        }
    }

    private static String formatTime(int seconds) {
        if (seconds >= 3600) {
            return String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        }
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    private void layoutBubble() {
        if (bubble.getWidth() == 0) {
            bubble.post(this::layoutBubble);
            return;
        }
        float left = dp(3);
        float right = seekBar.getWidth() - dp(3);
        float x = seekBar.getLeft() + left + displayProgress() * (right - left);
        float tx = x - bubble.getLeft() - bubble.getWidth() / 2f;
        float min = -bubble.getLeft() + dp(8);
        float max = layout.getWidth() - bubble.getLeft() - bubble.getWidth() - dp(8);
        bubble.setTranslationX(Math.max(min, Math.min(max, tx)));
    }

    private void setLyricsMode(boolean value, boolean animated) {
        if (lyricsMode == value) {
            return;
        }
        lyricsMode = value;
        lyricsButton.setActive(value, animated);
        lyricsButton.setRadius(lyricsRadius(value), animated);
        if (value) {
            loadLyrics();
            MessageObject mo = current;
            if (mo != null) {
                lyricsView.setPosition((long) (mo.audioProgress * mo.getDuration() * 1000));
            }
        }
        if (lyricsAnimator != null) {
            lyricsAnimator.cancel();
        }
        float target = value ? 1f : 0f;
        if (!animated) {
            setLyricsFraction(target);
            return;
        }
        lyricsPanel.setVisibility(View.VISIBLE);
        cover.setVisibility(View.VISIBLE);
        titleRow.setVisibility(View.VISIBLE);
        lyricsAnimator = ValueAnimator.ofFloat(lyricsFraction, target);
        lyricsAnimator.addUpdateListener(a -> setLyricsFraction((float) a.getAnimatedValue()));
        lyricsAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                setLyricsFraction(target);
            }
        });
        lyricsAnimator.setDuration(360);
        lyricsAnimator.setInterpolator(EMPHASIZED);
        lyricsAnimator.start();
    }

    private void setLyricsFraction(float f) {
        lyricsFraction = f;
        float playerAlpha = Math.max(0f, 1f - f * 1.6f);
        float lyricsAlpha = Math.max(0f, (f - 0.35f) / 0.65f);
        boolean backdropCover = coverStyle == PlayerStyle.COVER_FULL;
        cover.setAlpha(backdropCover ? 1f : playerAlpha);
        titleRow.setAlpha(playerAlpha);
        cover.setTranslationY(backdropCover ? 0 : -dp(24) * f);
        titleRow.setTranslationY(-dp(24) * f);
        lyricsPanel.setAlpha(lyricsAlpha);
        lyricsPanel.setTranslationY(dp(24) * (1f - f));
        cover.setVisibility(f >= 1f && !backdropCover ? View.INVISIBLE : View.VISIBLE);
        titleRow.setVisibility(f >= 1f ? View.INVISIBLE : View.VISIBLE);
        lyricsPanel.setVisibility(f <= 0f ? View.GONE : View.VISIBLE);
        coverScrim.invalidate();
    }

    private OnlineLyrics.Query query(MessageObject mo) {
        String album = null;
        if (PlayerArt.isPlaying(mo)) {
            AudioInfo info = MediaController.getInstance().getAudioInfo();
            if (info != null) {
                album = info.getAlbum();
            }
        }
        return new OnlineLyrics.Query(mo.getMusicAuthor(false), mo.getMusicTitle(false), album, (int) Math.round(mo.getDuration()));
    }

    private long positionMs() {
        MessageObject mo = current;
        return mo == null ? 0 : (long) (mo.audioProgress * mo.getDuration() * 1000);
    }

    private void loadLyrics() {
        MessageObject mo = current;
        if (mo == null) {
            return;
        }
        String key = currentKey;
        if (TextUtils.equals(lyricsKey, key)) {
            return;
        }
        lyricsKey = key;
        fileLyrics = null;
        if (PlayerArt.isPlaying(mo)) {
            AudioInfo info = MediaController.getInstance().getAudioInfo();
            Lyrics embedded = info != null ? Lyrics.parse(info.getLyrics(), Lyrics.SOURCE_FILE) : null;
            if (embedded != null) {
                showLyrics(embedded);
                if (embedded.synced || embedded.instrumental) {
                    return;
                }
                fileLyrics = embedded;
            }
        }
        OnlineLyrics.Query q = query(mo);
        if (!q.valid()) {
            if (fileLyrics == null) {
                lyricsView.showState(LyricsView.STATE_NOT_FOUND);
                sourceView.setText(null);
            }
            return;
        }
        Lyrics cached = OnlineLyrics.cached(q);
        if (cached != null && replacesFileLyrics(cached)) {
            showLyrics(cached);
            return;
        }
        if (fileLyrics == null) {
            lyricsView.showState(LyricsView.STATE_LOADING);
            sourceView.setText(null);
        }
        OnlineLyrics.loadCached(q, (lyrics, status) -> {
            if (!TextUtils.equals(key, currentKey)) {
                return;
            }
            if (lyrics != null && replacesFileLyrics(lyrics)) {
                showLyrics(lyrics);
            } else if (AppearanceConfig.lrclibAllowed.Bool()) {
                fetchLyrics();
            } else if (fileLyrics == null) {
                lyricsView.showState(LyricsView.STATE_OFFER);
            }
        });
    }

    private boolean replacesFileLyrics(Lyrics lyrics) {
        return fileLyrics == null || lyrics.synced;
    }

    private void fetchLyrics() {
        MessageObject mo = current;
        if (mo == null) {
            return;
        }
        String key = currentKey;
        OnlineLyrics.Query q = query(mo);
        if (!q.valid() || OnlineLyrics.knownMissing(q)) {
            if (fileLyrics == null) {
                lyricsView.showState(LyricsView.STATE_NOT_FOUND);
            }
            return;
        }
        if (fileLyrics == null) {
            lyricsView.showState(LyricsView.STATE_LOADING);
            sourceView.setText(null);
        }
        OnlineLyrics.fetch(q, (lyrics, status) -> {
            if (!TextUtils.equals(key, currentKey)) {
                return;
            }
            if (lyrics != null && replacesFileLyrics(lyrics)) {
                showLyrics(lyrics);
            } else if (fileLyrics == null) {
                lyricsView.showState(status == OnlineLyrics.ERROR ? LyricsView.STATE_ERROR : LyricsView.STATE_NOT_FOUND);
            }
        });
    }

    private void showLyrics(Lyrics lyrics) {
        if (lyrics.instrumental) {
            lyricsView.showState(LyricsView.STATE_INSTRUMENTAL);
            sourceView.setText(lyrics.provider);
            return;
        }
        lyricsView.setLyrics(lyrics, positionMs());
        if (lyrics.source == Lyrics.SOURCE_ONLINE) {
            sourceView.setText(LocaleController.formatString(lyrics.synced ? R.string.OEPlayerSourceSynced : R.string.OEPlayerSourcePlain, lyrics.provider));
        } else {
            sourceView.setText(getString(lyrics.synced ? R.string.OEPlayerSourceSyncedFile : R.string.OEPlayerSourcePlainFile));
        }
    }

    private void openQueue() {
        new PlayerQueueSheet(getContext(), colors, resourcesProvider).show();
    }

    private void openClassic() {
        dismissImmediately();
        if (activity != null) {
            new AudioPlayerAlert(activity, resourcesProvider).show();
        }
    }

    private void showMenu(View anchor) {
        MessageObject mo = current;
        if (mo == null || activity == null) {
            return;
        }
        boolean noForwards = PlayerActions.noForwards(mo);
        Theme.ResourcesProvider rp = colors.provider(resourcesProvider);
        ItemOptions o = ItemOptions.makeOptions(container, rp, anchor, true);
        if (!noForwards) {
            ItemOptions sub = o.makeSwipeback();
            sub.add(R.drawable.ic_ab_back, getString(R.string.Back), o::closeSwipeback);
            sub.addGap();
            sub.addIf(!PlayerActions.isSavedToProfile(mo), R.drawable.left_status_profile, getString(R.string.AudioSaveToMyProfile), () -> {
                o.dismiss();
                toggleProfile();
            });
            sub.add(R.drawable.msg_saved, getString(R.string.AudioSaveToSavedMessages), () -> {
                o.dismiss();
                PlayerActions.forwardTo(activity, mo, UserConfig.getInstance(mo.currentAccount).getClientUserId());
            });
            sub.add(R.drawable.menu_download_round, getString(R.string.AudioSaveToMusicFolder), () -> {
                o.dismiss();
                PlayerActions.saveToMusic(activity, mo, root, rp);
            });
            sub.addGap();
            sub.addText(getString(R.string.AudioSaveToInfo), 12, dp(200));
            o.add(R.drawable.msg_stories_save, getString(R.string.AudioSaveTo), () -> o.openSwipeback(sub));
            if (o.getLast() != null) {
                o.getLast().setRightIcon(R.drawable.msg_arrowright);
            }
            o.add(R.drawable.msg_forward, getString(R.string.Forward), () -> {
                o.dismiss();
                dismissImmediately();
                PlayerActions.forward(activity, mo);
            });
        } else {
            o.add(R.drawable.menu_download_round, getString(R.string.AudioSaveToMusicFolder), () -> {
                o.dismiss();
                PlayerActions.saveToMusic(activity, mo, root, rp);
            });
        }
        o.add(R.drawable.msg_shareout, getString(R.string.ShareFile), () -> {
            o.dismiss();
            PlayerActions.share(activity, mo);
        });
        ItemOptions order = o.makeSwipeback();
        order.add(R.drawable.ic_ab_back, getString(R.string.Back), o::closeSwipeback);
        order.addGap();
        addPlaybackOptions(order, o::dismiss);
        o.add(R.drawable.player_new_order, getString(R.string.OEPlayerPlaybackOrder), () -> o.openSwipeback(order));
        if (o.getLast() != null) {
            o.getLast().setRightIcon(R.drawable.msg_arrowright);
        }
        o.addIf(mo.getId() > 0, R.drawable.msg_message, getString(R.string.ShowInChat), () -> {
            o.dismiss();
            dismissImmediately();
            PlayerActions.showInChat(activity, mo);
        });
        if (!PlayerStyle.showActions()) {
            o.addGap();
            o.add(R.drawable.msg_photo_text_framed3, getString(R.string.OEPlayerLyrics), () -> {
                o.dismiss();
                setLyricsMode(!lyricsMode, true);
            });
            o.add(R.drawable.msg_list, getString(R.string.OEPlayerQueue), () -> {
                o.dismiss();
                openQueue();
            });
            o.add(R.drawable.msg_speed, getString(R.string.OEPlayerSpeed), () -> {
                o.dismiss();
                cycleSpeed();
            });
        }
        o.addGap();
        o.add(R.drawable.msg_customize, getString(R.string.OEPlayerCustomize), () -> {
            o.dismiss();
            openEditor();
        });
        o.add(R.drawable.msg_filled_data_music, getString(R.string.OEPlayerClassic), () -> {
            o.dismiss();
            openClassic();
        });
        o.setGravity(LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT);
        o.show();
    }

    private boolean onCoverTouch(View v, MotionEvent e) {
        if (coverAnimating || previewMode || editing || !PlayerStyle.coverSwipe()
                || coverStyle != PlayerStyle.COVER_CARD && coverStyle != PlayerStyle.COVER_DISC) {
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragStartX = e.getRawX();
                dragStartY = e.getRawY();
                dragTracking = true;
                dragActive = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!dragTracking) {
                    return false;
                }
                float dx = e.getRawX() - dragStartX;
                float dy = e.getRawY() - dragStartY;
                if (!dragActive) {
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                        dragActive = true;
                        dragStartX = e.getRawX();
                        dx = 0;
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                    } else if (Math.abs(dy) > touchSlop) {
                        dragTracking = false;
                        return false;
                    }
                }
                if (dragActive) {
                    setCoverDrag(dx);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float dx = e.getRawX() - dragStartX;
                boolean wasActive = dragActive;
                dragTracking = false;
                dragActive = false;
                if (wasActive) {
                    finishCoverDrag(e.getActionMasked() == MotionEvent.ACTION_UP ? dx : 0);
                }
                return true;
            }
        }
        return false;
    }

    private void setCoverDrag(float dx) {
        cover.setTranslationX(dx);
        if (coverStyle != PlayerStyle.COVER_DISC) {
            cover.setRotation(dx / dp(40));
        }
        cover.setAlpha(1f - Math.min(Math.abs(dx) / dp(420), 0.4f));
    }

    private void finishCoverDrag(float dx) {
        boolean disc = coverStyle == PlayerStyle.COVER_DISC;
        if (Math.abs(dx) < dp(70)) {
            cover.animate().translationX(0).rotation(disc ? cover.getRotation() : 0).alpha(1f).setDuration(450).setInterpolator(EMPHASIZED).start();
            return;
        }
        boolean next = dx < 0;
        float out = (next ? -1 : 1) * layout.getWidth();
        coverAnimating = true;
        cover.animate().translationX(out).rotation(disc ? cover.getRotation() : out / dp(40) / 4f).alpha(0f).setDuration(180).setInterpolator(CubicBezierInterpolator.EASE_IN).withEndAction(() -> {
            if (next) {
                MediaController.getInstance().playNextMessage();
            } else {
                MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
                if (mo != null) {
                    mo.audioProgressSec = 0;
                }
                MediaController.getInstance().playPreviousMessage();
            }
            cover.setTranslationX(-out * 0.35f);
            if (!disc) {
                cover.setRotation(0);
            }
            cover.animate().translationX(0).alpha(1f).setDuration(450).setInterpolator(EMPHASIZED).withEndAction(() -> coverAnimating = false).start();
        }).start();
    }

    private void animateColors(PlayerColors target, boolean animated) {
        if (colorAnimator != null) {
            colorAnimator.cancel();
            colorAnimator = null;
        }
        if (!animated) {
            applyColors(target);
            return;
        }
        PlayerColors from = colors;
        colorAnimator = ValueAnimator.ofFloat(0f, 1f);
        colorAnimator.addUpdateListener(a -> applyColors(PlayerColors.lerp(from, target, (float) a.getAnimatedValue())));
        colorAnimator.setDuration(600);
        colorAnimator.setInterpolator(EMPHASIZED);
        colorAnimator.start();
    }

    private void applyColors(PlayerColors c) {
        colors = c;
        root.invalidate();
        collapseIcon.setColor(c.onSurface);
        moreIcon.setColor(c.onSurface);
        collapseButton.setBackground(Theme.createSelectorDrawable(c.ripple(), Theme.RIPPLE_MASK_CIRCLE_20DP));
        moreButton.setBackground(Theme.createSelectorDrawable(c.ripple(), Theme.RIPPLE_MASK_CIRCLE_20DP));
        headerLabel.setTextColor(c.onSurfaceVariant);
        headerTitle.setTextColor(c.onSurface);
        cover.setColors(c.primaryContainer, c.onPrimaryContainer);
        cover.setDisc(coverStyle == PlayerStyle.COVER_DISC, c.surface);
        smallCover.setColors(c.primaryContainer, c.onPrimaryContainer);
        coverScrim.invalidate();
        layout.invalidate();
        if (styleEditor != null) {
            styleEditor.setColors(c);
            bindEditorColors(c);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            cover.setOutlineSpotShadowColor(c.shadow());
            cover.setOutlineAmbientShadowColor(c.shadow());
        }
        titleView.setTextColor(c.onSurface);
        artistView.setTextColor(c.onSurfaceVariant);
        smallTitle.setTextColor(c.onSurface);
        smallArtist.setTextColor(c.onSurfaceVariant);
        likeButton.setColors(0, c.onSurfaceVariant, c.primaryContainer, c.onPrimaryContainer);
        lyricsView.setColors(c);
        sourceView.setTextColor(c.onSurfaceVariant);
        seekBar.setColors(c.primary, c.secondaryContainer);
        timeNow.setTextColor(c.onSurfaceVariant);
        timeLeft.setTextColor(c.onSurfaceVariant);
        bubbleBg.setColor(c.onSurface);
        bubble.setTextColor(c.surface);
        controls.shuffle.setColors(0, c.onSurfaceVariant, c.secondaryContainer, c.onSecondaryContainer);
        controls.repeat.setColors(0, c.onSurfaceVariant, c.secondaryContainer, c.onSecondaryContainer);
        controls.repeat.setBadgeColors(c.primary, c.onPrimary);
        controls.prev.setColors(c.primaryContainer, c.onPrimaryContainer, c.primaryContainer, c.onPrimaryContainer);
        controls.next.setColors(c.primaryContainer, c.onPrimaryContainer, c.primaryContainer, c.onPrimaryContainer);
        controls.play.setColors(c.primary, c.onPrimary, c.primary, c.onPrimary);
        bindActionColors(c);
        applySystemBars(false);
    }

    private void applySystemBars(boolean force) {
        if (morphProgress >= 0f || closing) {
            return;
        }
        boolean light = colors.lightStatusBar();
        if (!force && lightBars != null && lightBars == light) {
            return;
        }
        lightBars = light;
        setBars(light, light);
    }

    private void setBars(boolean lightStatus, boolean lightNav) {
        if (getWindow() != null) {
            AndroidUtilities.setLightStatusBar(getWindow(), lightStatus);
            AndroidUtilities.setLightNavigationBar(this, lightNav);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            AndroidUtilities.setLightStatusBar(container, lightStatus);
            AndroidUtilities.setLightNavigationBar(container, lightNav);
        }
    }

    private void bindPreview() {
        titleView.setText(getString(R.string.OEPlayerPreviewTitle));
        artistView.setText(getString(R.string.OEPlayerPreviewArtist));
        smallTitle.setText(getString(R.string.OEPlayerPreviewTitle));
        smallArtist.setText(getString(R.string.OEPlayerPreviewArtist));
        headerLabel.setText(getString(R.string.OEPlayerFromChat));
        headerTitle.setText(getString(R.string.SavedMessages));
        likeButton.setVisibility(PlayerStyle.showLike() ? View.VISIBLE : View.GONE);
        titleSpacer.setVisibility(PlayerStyle.showLike() && PlayerStyle.titleCentered() ? View.VISIBLE : View.GONE);
        seekBar.setProgress(0.36f);
        timeNow.setText(formatTime(78));
        timeLeft.setText("−" + formatTime(139));
        controls.setPlaying(true, false);
        updateModes(false);
        updateSpeed();
        layout.setEnabled(false);
    }

    public void openEditor() {
        if (editing || closing) {
            return;
        }
        if (lyricsMode) {
            setLyricsMode(false, false);
        }
        if (editorPanel == null) {
            buildEditor();
        } else {
            styleEditor.refresh();
        }
        editing = true;
        editorPanel.setVisibility(View.VISIBLE);
        root.post(() -> {
            measureEditor();
            animateEditor(1f);
        });
    }

    public void closeEditor() {
        if (!editing) {
            return;
        }
        editing = false;
        if (previewMode) {
            skipMorph = true;
            dismiss();
            return;
        }
        animateEditor(0f);
    }

    private void animateEditor(float target) {
        if (editorAnimator != null) {
            editorAnimator.cancel();
        }
        editorAnimator = ValueAnimator.ofFloat(editorFraction, target);
        editorAnimator.addUpdateListener(a -> setEditorFraction((float) a.getAnimatedValue()));
        editorAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                editorAnimator = null;
                setEditorFraction(target);
                if (target == 0f && editorPanel != null) {
                    editorPanel.setVisibility(View.GONE);
                }
            }
        });
        editorAnimator.setDuration(420);
        editorAnimator.setInterpolator(EMPHASIZED);
        editorAnimator.start();
    }

    private void measureEditor() {
        if (editorPanel == null) {
            return;
        }
        int width = root.getWidth();
        int height = root.getHeight();
        if (width == 0 || height == 0) {
            return;
        }
        int top = getStatusBarHeight();
        int panelHeight = Math.max(dp(320), Math.min(height - dp(240), Math.round(height * 0.5f)));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) editorPanel.getLayoutParams();
        if (lp.height != panelHeight) {
            lp.height = panelHeight;
            editorPanel.setLayoutParams(lp);
        }
        float previewHeight = height - panelHeight - top - dp(20);
        editScale = Math.max(0.2f, Math.min(previewHeight / height, (width - dp(32)) / (float) width));
        editTop = top + dp(8);
    }

    private void setEditorFraction(float f) {
        editorFraction = f;
        int width = root.getWidth();
        float s = 1f + (editScale - 1f) * f;
        if (morphProgress < 0f) {
            layout.setPivotX(0);
            layout.setPivotY(0);
            layout.setScaleX(s);
            layout.setScaleY(s);
            layout.setTranslationX((width - width * s) / 2f);
            layout.setTranslationY(editTop * f);
        }
        if (editorPanel != null) {
            int h = editorPanel.getHeight() > 0 ? editorPanel.getHeight() : editorPanel.getLayoutParams().height;
            editorPanel.setTranslationY(h * (1f - f));
        }
        root.invalidate();
        layout.invalidate();
    }

    private void buildEditor() {
        Context context = getContext();
        editorPanel = new FrameLayout(context);
        editorPanel.setClickable(true);
        float r = dp(28);
        editorBackground.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        editorPanel.setBackground(editorBackground);

        FrameLayout bar = new FrameLayout(context);
        editorTitle = new TextView(context);
        editorTitle.setText(getString(R.string.OEPlayerStyleTitle));
        editorTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        editorTitle.setTypeface(AndroidUtilities.bold());
        editorTitle.setSingleLine(true);
        editorTitle.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(editorTitle, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.LEFT, 24, 0, 200, 0));
        editorDone = new TextView(context);
        editorDone.setText(getString(R.string.Done));
        editorDone.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        editorDone.setTypeface(AndroidUtilities.bold());
        editorDone.setGravity(Gravity.CENTER);
        editorDone.setPadding(dp(18), 0, dp(18), 0);
        editorDoneBackground.setCornerRadius(dp(20));
        editorDone.setBackground(editorDoneBackground);
        editorDone.setOnClickListener(v -> closeEditor());
        bar.addView(editorDone, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 40, Gravity.CENTER_VERTICAL | Gravity.RIGHT, 0, 0, 16, 0));
        editorReset = new TextView(context);
        editorReset.setText(getString(R.string.OEPlayerStyleReset));
        editorReset.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        editorReset.setTypeface(AndroidUtilities.bold());
        editorReset.setGravity(Gravity.CENTER);
        editorReset.setPadding(dp(14), 0, dp(14), 0);
        editorReset.setOnClickListener(v -> {
            PlayerStyle.reset();
            styleEditor.refresh();
            applyStyle(true);
        });
        bar.addView(editorReset, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 40, Gravity.CENTER_VERTICAL | Gravity.RIGHT, 0, 0, 112, 0));
        editorPanel.addView(bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 64, Gravity.TOP, 0, 4, 0, 0));

        ScrollView scroll = new ScrollView(context) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    container.requestDisallowInterceptTouchEvent(true);
                }
                return super.dispatchTouchEvent(ev);
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        styleEditor = new PlayerStyleEditor(context, colors, () -> applyStyle(true));
        scroll.addView(styleEditor, new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, getBottomInset());
        editorPanel.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 68, 0, 0));

        root.addView(editorPanel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 320, Gravity.BOTTOM));
        padEditorPanel();
        editorPanel.setTranslationY(dp(2000));
        bindEditorColors(colors);
    }

    private void bindEditorColors(PlayerColors c) {
        if (editorPanel == null) {
            return;
        }
        editorBackground.setColor(c.surfaceContainer);
        editorTitle.setTextColor(c.onSurface);
        editorDoneBackground.setColor(c.primary);
        editorDone.setTextColor(c.onPrimary);
        editorReset.setTextColor(c.primary);
        editorReset.setBackground(Theme.createSelectorDrawable(c.ripple(), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
    }

    private void applyStyle(boolean animated) {
        int style = PlayerStyle.coverStyle();
        boolean styleChanged = style != coverStyle;
        if (animated && layout.isLaidOut() && morphProgress < 0f && !styleChanged) {
            ChangeBounds transition = new ChangeBounds();
            transition.setDuration(380);
            transition.setInterpolator(EMPHASIZED);
            TransitionManager.beginDelayedTransition(layout, transition);
        }
        coverStyle = style;
        blockOrder = PlayerStyle.order();
        boolean framed = style == PlayerStyle.COVER_CARD || style == PlayerStyle.COVER_DISC;
        cover.setElevation(framed ? dp(14) : 0);
        cover.setTranslationZ(0);
        if (!framed) {
            cover.setScaleX(1f);
            cover.setScaleY(1f);
            cover.setTranslationX(0);
            cover.setAlpha(1f);
        }
        if (style != PlayerStyle.COVER_DISC) {
            cover.setRotation(0);
        }
        if (style == PlayerStyle.COVER_CARD) {
            cover.setRadius(dp(PlayerStyle.coverRadiusDp()));
        } else if (style != PlayerStyle.COVER_DISC) {
            cover.setRadius(0);
        }
        cover.setDisc(style == PlayerStyle.COVER_DISC, colors.surface);
        cover.invalidateOutline();
        coverScrim.setVisibility(framed ? View.GONE : View.VISIBLE);
        coverScrim.invalidate();

        int titleSize = PlayerStyle.titleSize();
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, titleSize == 0 ? 22 : titleSize == 2 ? 31 : 26);
        artistView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, titleSize == 0 ? 15 : titleSize == 2 ? 17 : 16);
        titles.setGravity(PlayerStyle.titleCentered() ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        if (previewMode) {
            likeButton.setVisibility(PlayerStyle.showLike() ? View.VISIBLE : View.GONE);
            titleSpacer.setVisibility(PlayerStyle.showLike() && PlayerStyle.titleCentered() ? View.VISIBLE : View.GONE);
        } else {
            updateLike(false);
        }
        headerCenter.setVisibility(PlayerStyle.showSource() ? View.VISIBLE : View.INVISIBLE);
        seekBar.setMaxAmplitude(PlayerStyle.wavy() ? dp(4) : 0);
        controls.setStyle(PlayerStyle.controlsScale(), PlayerStyle.showModes());
        group.setVisibility(PlayerStyle.showActions() ? View.VISIBLE : View.GONE);
        applyActionsStyle();

        PlayerColors target = colors;
        if (PlayerStyle.themeColors()) {
            target = PlayerColors.fromSeed(fallbackSeed, dark);
        } else if (current != null) {
            Integer seed = PlayerArt.cachedSeed(current);
            target = PlayerColors.fromSeed(seed != null ? seed : fallbackSeed, dark);
        }
        if (target.primary != colors.primary || target.surface != colors.surface) {
            animateColors(target, animated);
        } else {
            applyColors(colors);
        }
        updateBackdrop();
        updatePlayState(false);
        setLyricsFraction(lyricsFraction);
        layout.requestLayout();
        layout.invalidate();
        root.invalidate();
    }

    private void applyActionsStyle() {
        islandActions = PlayerStyle.actionsIsland();
        setActionParams(speedButton, 0);
        setActionParams(lyricsButton, 4);
        setActionParams(queueButton, 0);
        group.setGravity(Gravity.CENTER_VERTICAL);
        group.setPadding(islandActions ? dp(6) : 0, 0, islandActions ? dp(6) : 0, 0);
        group.setBackground(islandActions ? islandBackground : null);
        group.setElevation(islandActions ? dp(2) : 0);
        lyricsButton.setText(islandActions ? null : getString(R.string.OEPlayerLyrics));
        queueButton.setText(islandActions ? null : getString(R.string.OEPlayerQueue));
        lyricsButton.setContentDescription(getString(R.string.OEPlayerLyrics));
        queueButton.setContentDescription(getString(R.string.OEPlayerQueue));
        speedButton.setRadius(dp(26), dp(islandActions ? 26 : 8), false);
        queueButton.setRadius(dp(islandActions ? 26 : 8), dp(26), false);
        lyricsButton.setRadius(lyricsRadius(lyricsMode), false);
        updateSpeed();
        bindActionColors(colors);
    }

    private void setActionParams(MorphButton button, int marginDp) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) button.getLayoutParams();
        lp.width = islandActions ? dp(44) : 0;
        lp.height = dp(islandActions ? 44 : 52);
        lp.weight = islandActions ? 0f : 1f;
        lp.leftMargin = dp(marginDp);
        lp.rightMargin = dp(marginDp);
        button.setLayoutParams(lp);
    }

    private float lyricsRadius(boolean active) {
        return dp(islandActions || active ? 26 : 8);
    }

    private void bindActionColors(PlayerColors c) {
        islandBackground.setCornerRadius(dp(28));
        islandBackground.setColor(c.surfaceHigh);
        int background = islandActions ? 0 : c.surfaceHigh;
        speedButton.setColors(background, c.onSurface, background, c.onSurface);
        speedButton.setBadgeColors(c.primary, c.onPrimary);
        lyricsButton.setColors(background, c.onSurface, c.primary, c.onPrimary);
        queueButton.setColors(background, c.onSurface, background, c.onSurface);
    }

    private void updateSpin() {
        boolean spin = coverStyle == PlayerStyle.COVER_DISC && PlayerStyle.discSpin()
                && (previewMode || !MediaController.getInstance().isMessagePaused()) && !detached;
        if (spin) {
            if (spinAnimator == null) {
                float from = cover.getRotation() % 360f;
                spinAnimator = ValueAnimator.ofFloat(from, from + 360f);
                spinAnimator.setDuration(16000);
                spinAnimator.setRepeatCount(ValueAnimator.INFINITE);
                spinAnimator.setInterpolator(null);
                spinAnimator.addUpdateListener(a -> {
                    if (!dragActive && !coverAnimating) {
                        cover.setRotation((float) a.getAnimatedValue());
                    }
                });
                spinAnimator.start();
            }
        } else if (spinAnimator != null) {
            spinAnimator.cancel();
            spinAnimator = null;
        }
    }

    private void updateBackdrop() {
        boolean need = coverStyle != PlayerStyle.COVER_FULL && PlayerStyle.background() == PlayerStyle.BACKGROUND_BLUR;
        if (!need) {
            backdrop = null;
            backdropKey = null;
            layout.invalidate();
            return;
        }
        Bitmap source = current != null ? PlayerArt.fileCover(current) : null;
        if (source == null && cover.getImageReceiver().hasBitmapImage()) {
            source = cover.getImageReceiver().getBitmap();
        }
        String key = currentKey + (source != null ? ":" + source.getWidth() + "x" + source.getHeight() : "");
        if (source == null) {
            backdrop = null;
            backdropKey = null;
            layout.invalidate();
            return;
        }
        if (TextUtils.equals(key, backdropKey) && backdrop != null) {
            return;
        }
        Bitmap small = PlayerColors.sample(source);
        if (small == null) {
            return;
        }
        Bitmap blurred = small.isMutable() ? small : small.copy(Bitmap.Config.ARGB_8888, true);
        if (blurred == null) {
            return;
        }
        try {
            Utilities.stackBlurBitmap(blurred, Math.max(8, Math.min(blurred.getWidth(), blurred.getHeight()) / 6));
        } catch (Throwable ignore) {
        }
        backdrop = blurred;
        backdropKey = key;
        layout.invalidate();
    }

    private void drawBackdrop(Canvas canvas, int width, int height) {
        if (coverStyle == PlayerStyle.COVER_FULL) {
            return;
        }
        int mode = PlayerStyle.background();
        if (mode == PlayerStyle.BACKGROUND_BLUR && backdrop != null && !backdrop.isRecycled()) {
            int bw = backdrop.getWidth();
            int bh = backdrop.getHeight();
            float ratio = width / (float) Math.max(1, height);
            if (bw / (float) bh > ratio) {
                int w = Math.max(1, (int) (bh * ratio));
                int x = (bw - w) / 2;
                backdropSrc.set(x, 0, x + w, bh);
            } else {
                int h = Math.max(1, (int) (bw / ratio));
                int y = (bh - h) / 2;
                backdropSrc.set(0, y, bw, y + h);
            }
            previewRect.set(0, 0, width, height);
            backdropPaint.setAlpha(255);
            canvas.drawBitmap(backdrop, backdropSrc, previewRect, backdropPaint);
            overlayPaint.setShader(null);
            overlayPaint.setColor(ColorUtils.setAlphaComponent(colors.surface, colors.dark ? 0xa8 : 0xb4));
            canvas.drawRect(previewRect, overlayPaint);
        } else if (mode == PlayerStyle.BACKGROUND_GRADIENT && (coverStyle != PlayerStyle.COVER_WIDE || landscape)) {
            int key = colors.primaryContainer * 31 + height;
            if (backgroundGradient == null || backgroundGradientKey != key) {
                backgroundGradientKey = key;
                backgroundGradient = new LinearGradient(0, 0, 0, height * 0.7f,
                        new int[]{ColorUtils.setAlphaComponent(colors.primaryContainer, 0xd9), ColorUtils.setAlphaComponent(colors.primaryContainer, 0)},
                        null, Shader.TileMode.CLAMP);
            }
            overlayPaint.setShader(backgroundGradient);
            overlayPaint.setAlpha(255);
            canvas.drawRect(0, 0, width, height * 0.7f, overlayPaint);
            overlayPaint.setShader(null);
        }
    }

    private class CoverScrim extends View {

        private LinearGradient gradient;
        private LinearGradient sideGradient;
        private int gradientKey;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint extensionPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint shadePaint = new Paint();
        private final Rect extensionSrc = new Rect();
        private final RectF extensionDst = new RectF();
        private Bitmap extension;
        private Bitmap extensionSource;
        private int extensionKey;
        private int extensionFadeRows;
        private LinearGradient shade;
        private int shadeKey;

        CoverScrim(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0) {
                return;
            }
            boolean extended = prepareExtension(w, h);
            drawScrim(canvas, w, h, 1f, extended);
            if (extended) {
                drawExtension(canvas, w, h, cover.getAlpha());
            }
        }

        void drawMorph(Canvas canvas, RectF bounds, float alpha) {
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0 || alpha <= 0f) {
                return;
            }
            canvas.save();
            canvas.translate(bounds.left, bounds.top);
            canvas.scale(bounds.width() / w, bounds.height() / h);
            drawScrim(canvas, w, h, alpha, prepareExtension(w, h));
            canvas.restore();
        }

        void drawMorphExtension(Canvas canvas, RectF bounds, float alpha) {
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0 || alpha <= 0f || !prepareExtension(w, h)) {
                return;
            }
            canvas.save();
            canvas.translate(bounds.left, bounds.top);
            canvas.scale(bounds.width() / w, bounds.height() / h);
            drawExtension(canvas, w, h, alpha);
            canvas.restore();
        }

        private int extensionBelow(int h) {
            return Math.max(0, layout.getHeight() - getTop() - h);
        }

        private int extensionFade(int h) {
            return Math.min(Math.round(h * 0.22f), dp(120));
        }

        private boolean prepareExtension(int w, int h) {
            if (coverStyle != PlayerStyle.COVER_WIDE || !PlayerStyle.coverExtend() || landscape) {
                return false;
            }
            int below = extensionBelow(h);
            Bitmap source = cover.getImageReceiver().getBitmap();
            if (below <= 0 || source == null || source.isRecycled() || source.getWidth() <= 0 || source.getHeight() <= 0) {
                return false;
            }
            int key = (w * 31 + h) * 31 + below;
            if (extension != null && source == extensionSource && key == extensionKey) {
                return true;
            }
            Bitmap built = buildExtension(source, w, h, below);
            if (built == null) {
                return false;
            }
            extension = built;
            extensionSource = source;
            extensionKey = key;
            return true;
        }

        private Bitmap buildExtension(Bitmap source, int w, int h, int below) {
            try {
                Bitmap src = source;
                if (Build.VERSION.SDK_INT >= 26 && src.getConfig() == Bitmap.Config.HARDWARE) {
                    src = src.copy(Bitmap.Config.ARGB_8888, false);
                    if (src == null) {
                        return null;
                    }
                }
                int sw = src.getWidth();
                int sh = src.getHeight();
                float scale = Math.max(w / (float) sw, h / (float) sh);
                float visibleW = w / scale;
                float left = (sw - visibleW) / 2f;
                float bottom = (sh + h / scale) / 2f;
                int fade = extensionFade(h);
                int mirror = Math.max(1, Math.min(below, h / 2));
                int width = 32;
                float k = width / (float) w;
                int fadeRows = Math.max(2, Math.round(fade * k));
                int mirrorRows = Math.max(2, Math.round(mirror * k));
                Bitmap out = Bitmap.createBitmap(width, fadeRows + mirrorRows, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(out);
                Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
                int srcLeft = Math.max(0, Math.round(left));
                int srcRight = Math.min(sw, Math.round(left + visibleW));
                int srcBottom = Math.min(sh, Math.round(bottom));
                c.drawBitmap(src, new Rect(srcLeft, Math.max(0, Math.round(bottom - fade / scale)), srcRight, srcBottom), new Rect(0, 0, width, fadeRows), p);
                c.save();
                c.scale(1f, -1f, 0, fadeRows + mirrorRows / 2f);
                c.drawBitmap(src, new Rect(srcLeft, Math.max(0, Math.round(bottom - mirror / scale)), srcRight, srcBottom), new Rect(0, fadeRows, width, fadeRows + mirrorRows), p);
                c.restore();
                Utilities.stackBlurBitmap(out, Math.max(3, width / 5));
                Utilities.stackBlurBitmap(out, Math.max(3, width / 5));
                Paint mask = new Paint();
                mask.setShader(new LinearGradient(0, 0, 0, fadeRows, 0, 0xff000000, Shader.TileMode.CLAMP));
                mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
                c.drawRect(0, 0, width, fadeRows, mask);
                extensionFadeRows = fadeRows;
                return out;
            } catch (Throwable e) {
                return null;
            }
        }

        private void drawExtension(Canvas canvas, int w, int h, float alpha) {
            Bitmap bitmap = extension;
            if (bitmap == null || bitmap.isRecycled() || alpha <= 0f) {
                return;
            }
            int below = extensionBelow(h);
            int fade = extensionFade(h);
            int bw = bitmap.getWidth();
            extensionPaint.setAlpha((int) (255 * alpha));
            extensionSrc.set(0, 0, bw, extensionFadeRows);
            extensionDst.set(0, h - fade, w, h);
            canvas.drawBitmap(bitmap, extensionSrc, extensionDst, extensionPaint);
            extensionSrc.set(0, extensionFadeRows, bw, bitmap.getHeight());
            extensionDst.set(0, h, w, h + below);
            canvas.drawBitmap(bitmap, extensionSrc, extensionDst, extensionPaint);
            int surface = colors.surface;
            int key = ((surface * 31 + h) * 31 + below) * 31 + fade;
            if (shade == null || shadeKey != key) {
                shadeKey = key;
                float total = fade + below;
                float mid = Math.min(below, dp(150));
                shade = new LinearGradient(0, h - fade, 0, h + below, new int[]{
                        ColorUtils.setAlphaComponent(surface, 0),
                        ColorUtils.setAlphaComponent(surface, 0x4d),
                        ColorUtils.setAlphaComponent(surface, 0xa6),
                        ColorUtils.setAlphaComponent(surface, 0xcc)
                }, new float[]{0f, fade / total, (fade + mid) / total, 1f}, Shader.TileMode.CLAMP);
            }
            shadePaint.setShader(shade);
            shadePaint.setAlpha((int) (255 * alpha));
            canvas.drawRect(0, h - fade, w, h + below, shadePaint);
        }

        private void drawScrim(Canvas canvas, int w, int h, float alpha, boolean extended) {
            int surface = colors.surface;
            boolean full = coverStyle == PlayerStyle.COVER_FULL;
            int key = (((surface * 31 + h) * 31 + (full ? 1 : 0)) * 31 + (extended ? 1 : 0)) * 31 + (landscape ? columnLeft * 7 + w : 0);
            if (gradient == null || gradientKey != key) {
                gradientKey = key;
                sideGradient = null;
                if (landscape) {
                    gradient = new LinearGradient(0, 0, 0, h, new int[]{
                            ColorUtils.setAlphaComponent(surface, 0x8c),
                            ColorUtils.setAlphaComponent(surface, 0)
                    }, new float[]{0f, 0.3f}, Shader.TileMode.CLAMP);
                    float start = full ? Math.max(0f, (columnLeft - dp(180)) / (float) w) : 0.55f;
                    float end = full ? Math.min(1f, (columnLeft + dp(16)) / (float) w) : 1f;
                    sideGradient = new LinearGradient(0, 0, w, 0, new int[]{
                            ColorUtils.setAlphaComponent(surface, 0),
                            full ? ColorUtils.setAlphaComponent(surface, 0xe6) : surface
                    }, new float[]{start, end}, Shader.TileMode.CLAMP);
                } else if (full) {
                    gradient = new LinearGradient(0, 0, 0, h, new int[]{
                            ColorUtils.setAlphaComponent(surface, 0x99),
                            ColorUtils.setAlphaComponent(surface, 0),
                            ColorUtils.setAlphaComponent(surface, 0),
                            ColorUtils.setAlphaComponent(surface, 0xe6),
                            surface
                    }, new float[]{0f, 0.2f, 0.36f, 0.7f, 1f}, Shader.TileMode.CLAMP);
                } else if (extended) {
                    gradient = new LinearGradient(0, 0, 0, h, new int[]{
                            ColorUtils.setAlphaComponent(surface, 0x8c),
                            ColorUtils.setAlphaComponent(surface, 0)
                    }, new float[]{0f, 0.26f}, Shader.TileMode.CLAMP);
                } else {
                    gradient = new LinearGradient(0, 0, 0, h, new int[]{
                            ColorUtils.setAlphaComponent(surface, 0x8c),
                            ColorUtils.setAlphaComponent(surface, 0),
                            ColorUtils.setAlphaComponent(surface, 0),
                            surface
                    }, new float[]{0f, 0.26f, 0.62f, 1f}, Shader.TileMode.CLAMP);
                }
            }
            paint.setShader(gradient);
            paint.setAlpha((int) (255 * alpha));
            canvas.drawRect(0, 0, w, h, paint);
            if (sideGradient != null) {
                paint.setShader(sideGradient);
                paint.setAlpha((int) (255 * alpha));
                canvas.drawRect(0, 0, w, h, paint);
            }
            if (full && lyricsFraction > 0f) {
                paint.setShader(null);
                paint.setColor(ColorUtils.setAlphaComponent(surface, (int) (0xb8 * lyricsFraction * alpha)));
                canvas.drawRect(0, 0, w, h, paint);
            }
        }
    }

    private class PlayerLayout extends ViewGroup {

        private final Rect coverRect = new Rect();
        private final int[] blockY = new int[3];
        private int titleY;
        private int stageTop;
        private int stageHeight;
        private int stageLeft;
        private int stageWidth;
        private int columnWidth;
        private float gapScale = 1f;
        private int lastWidth;
        private int lastHeight;
        private int padLeft = dp(24);
        private int padRight = dp(24);
        private float tapX;
        private float tapY;
        private int tapTarget = PlayerStyleEditor.TARGET_NONE;

        PlayerLayout(Context context) {
            super(context);
            setClipChildren(false);
            setClipToPadding(false);
            setChildrenDrawingOrderEnabled(true);
        }

        @Override
        protected int getChildDrawingOrder(int childCount, int drawingPosition) {
            if (coverStyle == PlayerStyle.COVER_CARD || coverStyle == PlayerStyle.COVER_DISC) {
                return drawingPosition;
            }
            int coverIndex = indexOfChild(cover);
            int scrimIndex = indexOfChild(coverScrim);
            if (coverIndex < 0 || scrimIndex < 0) {
                return drawingPosition;
            }
            if (drawingPosition == 0) {
                return coverIndex;
            }
            if (drawingPosition == 1) {
                return scrimIndex;
            }
            int index = drawingPosition - 2;
            for (int i = 0; i < childCount; i++) {
                if (i == coverIndex || i == scrimIndex) {
                    continue;
                }
                if (index == 0) {
                    return i;
                }
                index--;
            }
            return drawingPosition;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (editing && styleEditor != null && editorFraction >= 1f) {
                return handleEditorTap(ev);
            }
            if (editing || editorFraction > 0f || (previewMode && !isEnabled())) {
                return false;
            }
            return super.dispatchTouchEvent(ev);
        }

        private boolean handleEditorTap(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    tapX = ev.getX();
                    tapY = ev.getY();
                    tapTarget = editorTargetAt(tapX, tapY);
                    return tapTarget != PlayerStyleEditor.TARGET_NONE;
                case MotionEvent.ACTION_MOVE:
                    float slop = ViewConfiguration.get(getContext()).getScaledTouchSlop() / Math.max(0.2f, getScaleX());
                    if (Math.abs(ev.getX() - tapX) > slop || Math.abs(ev.getY() - tapY) > slop) {
                        tapTarget = PlayerStyleEditor.TARGET_NONE;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (tapTarget != PlayerStyleEditor.TARGET_NONE) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        styleEditor.focus(tapTarget);
                    }
                    tapTarget = PlayerStyleEditor.TARGET_NONE;
                    return true;
                default:
                    tapTarget = PlayerStyleEditor.TARGET_NONE;
                    return true;
            }
        }

        private int editorTargetAt(float x, float y) {
            if (hit(group, x, y)) {
                return PlayerStyleEditor.TARGET_ACTIONS;
            }
            if (hit(controls, x, y)) {
                return PlayerStyleEditor.TARGET_CONTROLS;
            }
            if (hit(seekBar, x, y) || hit(timeRow, x, y)) {
                return PlayerStyleEditor.TARGET_PROGRESS;
            }
            if (hit(titleRow, x, y)) {
                return PlayerStyleEditor.TARGET_TITLE;
            }
            if (hit(cover, x, y)) {
                return PlayerStyleEditor.TARGET_COVER;
            }
            return PlayerStyleEditor.TARGET_NONE;
        }

        private boolean hit(View view, float x, float y) {
            return view.getVisibility() == VISIBLE && x >= view.getLeft() && x < view.getRight()
                    && y >= view.getTop() && y < view.getBottom();
        }

        @Override
        protected void dispatchDraw(@NonNull Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            boolean clip = editorFraction > 0f;
            if (clip) {
                float r = dp(26) / Math.max(0.2f, getScaleX());
                previewRect.set(0, 0, w, h);
                previewPath.rewind();
                previewPath.addRoundRect(previewRect, r, r, Path.Direction.CW);
                canvas.save();
                canvas.clipPath(previewPath);
                overlayPaint.setShader(null);
                overlayPaint.setColor(colors.surface);
                canvas.drawRect(previewRect, overlayPaint);
            }
            if (!morphUnderlay) {
                drawBackdrop(canvas, w, h);
            }
            super.dispatchDraw(canvas);
            if (clip) {
                canvas.restore();
                float scale = Math.max(0.2f, getScaleX());
                float r = dp(26) / scale;
                overlayPaint.setShader(null);
                overlayPaint.setStyle(Paint.Style.STROKE);
                overlayPaint.setStrokeWidth(dp(1) / scale);
                overlayPaint.setColor(ColorUtils.setAlphaComponent(colors.outlineVariant, (int) (255 * editorFraction)));
                canvas.drawRoundRect(previewRect, r, r, overlayPaint);
                overlayPaint.setStyle(Paint.Style.FILL);
            }
        }

        private int gapBefore(int block) {
            switch (block) {
                case PlayerStyle.BLOCK_PROGRESS:
                    return Math.round(dp(6) * gapScale);
                case PlayerStyle.BLOCK_CONTROLS:
                    return Math.round(dp(12) * gapScale);
                default:
                    return Math.round(dp(16) * gapScale);
            }
        }

        private int heightOf(int block) {
            switch (block) {
                case PlayerStyle.BLOCK_PROGRESS:
                    return dp(40) + timeRow.getMeasuredHeight();
                case PlayerStyle.BLOCK_CONTROLS:
                    return controls.getMeasuredHeight();
                default:
                    return dp(islandActions ? 56 : 52);
            }
        }

        private boolean visible(int block) {
            return block != PlayerStyle.BLOCK_ACTIONS || group.getVisibility() != GONE;
        }

        private int lowerHeight() {
            int sum = 0;
            for (int block : blockOrder) {
                if (visible(block)) {
                    sum += gapBefore(block) + heightOf(block);
                }
            }
            return sum;
        }

        private void placeLower(int startY) {
            int y = startY;
            for (int block : blockOrder) {
                if (!visible(block)) {
                    continue;
                }
                y += gapBefore(block);
                blockY[block] = y;
                y += heightOf(block);
            }
        }

        private void computeColumns(int width, int height) {
            int contentW = Math.max(0, width - padLeft - padRight);
            landscape = width > height && height < dp(600);
            gapScale = landscape ? 0.6f : 1f;
            if (!landscape) {
                columnLeft = padLeft;
                columnWidth = contentW;
                stageLeft = padLeft;
                stageWidth = contentW;
                return;
            }
            int split = coverStyle == PlayerStyle.COVER_WIDE ? Math.round(width * 0.46f) : padLeft + Math.round(contentW * 0.46f);
            columnLeft = split + dp(28);
            columnWidth = Math.max(0, width - padRight - columnLeft);
            stageLeft = padLeft;
            stageWidth = Math.max(0, split - padLeft);
        }

        private void computeLandscape(int width, int height, int headerBottom, int bottom) {
            int titleH = titleRow.getMeasuredHeight();
            int areaTop = headerBottom;
            int areaBottom = height - bottom - dp(16);
            int areaH = Math.max(0, areaBottom - areaTop);
            if (titleH + lowerHeight() > areaH) {
                gapScale = 0.3f;
            }
            int column = titleH + lowerHeight();
            titleY = areaTop + Math.max(0, (areaH - column) / 2);
            placeLower(titleY + titleH);
            stageTop = areaTop;
            stageHeight = areaH;
            if (coverStyle == PlayerStyle.COVER_FULL) {
                coverRect.set(0, 0, width, height);
            } else if (coverStyle == PlayerStyle.COVER_WIDE) {
                coverRect.set(0, 0, columnLeft - dp(28), height);
            } else {
                int size = Math.max(dp(96), Math.round(Math.min(areaH, stageWidth) * PlayerStyle.coverSize()));
                int left = stageLeft + (stageWidth - size) / 2;
                int coverTop = areaTop + (areaH - size) / 2;
                coverRect.set(left, coverTop, left + size, coverTop + size);
            }
        }

        private void compute(int width, int height) {
            int top = getStatusBarHeight();
            int bottom = getBottomInset();
            int contentW = Math.max(0, width - padLeft - padRight);
            int titleH = titleRow.getMeasuredHeight();
            int headerBottom = top + dp(56);
            if (landscape) {
                computeLandscape(width, height, headerBottom, bottom);
                return;
            }
            int bottomY = height - bottom - dp(28);
            int lower = lowerHeight();
            int style = coverStyle;
            if (style == PlayerStyle.COVER_FULL) {
                coverRect.set(0, 0, width, height);
                int y = bottomY;
                for (int i = blockOrder.length - 1; i >= 0; i--) {
                    int block = blockOrder[i];
                    if (!visible(block)) {
                        continue;
                    }
                    y -= heightOf(block);
                    blockY[block] = y;
                    y -= gapBefore(block);
                }
                titleY = y - dp(16) - titleH;
                stageTop = headerBottom;
                stageHeight = Math.max(0, y - headerBottom);
                return;
            }
            if (style == PlayerStyle.COVER_WIDE) {
                int maxH = bottomY - lower - titleH - dp(4);
                int coverH = Math.max(Math.min(width, dp(180)), Math.min(width * 6 / 5, maxH));
                coverRect.set(0, 0, width, coverH);
                titleY = Math.max(coverH + dp(4), bottomY - lower - titleH);
                stageTop = headerBottom;
                stageHeight = Math.max(0, titleY + titleH - headerBottom);
                placeLower(titleY + titleH);
                return;
            }
            int available = bottomY - headerBottom - lower;
            int coverAvail = available - dp(16) - dp(26) - titleH;
            int size = Math.max(dp(96), Math.min(Math.round(contentW * PlayerStyle.coverSize()), coverAvail));
            stageHeight = dp(16) + size + dp(26) + titleH;
            int free = available - stageHeight;
            stageTop = headerBottom + Math.max(0, free / 2);
            int coverTop;
            if (PlayerStyle.titleAbove()) {
                titleY = stageTop + dp(16);
                coverTop = titleY + titleH + dp(22);
            } else {
                coverTop = stageTop + dp(16);
                titleY = coverTop + size + dp(26);
            }
            int coverLeft = padLeft + (contentW - size) / 2;
            coverRect.set(coverLeft, coverTop, coverLeft + size, coverTop + size);
            placeLower(Math.max(stageTop + stageHeight, bottomY - lower));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            padLeft = dp(24) + PlayerSheet.super.getLeftInset();
            padRight = dp(24) + PlayerSheet.super.getRightInset();
            int contentW = Math.max(0, width - padLeft - padRight);
            computeColumns(width, height);
            int exactW = MeasureSpec.makeMeasureSpec(columnWidth, MeasureSpec.EXACTLY);
            header.measure(MeasureSpec.makeMeasureSpec(contentW + dp(24), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(56), MeasureSpec.EXACTLY));
            seekBar.measure(exactW, MeasureSpec.makeMeasureSpec(dp(40), MeasureSpec.EXACTLY));
            timeRow.measure(exactW, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            controls.measure(exactW, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            group.measure(islandActions ? MeasureSpec.makeMeasureSpec(columnWidth, MeasureSpec.AT_MOST) : exactW,
                    MeasureSpec.makeMeasureSpec(heightOf(PlayerStyle.BLOCK_ACTIONS), MeasureSpec.EXACTLY));
            titleRow.measure(exactW, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            bubble.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            compute(width, height);
            cover.measure(MeasureSpec.makeMeasureSpec(coverRect.width(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(coverRect.height(), MeasureSpec.EXACTLY));
            coverScrim.measure(MeasureSpec.makeMeasureSpec(coverRect.width(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(coverRect.height(), MeasureSpec.EXACTLY));
            lyricsPanel.measure(MeasureSpec.makeMeasureSpec(stageWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(stageHeight, MeasureSpec.EXACTLY));
            if (coverStyle == PlayerStyle.COVER_DISC) {
                int r = coverRect.width() / 2;
                if (cover.getRadius() != r) {
                    cover.setRadius(r);
                    cover.invalidateOutline();
                }
            }
            if (lastWidth != width || lastHeight != height) {
                lastWidth = width;
                lastHeight = height;
                backgroundGradient = null;
                coverScrim.invalidate();
            }
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int top = getStatusBarHeight();
            int x = columnLeft;
            int w = columnWidth;
            header.layout(padLeft - dp(12), top, padLeft - dp(12) + header.getMeasuredWidth(), top + dp(56));
            cover.layout(coverRect.left, coverRect.top, coverRect.right, coverRect.bottom);
            coverScrim.layout(coverRect.left, coverRect.top, coverRect.right, coverRect.bottom);
            titleRow.layout(x, titleY, x + w, titleY + titleRow.getMeasuredHeight());
            lyricsPanel.layout(stageLeft, stageTop, stageLeft + stageWidth, stageTop + stageHeight);
            int seekTop = blockY[PlayerStyle.BLOCK_PROGRESS];
            seekBar.layout(x, seekTop, x + w, seekTop + dp(40));
            int bubbleTop = seekTop - dp(36);
            bubble.layout(x, bubbleTop, x + bubble.getMeasuredWidth(), bubbleTop + bubble.getMeasuredHeight());
            int timeTop = seekTop + dp(40);
            timeRow.layout(x, timeTop, x + w, timeTop + timeRow.getMeasuredHeight());
            int controlsTop = blockY[PlayerStyle.BLOCK_CONTROLS];
            controls.layout(x, controlsTop, x + w, controlsTop + controls.getMeasuredHeight());
            if (group.getVisibility() != GONE) {
                int groupTop = blockY[PlayerStyle.BLOCK_ACTIONS];
                int groupLeft = x + (w - group.getMeasuredWidth()) / 2;
                group.layout(groupLeft, groupTop, groupLeft + group.getMeasuredWidth(), groupTop + group.getMeasuredHeight());
            }
            if (seeking) {
                layoutBubble();
            }
        }
    }
}
