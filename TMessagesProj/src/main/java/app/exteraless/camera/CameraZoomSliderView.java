package app.exteraless.camera;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;

import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.util.Arrays;
import java.util.Locale;

/**
 * Ползунок зума камеры в духе системной камеры Pixel: перенос
 * {@code com.exteragram.messenger.camera.CameraZoomSliderView} из exteraGram 12.9.0.
 *
 * У вида два состояния. Сжатое — ряд круглых делений («.5», «1», «2», «5»), выбранное
 * подсвечено кружком. Развёрнутое — линейка с засечками, подписями на опорных значениях
 * и пузырём текущего зума сверху; в неё переходят долгим нажатием или горизонтальным
 * протягиванием, обратно — само по таймауту.
 *
 * Шкала логарифмическая: {@link #zoomToTick} и {@link #tickToZoom} переводят кратность в
 * позицию засечки и обратно, причём единица закреплена за отдельной засечкой
 * ({@code oneXTick}), чтобы «1×» не уезжала между делениями на камерах с шириком.
 *
 * Подложку рисует наследник — {@link #drawPillBackground}.
 */
public abstract class CameraZoomSliderView extends View {

    public interface OnZoomChangeListener {
        void onZoomChanged(float zoom);
    }

    private static final double LOG_2 = Math.log(2.0);
    private static final TimeInterpolator ZOOM_INTERPOLATOR = new ZoomLookupInterpolator();
    private static final TimeInterpolator MORPH_INTERPOLATOR = new CubicBezierInterpolator(0.4f, 0f, 0.2f, 1f);
    private static final PorterDuffXfermode XOR_XFERMODE = new PorterDuffXfermode(PorterDuff.Mode.XOR);
    private static final PorterDuffXfermode DST_OVER_XFERMODE = new PorterDuffXfermode(PorterDuff.Mode.DST_OVER);
    private static final PorterDuffXfermode DST_IN_XFERMODE = new PorterDuffXfermode(PorterDuff.Mode.DST_IN);

    private static final int TOGGLE_SIZE_DP = 48;
    private static final int EXPANDED_BACKGROUND_WIDTH_DP = 288;
    private static final int EXPANDED_RULER_WIDTH_DP = 258;
    private static final long AUTO_COLLAPSE_DELAY = 1500L;
    private static final long MORPH_DURATION = 217L;

    private static final FloatPropertyCompat<CameraZoomSliderView> CONTROL_WIDTH =
        new FloatPropertyCompat<CameraZoomSliderView>("controlWidth") {
            @Override
            public float getValue(CameraZoomSliderView view) {
                return view.animatedControlWidth;
            }

            @Override
            public void setValue(CameraZoomSliderView view, float value) {
                view.animatedControlWidth = Math.max(0f, value);
                view.invalidate();
            }
        };

    private static final FloatPropertyCompat<CameraZoomSliderView> SELECTOR_OFFSET =
        new FloatPropertyCompat<CameraZoomSliderView>("selectorOffset") {
            @Override
            public float getValue(CameraZoomSliderView view) {
                return view.animatedSelectorOffset;
            }

            @Override
            public void setValue(CameraZoomSliderView view, float value) {
                view.animatedSelectorOffset = value;
                view.invalidate();
            }
        };

    /** Таблица разгона анимации зума: у экстеры это ровно такой же массив из 201 точки. */
    public static final class ZoomLookupInterpolator implements TimeInterpolator {

        private static final float[] VALUES = {
            0f, 8.0E-4f, 0.0016f, 0.0024f, 0.0032f, 0.0057f, 0.0083f, 0.0109f, 0.0134f, 0.0171f,
            0.0218f, 0.0266f, 0.0313f, 0.036f, 0.0431f, 0.0506f, 0.0581f, 0.0656f, 0.0733f, 0.0835f,
            0.0937f, 0.1055f, 0.1179f, 0.1316f, 0.1466f, 0.1627f, 0.181f, 0.2003f, 0.2226f, 0.2468f,
            0.2743f, 0.306f, 0.3408f, 0.3852f, 0.4317f, 0.4787f, 0.5177f, 0.5541f, 0.5834f, 0.6123f,
            0.6333f, 0.6542f, 0.6739f, 0.6887f, 0.7035f, 0.7183f, 0.7308f, 0.7412f, 0.7517f, 0.7621f,
            0.7725f, 0.7805f, 0.7879f, 0.7953f, 0.8027f, 0.8101f, 0.8175f, 0.823f, 0.8283f, 0.8336f,
            0.8388f, 0.8441f, 0.8494f, 0.8546f, 0.8592f, 0.863f, 0.8667f, 0.8705f, 0.8743f, 0.878f,
            0.8818f, 0.8856f, 0.8893f, 0.8927f, 0.8953f, 0.898f, 0.9007f, 0.9034f, 0.9061f, 0.9087f,
            0.9114f, 0.9141f, 0.9168f, 0.9194f, 0.9218f, 0.9236f, 0.9255f, 0.9274f, 0.9293f, 0.9312f,
            0.9331f, 0.935f, 0.9368f, 0.9387f, 0.9406f, 0.9425f, 0.9444f, 0.946f, 0.9473f, 0.9486f,
            0.9499f, 0.9512f, 0.9525f, 0.9538f, 0.9551f, 0.9564f, 0.9577f, 0.959f, 0.9603f, 0.9616f,
            0.9629f, 0.9642f, 0.9654f, 0.9663f, 0.9672f, 0.968f, 0.9689f, 0.9697f, 0.9706f, 0.9715f,
            0.9723f, 0.9732f, 0.9741f, 0.9749f, 0.9758f, 0.9766f, 0.9775f, 0.9784f, 0.9792f, 0.9801f,
            0.9808f, 0.9813f, 0.9819f, 0.9824f, 0.9829f, 0.9835f, 0.984f, 0.9845f, 0.985f, 0.9856f,
            0.9861f, 0.9866f, 0.9872f, 0.9877f, 0.9882f, 0.9887f, 0.9893f, 0.9898f, 0.9903f, 0.9909f,
            0.9914f, 0.9917f, 0.992f, 0.9922f, 0.9925f, 0.9928f, 0.9931f, 0.9933f, 0.9936f, 0.9939f,
            0.9942f, 0.9944f, 0.9947f, 0.995f, 0.9953f, 0.9955f, 0.9958f, 0.9961f, 0.9964f, 0.9966f,
            0.9969f, 0.9972f, 0.9975f, 0.9977f, 0.9979f, 0.9981f, 0.9982f, 0.9983f, 0.9984f, 0.9986f,
            0.9987f, 0.9988f, 0.9989f, 0.9991f, 0.9992f, 0.9993f, 0.9994f, 0.9995f, 0.9995f, 0.9996f,
            0.9996f, 0.9997f, 0.9997f, 0.9997f, 0.9998f, 0.9998f, 0.9998f, 0.9999f, 0.9999f, 1f, 1f
        };

        @Override
        public float getInterpolation(float input) {
            if (input <= 0f) {
                return 0f;
            }
            if (input >= 1f) {
                return 1f;
            }
            int index = Math.min((int) (200f * input), 199);
            float fraction = (input - index * 0.005f) / 0.005f;
            float value = VALUES[index];
            return value + fraction * (VALUES[index + 1] - value);
        }
    }

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint toggleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint selectedToggleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rulerLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint edgeFadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);

    private final RectF controlBounds = new RectF();
    private final RectF compactBounds = new RectF();
    private final RectF rulerBounds = new RectF();
    private final RectF compactTouchBounds = new RectF();
    private final RectF rulerTouchBounds = new RectF();
    private final RectF selectorBounds = new RectF();
    private final RectF bubbleBounds = new RectF();

    private final SparseArray<String> primaryLabels = new SparseArray<>();
    private final int touchSlop;
    private final SpringAnimation widthSpring;
    private final SpringAnimation selectorSpring;

    private float minZoom = 0.5f;
    private float maxZoom = 30f;
    private float zoom = 1f;
    private float[] toggleStops = {0.5f, 1f, 2f, 5f};
    private float[] rulerStops = {0.5f, 1f, 2f, 5f, 10f, 30f};
    private int[] primaryTickIndices = new int[0];
    private int oneXTick = -1;
    private int intervalCount;
    private float tickSpacing;
    private float displayNormalizationFactor = 1f;

    private int protectionBackgroundColor = 0x99000000;
    private int primaryColor = 0xffa8b0ba;
    private int minorTickColor = 0xffe3e8e3;
    private int secondaryFixedColor = 0xffd7dee7;
    private int onSecondaryFixedColor = 0xff1a2b45;
    private int unselectedToggleColor = 0xffffffff;

    private float animatedControlWidth;
    private float animatedSelectorOffset;
    private float expandedProgress;
    private ValueAnimator expandedAnimator;
    private ValueAnimator zoomAnimator;
    private boolean expanded;
    private boolean externalZoomGesture;

    private int selectedToggleIndex;
    private boolean selectedShowsStopValue;
    private float pendingConfigurationSelectorX = Float.NaN;

    private boolean dragging;
    private boolean compactGestureDown;
    private boolean dragStartedFromCompact;
    private boolean movedPastSlop;
    private int pressedToggleIndex = -1;
    private float downX;
    private float downY;
    private float lastTouchX;
    private float dragTick;
    private int dragPrimarySegment = Integer.MIN_VALUE;
    private int lastHapticTick = Integer.MIN_VALUE;
    private int stickyTick = -1;
    private float stickyDistance;
    private float stickyFactor;
    private VelocityTracker velocityTracker;

    private OnZoomChangeListener onZoomChangeListener;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (!compactGestureDown || movedPastSlop || expanded) {
                return;
            }
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            dragStartedFromCompact = true;
            beginDrag(downX);
            setExpanded(true, true);
        }
    };

    private final Runnable autoCollapseRunnable = new Runnable() {
        @Override
        public void run() {
            if (!expanded || dragging || externalZoomGesture) {
                return;
            }
            setExpanded(false, true);
        }
    };

    public CameraZoomSliderView(Context context) {
        this(context, null);
    }

    public CameraZoomSliderView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CameraZoomSliderView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        tickSpacing = Math.max(1f, Math.round(AndroidUtilities.dp(8f)));
        widthSpring = createSpring(CONTROL_WIDTH, 1f, 3800f, AndroidUtilities.dp(0.1f));
        selectorSpring = createSpring(SELECTOR_OFFSET, 0.8f, 800f, 1f);
        configurePaints();
        rebuildScale();
        selectedToggleIndex = findToggleSegment(zoom);
        animatedSelectorOffset = getSelectorOffset(selectedToggleIndex);
        animatedControlWidth = getCompactWidth();
        setClickable(true);
        setFocusable(true);
        updateAccessibilityDescription();
    }

    public abstract boolean drawPillBackground(Canvas canvas, RectF bounds, float radius);

    // ---------- геометрия шкалы ----------

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Позиция засечки для кратности. Отрезок до единицы и после неё масштабируются
     * независимо, поэтому «1×» всегда попадает ровно в {@code oneXTick}.
     */
    private float zoomToTick(float value) {
        final float clamped = clamp(value, minZoom, maxZoom);
        if (clamped <= minZoom) {
            return 0f;
        }
        if (clamped >= maxZoom) {
            return intervalCount;
        }
        if (oneXTick >= 0) {
            if (clamped == displayNormalizationFactor) {
                return oneXTick;
            }
            if (clamped > displayNormalizationFactor) {
                final float progress = (float) (Math.log(clamped / displayNormalizationFactor)
                    / Math.log(maxZoom / displayNormalizationFactor));
                return oneXTick + progress * (intervalCount - oneXTick);
            }
            return (float) (Math.log(clamped / minZoom) / Math.log(displayNormalizationFactor / minZoom)) * oneXTick;
        }
        return (float) (Math.log(clamped / minZoom) / Math.log(maxZoom / minZoom)) * intervalCount;
    }

    private float tickToZoom(float tick) {
        final float clamped = clamp(tick, 0f, intervalCount);
        if (clamped <= 0f) {
            return minZoom;
        }
        if (clamped >= intervalCount) {
            return maxZoom;
        }
        if (oneXTick < 0) {
            return (float) (minZoom * Math.exp(Math.log(maxZoom / minZoom) * (clamped / intervalCount)));
        }
        if (clamped == oneXTick) {
            return displayNormalizationFactor;
        }
        if (clamped <= oneXTick) {
            return (float) (minZoom
                * Math.exp(Math.log(displayNormalizationFactor / minZoom) * (clamped / oneXTick)));
        }
        return (float) (displayNormalizationFactor
            * Math.exp(Math.log(maxZoom / displayNormalizationFactor) * ((clamped - oneXTick) / (float) (intervalCount - oneXTick))));
    }

    private void rebuildScale() {
        final float octaves = (float) (Math.log(maxZoom / minZoom) / LOG_2);
        if (minZoom >= displayNormalizationFactor || maxZoom < displayNormalizationFactor) {
            oneXTick = -1;
            intervalCount = Math.max(1, Math.round(octaves * 5f));
        } else {
            oneXTick = Math.max(3, Math.round((float) (Math.log(displayNormalizationFactor / minZoom) / LOG_2) * 5f));
            intervalCount = oneXTick + (maxZoom > displayNormalizationFactor
                ? Math.max(1, Math.round((float) (Math.log(maxZoom / displayNormalizationFactor) / LOG_2) * 5f))
                : 0);
        }
        primaryLabels.clear();
        final int[] indices = new int[rulerStops.length];
        int count = 0;
        for (float stop : rulerStops) {
            if (stop < minZoom || stop > maxZoom) {
                continue;
            }
            final int tick = Math.round(zoomToTick(stop));
            primaryLabels.put(tick, formatRuler(stop));
            boolean known = false;
            for (int i = 0; i < count; i++) {
                if (indices[i] == tick) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                indices[count++] = tick;
            }
        }
        primaryTickIndices = Arrays.copyOf(indices, count);
        Arrays.sort(primaryTickIndices);
    }

    private static float[] sanitizeStops(float[] stops, float min, float max) {
        if (stops == null || stops.length == 0) {
            return new float[0];
        }
        final float[] kept = new float[stops.length];
        int count = 0;
        for (float stop : stops) {
            if (Float.isFinite(stop) && stop >= min && stop <= max) {
                kept[count++] = stop;
            }
        }
        float[] sorted = Arrays.copyOf(kept, count);
        Arrays.sort(sorted);
        if (sorted.length < 2) {
            return sorted;
        }
        int unique = 1;
        for (int i = 1; i < sorted.length; i++) {
            if (Float.compare(sorted[i], sorted[unique - 1]) != 0) {
                sorted[unique++] = sorted[i];
            }
        }
        return Arrays.copyOf(sorted, unique);
    }

    private int findToggleSegment(float value) {
        if (toggleStops.length == 0) {
            return -1;
        }
        int segment = 0;
        for (int i = 1; i < toggleStops.length && value >= toggleStops[i]; i++) {
            segment = i;
        }
        return segment;
    }

    private int findToggleIndexAt(float x) {
        if (x < compactBounds.left || x > compactBounds.right || toggleStops.length == 0) {
            return -1;
        }
        final int index = (int) ((x - compactBounds.left) / AndroidUtilities.dp(TOGGLE_SIZE_DP));
        return Math.max(0, Math.min(toggleStops.length - 1, index));
    }

    /**
     * Номер засечки-«якоря», в чьём отрезке сейчас палец. Направление движения нужно,
     * чтобы на самой засечке отрезок не дребезжал: при движении вправо она считается
     * началом следующего отрезка, при движении влево — концом предыдущего.
     */
    private int findPrimarySegment(float tick, float delta) {
        if (primaryTickIndices.length == 0) {
            return Integer.MIN_VALUE;
        }
        final float first = primaryTickIndices[0];
        if (tick < first || (tick == first && delta >= 0f)) {
            return -1;
        }
        int i = 0;
        while (i < primaryTickIndices.length - 1) {
            final float next = primaryTickIndices[i + 1];
            if (delta < 0f ? tick < next : tick <= next) {
                return primaryTickIndices[i];
            }
            i++;
        }
        return primaryTickIndices[primaryTickIndices.length - 1];
    }

    // ---------- подписи ----------

    private float normalizeDisplayZoom(float value) {
        final float normalized = value / displayNormalizationFactor;
        float tenths = normalized * 10f;
        if (normalized < 1f) {
            return (float) Math.round(tenths) / 10f;
        }
        final float floor = (float) Math.floor(tenths);
        if (floor % 5f == 0f) {
            tenths = floor;
        } else {
            final float ceil = (float) Math.ceil(tenths);
            if (ceil % 5f == 0f) {
                tenths = ceil;
            }
        }
        final float rounded = (float) Math.rint(tenths) / 10f;
        return rounded % 1f == 0f || rounded >= 8f ? (float) Math.rint(normalized) : rounded;
    }

    private String formatZoomNumber(float value) {
        final float normalized = normalizeDisplayZoom(value);
        if (normalized % 1f == 0f) {
            return String.format(Locale.US, "%.0f", normalized);
        }
        return String.format(Locale.US, "%.1f", normalized);
    }

    private String formatBubble(float value) {
        return formatZoomNumber(value) + "×";
    }

    private String formatRuler(float value) {
        return formatZoomNumber(value);
    }

    private String formatToggle(float value) {
        return formatZoomNumber(value);
    }

    private String getToggleLabel(int index) {
        if (index == selectedToggleIndex) {
            return formatBubble(selectedShowsStopValue ? toggleStops[index] : zoom);
        }
        return formatToggle(toggleStops[index]);
    }

    // ---------- размеры ----------

    private float getCompactWidth() {
        return AndroidUtilities.dp(TOGGLE_SIZE_DP) * toggleStops.length;
    }

    private float getExpandedBackgroundWidth() {
        return AndroidUtilities.dp(EXPANDED_BACKGROUND_WIDTH_DP);
    }

    private float getExpandedRulerWidth() {
        return AndroidUtilities.dp(EXPANDED_RULER_WIDTH_DP);
    }

    private float getSelectorOffset(int index) {
        return AndroidUtilities.dp(TOGGLE_SIZE_DP) * Math.max(0, index);
    }

    private float getBubbleHeight() {
        final Paint.FontMetricsInt metrics = bubbleTextPaint.getFontMetricsInt();
        return (metrics.descent - metrics.ascent) + AndroidUtilities.dp(6f) * 2f;
    }

    private float centeredChildLeft(float available, float width) {
        return getPaddingLeft() + (int) ((available - width) / 2f);
    }

    private void updateLayoutBounds() {
        final float available = Math.max(0f, getWidth() - getPaddingLeft() - getPaddingRight());
        final float usable = Math.max(0f, available - AndroidUtilities.dp(8f) * 2f);
        final float bottom = getHeight() - getPaddingBottom();
        final float top = bottom - AndroidUtilities.dp(64f);
        final float controlBottom = top + AndroidUtilities.dp(TOGGLE_SIZE_DP);

        final float expandedWidth = Math.min(usable, Math.round(getExpandedBackgroundWidth()));
        final float compactWidth = Math.min(usable, Math.round(getCompactWidth()));
        if (animatedControlWidth <= 0f) {
            animatedControlWidth = expanded ? expandedWidth : compactWidth;
        }

        final float currentWidth = Math.min(usable, Math.round(animatedControlWidth));
        final float currentLeft = centeredChildLeft(available, currentWidth);
        controlBounds.set(currentLeft, top, currentLeft + currentWidth, controlBottom);

        final float compactLeft = centeredChildLeft(available, compactWidth);
        compactBounds.set(compactLeft, top, compactLeft + compactWidth, controlBottom);

        final float rulerWidth = Math.min(usable, getExpandedRulerWidth());
        final float rulerLeft = centeredChildLeft(available, rulerWidth);
        rulerBounds.set(rulerLeft, top, rulerLeft + rulerWidth, bottom);

        compactTouchBounds.set(compactBounds);
        compactTouchBounds.bottom = bottom;
        rulerTouchBounds.set(rulerBounds);
    }

    // ---------- отрисовка ----------

    private SpringAnimation createSpring(FloatPropertyCompat<CameraZoomSliderView> property,
                                         float damping, float stiffness, float minimumVisibleChange) {
        final SpringAnimation animation = new SpringAnimation(this, property);
        animation.setSpring(new SpringForce().setDampingRatio(damping).setStiffness(stiffness));
        animation.setMinimumVisibleChange(minimumVisibleChange);
        return animation;
    }

    private void configurePaints() {
        backgroundPaint.setStyle(Paint.Style.FILL);
        selectorPaint.setStyle(Paint.Style.FILL);
        bubblePaint.setStyle(Paint.Style.FILL);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);
        markerPaint.setStrokeCap(Paint.Cap.ROUND);
        toggleTextPaint.setTextAlign(Paint.Align.CENTER);
        toggleTextPaint.setTextSize(AndroidUtilities.dp(14f));
        toggleTextPaint.setTypeface(AndroidUtilities.bold());
        selectedToggleTextPaint.setTextAlign(Paint.Align.CENTER);
        selectedToggleTextPaint.setTextSize(AndroidUtilities.dp(16f));
        selectedToggleTextPaint.setTypeface(AndroidUtilities.bold());
        rulerLabelPaint.setTextAlign(Paint.Align.CENTER);
        rulerLabelPaint.setTextSize(AndroidUtilities.dp(11f));
        rulerLabelPaint.setTypeface(AndroidUtilities.bold());
        bubbleTextPaint.setTextAlign(Paint.Align.CENTER);
        bubbleTextPaint.setTextSize(AndroidUtilities.dp(16f));
        bubbleTextPaint.setTypeface(AndroidUtilities.bold());
        configurePaintColors();
    }

    private void configurePaintColors() {
        tickPaint.setColor(minorTickColor);
        markerPaint.setColor(primaryColor);
        rulerLabelPaint.setColor(primaryColor);
        selectorPaint.setColor(secondaryFixedColor);
        bubblePaint.setColor(secondaryFixedColor);
        bubbleTextPaint.setColor(onSecondaryFixedColor);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        updateLayoutBounds();
        final boolean pillDrawn = !controlBounds.isEmpty()
            && drawPillBackground(canvas, controlBounds, AndroidUtilities.dp(24f));
        final float compactAlpha = clamp(1f - expandedProgress, 0f, 1f);
        final float expandedAlpha = clamp(expandedProgress, 0f, 1f);
        final int layer = canvas.saveLayer(0f, 0f, getWidth(), getHeight(), null);
        if (compactAlpha > 0.001f) {
            final int save = canvas.saveLayerAlpha(0f, 0f, getWidth(), getHeight(), Math.round(compactAlpha * 255f));
            drawToggleRow(canvas, 1f);
            canvas.restoreToCount(save);
        }
        if (expandedAlpha > 0.001f) {
            final int save = canvas.saveLayerAlpha(0f, 0f, getWidth(), getHeight(), Math.round(expandedAlpha * 255f));
            drawBubble(canvas, 1f);
            drawRuler(canvas, 1f);
            canvas.restoreToCount(save);
        }
        if (!pillDrawn) {
            drawProtectionBackground(canvas);
        }
        canvas.restoreToCount(layer);
    }

    private void drawProtectionBackground(Canvas canvas) {
        if (controlBounds.isEmpty()) {
            return;
        }
        backgroundPaint.setColor(protectionBackgroundColor);
        backgroundPaint.setXfermode(DST_OVER_XFERMODE);
        canvas.drawRoundRect(controlBounds, AndroidUtilities.dp(24f), AndroidUtilities.dp(24f), backgroundPaint);
        backgroundPaint.setXfermode(null);
    }

    private void drawToggleRow(Canvas canvas, float alpha) {
        if (toggleStops.length == 0 || compactBounds.isEmpty()) {
            return;
        }
        final float inset = AndroidUtilities.dp(2f);
        final float size = AndroidUtilities.dp(44f);
        final float left = compactBounds.left + Math.round(animatedSelectorOffset) + inset;
        selectorBounds.set(left, compactBounds.top + inset, left + size, compactBounds.top + inset + size);
        drawToggleLabels(canvas, alpha, unselectedToggleColor);
        if (expanded) {
            return;
        }
        selectorPaint.setColor(Theme.multAlpha(secondaryFixedColor, alpha));
        selectorPaint.setXfermode(XOR_XFERMODE);
        canvas.drawOval(selectorBounds, selectorPaint);
        selectorPaint.setColor(Theme.multAlpha(onSecondaryFixedColor, alpha));
        selectorPaint.setXfermode(DST_OVER_XFERMODE);
        canvas.drawOval(selectorBounds, selectorPaint);
        selectorPaint.setXfermode(null);
    }

    private void drawToggleLabels(Canvas canvas, float alpha, int color) {
        for (int i = 0; i < toggleStops.length; i++) {
            final Paint paint = i == selectedToggleIndex ? selectedToggleTextPaint : toggleTextPaint;
            paint.setColor(Theme.multAlpha(color, alpha));
            final float cx = compactBounds.left + (i + 0.5f) * AndroidUtilities.dp(TOGGLE_SIZE_DP);
            final Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
            final int textHeight = metrics.descent - metrics.ascent;
            final float baseline = compactBounds.top + (int) ((compactBounds.height() - textHeight) / 2f) - metrics.ascent;
            canvas.drawText(getToggleLabel(i), cx, baseline, paint);
        }
    }

    private void drawRuler(Canvas canvas, float alpha) {
        if (rulerBounds.isEmpty()) {
            return;
        }
        final int layer = canvas.saveLayer(rulerBounds.left, controlBounds.top, rulerBounds.right, controlBounds.bottom, null);
        canvas.clipRect(rulerBounds.left, controlBounds.top, rulerBounds.right, controlBounds.bottom);

        final float centerX = rulerBounds.centerX();
        final float currentTick = zoomToTick(zoom);
        final float ticksBottom = controlBounds.bottom - AndroidUtilities.dp(22f);
        final float markerBottom = controlBounds.bottom - AndroidUtilities.dp(23f);
        final float halfTicks = (rulerBounds.width() / 2f) / tickSpacing;
        int from = Math.max(0, (int) Math.floor(currentTick - halfTicks) - 1);
        final int to = Math.min(intervalCount, (int) Math.ceil(currentTick + halfTicks) + 1);
        final Paint.FontMetricsInt metrics = rulerLabelPaint.getFontMetricsInt();
        final float labelBaseline = controlBounds.bottom - AndroidUtilities.dp(4f)
            - (metrics.descent - metrics.ascent) - metrics.ascent;

        for (; from <= to; from++) {
            final float x = centerX + (from - currentTick) * tickSpacing;
            final String label = primaryLabels.get(from);
            final boolean primary = label != null;
            tickPaint.setColor(Theme.multAlpha(primary ? primaryColor : minorTickColor, alpha));
            tickPaint.setStrokeWidth(AndroidUtilities.dp(1f));
            canvas.drawLine(x, ticksBottom - AndroidUtilities.dp(primary ? 12f : 6f), x, ticksBottom, tickPaint);
            if (primary) {
                rulerLabelPaint.setColor(Theme.multAlpha(primaryColor, alpha));
                canvas.drawText(label, x, labelBaseline, rulerLabelPaint);
            }
        }

        markerPaint.setColor(Theme.multAlpha(primaryColor, alpha));
        markerPaint.setStrokeWidth(AndroidUtilities.dp(4f));
        canvas.drawLine(centerX, markerBottom - AndroidUtilities.dp(12f), centerX, markerBottom, markerPaint);

        edgeFadePaint.setShader(new LinearGradient(rulerBounds.left, 0f, rulerBounds.right, 0f,
            new int[]{0x33000000, 0xff000000, 0xff000000, 0x33000000},
            new float[]{0f, 1f / 3f, 2f / 3f, 1f}, Shader.TileMode.CLAMP));
        edgeFadePaint.setXfermode(DST_IN_XFERMODE);
        canvas.drawRect(rulerBounds.left, controlBounds.top, rulerBounds.right, controlBounds.bottom, edgeFadePaint);
        edgeFadePaint.setShader(null);
        edgeFadePaint.setXfermode(null);
        canvas.restoreToCount(layer);
    }

    private void drawBubble(Canvas canvas, float alpha) {
        final String text = formatBubble(zoom);
        if (text.isEmpty()) {
            return;
        }
        final Paint.FontMetricsInt metrics = bubbleTextPaint.getFontMetricsInt();
        final float width = Math.max(AndroidUtilities.dp(40f), (float) Math.ceil(bubbleTextPaint.measureText(text)))
            + AndroidUtilities.dp(4f) * 2f;
        final float height = getBubbleHeight();
        final float halfHeight = height / 2f;
        final float centerX = controlBounds.centerX();
        final float centerY = controlBounds.top - AndroidUtilities.dp(8f) - halfHeight;
        bubbleBounds.set(centerX - width / 2f, centerY - halfHeight, centerX + width / 2f, centerY + halfHeight);

        final float bubbleAlpha = clamp(alpha, 0f, 1f);
        if (bubbleAlpha <= 0.001f) {
            return;
        }
        bubblePaint.setColor(secondaryFixedColor);
        bubbleTextPaint.setColor(onSecondaryFixedColor);
        final float baseline = bubbleBounds.top + AndroidUtilities.dp(6f) - metrics.ascent;
        final int save = canvas.saveLayerAlpha(0f, 0f, getWidth(), getHeight(), Math.round(bubbleAlpha * 255f));
        canvas.drawRoundRect(bubbleBounds, halfHeight, halfHeight, bubblePaint);
        canvas.drawText(text, bubbleBounds.centerX(), baseline, bubbleTextPaint);
        canvas.restoreToCount(save);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = Math.round(Math.max(getExpandedBackgroundWidth(), getCompactWidth())
            + AndroidUtilities.dp(8f) * 2f) + getPaddingLeft() + getPaddingRight();
        final int height = Math.round(getBubbleHeight() + AndroidUtilities.dp(8f) * 2f + AndroidUtilities.dp(64f))
            + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec));
    }

    // ---------- состояние ----------

    private void animateExpandedProgress(float target, boolean animated) {
        if (expandedAnimator != null) {
            expandedAnimator.cancel();
            expandedAnimator = null;
        }
        if (!animated || !isLaidOut()) {
            expandedProgress = target;
            return;
        }
        final ValueAnimator animator = ValueAnimator.ofFloat(expandedProgress, target);
        expandedAnimator = animator;
        animator.setDuration(MORPH_DURATION);
        animator.setInterpolator(MORPH_INTERPOLATOR);
        animator.addUpdateListener(a -> {
            expandedProgress = clamp((float) a.getAnimatedValue(), 0f, 1f);
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (expandedAnimator == animation) {
                    expandedAnimator = null;
                }
            }
        });
        animator.start();
    }

    private void animateSelectorTo(int index, boolean animated) {
        final float offset = getSelectorOffset(index);
        if (animated && isLaidOut()) {
            selectorSpring.animateToFinalPosition(offset);
            return;
        }
        selectorSpring.cancel();
        animatedSelectorOffset = offset;
        invalidate();
    }

    private void animateZoomTo(float target, boolean notify) {
        animateZoomTo(target, notify, -1);
    }

    private void animateZoomTo(float target, boolean notify, int toggleIndex) {
        final boolean fromToggle = toggleIndex >= 0 && toggleIndex < toggleStops.length;
        if (fromToggle) {
            stopZoomAnimator();
        } else {
            cancelZoomAnimator(true);
        }
        final float clamped = clamp(target, minZoom, maxZoom);
        if (fromToggle) {
            selectedToggleIndex = toggleIndex;
            selectedShowsStopValue = true;
            animateSelectorTo(toggleIndex, true);
        }
        if (Math.abs(clamped - zoom) < 1.0E-4f) {
            setZoomInternal(clamped, notify, !fromToggle);
            return;
        }
        final float from = zoom;
        final long duration = Math.min(500L,
            (long) Math.rint((Math.max(from, clamped) / Math.min(from, clamped)) * 500f / 3f));
        final ValueAnimator animator = ValueAnimator.ofFloat(from, clamped);
        zoomAnimator = animator;
        animator.setDuration(duration);
        animator.setInterpolator(ZOOM_INTERPOLATOR);
        animator.addUpdateListener(a -> setZoomInternal((float) a.getAnimatedValue(), notify, !fromToggle));
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!cancelled && Math.abs(zoom - clamped) > 1.0E-4f) {
                    setZoomInternal(clamped, notify, !fromToggle);
                }
                if (zoomAnimator == animation) {
                    zoomAnimator = null;
                    syncSelectedToggle(true);
                }
            }
        });
        animator.start();
    }

    private boolean stopZoomAnimator() {
        final ValueAnimator animator = zoomAnimator;
        if (animator == null) {
            return false;
        }
        zoomAnimator = null;
        animator.cancel();
        return true;
    }

    private void cancelZoomAnimator() {
        cancelZoomAnimator(false);
    }

    private void cancelZoomAnimator(boolean animated) {
        if (stopZoomAnimator()) {
            syncSelectedToggle(animated);
        }
    }

    private void cancelTransientSprings() {
        if (expandedAnimator != null) {
            expandedAnimator.cancel();
            expandedAnimator = null;
        }
        widthSpring.cancel();
        selectorSpring.cancel();
    }

    private void settleTransientAnimationValues() {
        expandedProgress = expanded ? 1f : 0f;
        animatedControlWidth = expanded ? getExpandedBackgroundWidth() : getCompactWidth();
        animatedSelectorOffset = getSelectorOffset(selectedToggleIndex);
    }

    private void updateTargetControlWidth(boolean animated) {
        final float target = expanded ? getExpandedBackgroundWidth() : getCompactWidth();
        if (animated && isLaidOut()) {
            widthSpring.animateToFinalPosition(target);
            return;
        }
        widthSpring.cancel();
        animatedControlWidth = target;
        invalidate();
    }

    private void setZoomInternal(float value, boolean notify, boolean syncToggle) {
        zoom = clamp(value, minZoom, maxZoom);
        if (syncToggle) {
            syncSelectedToggle(!expanded);
        }
        updateAccessibilityDescription();
        invalidate();
        if (notify && onZoomChangeListener != null) {
            onZoomChangeListener.onZoomChanged(zoom);
        }
    }

    protected void syncSelectedToggle(boolean animated) {
        selectedShowsStopValue = false;
        final int segment = findToggleSegment(zoom);
        if (segment < 0) {
            selectedToggleIndex = -1;
            animatedSelectorOffset = 0f;
        } else if (selectedToggleIndex != segment) {
            selectedToggleIndex = segment;
            animateSelectorTo(segment, animated);
        } else if (!animated) {
            selectorSpring.cancel();
            animatedSelectorOffset = getSelectorOffset(segment);
        }
    }

    private void selectToggle(int index) {
        if (index < 0 || index >= toggleStops.length) {
            return;
        }
        if (index == selectedToggleIndex && selectedShowsStopValue) {
            return;
        }
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        animateZoomTo(toggleStops[index], true, index);
    }

    private void updateAccessibilityDescription() {
        setContentDescription(formatBubble(zoom));
    }

    private void resetAutoCollapseTimeout() {
        removeCallbacks(autoCollapseRunnable);
        final AccessibilityManager manager =
            (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        final boolean exploring = manager != null && manager.isTouchExplorationEnabled();
        if (!expanded || dragging || externalZoomGesture || exploring) {
            return;
        }
        postDelayed(autoCollapseRunnable, AUTO_COLLAPSE_DELAY);
    }

    // ---------- жесты ----------

    protected void beginDrag(float x) {
        if (stopZoomAnimator()) {
            syncSelectedToggle(true);
        }
        dragging = true;
        lastTouchX = x;
        dragTick = zoomToTick(zoom);
        dragPrimarySegment = Integer.MIN_VALUE;
        lastHapticTick = primaryTickIndices.length == 0 ? (int) Math.floor(dragTick) : Integer.MIN_VALUE;
        stickyTick = -1;
        stickyDistance = 0f;
        stickyFactor = 0f;
        removeCallbacks(autoCollapseRunnable);
    }

    private void moveDrag(float x) {
        float delta = x - lastTouchX;
        lastTouchX = x;
        if (Math.abs(delta) < 1.0E-4f) {
            return;
        }
        float tick = dragTick;
        final float velocity = getCurrentXVelocity();

        if (stickyTick >= 0) {
            final float budget = Math.max(0f, tickSpacing * stickyFactor - stickyDistance);
            final float travelled = Math.abs(delta);
            if (travelled <= budget) {
                stickyDistance += travelled;
                setDragTick(stickyTick);
                return;
            }
            delta = Math.copySign(travelled - budget, delta);
            tick = stickyTick;
            stickyTick = -1;
            stickyDistance = 0f;
            stickyFactor = 0f;
        }

        if (primaryTickIndices.length > 0) {
            final int segment = findPrimarySegment(tick, delta);
            if (dragPrimarySegment == Integer.MIN_VALUE) {
                dragPrimarySegment = segment;
            } else if (segment != dragPrimarySegment) {
                final int previous = dragPrimarySegment;
                dragPrimarySegment = segment;
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                final float stickiness = calculateStickiness(velocity);
                if (stickiness > 0f) {
                    final int anchor = Math.max(previous, segment);
                    final float radius = tickSpacing * stickiness;
                    final float distance = Math.abs(tick - anchor) * tickSpacing;
                    if (distance < radius) {
                        final float budget = radius - distance;
                        final float travelled = Math.abs(delta);
                        if (travelled <= budget) {
                            stickyTick = anchor;
                            stickyDistance = distance + travelled;
                            stickyFactor = stickiness;
                            setDragTick(anchor);
                            return;
                        }
                        delta = Math.copySign(travelled - budget, delta);
                        tick = anchor;
                    }
                }
            }
        }

        final float target = clamp(tick - delta / tickSpacing, 0f, intervalCount);
        if (primaryTickIndices.length == 0) {
            performTickHaptic((int) Math.floor(target));
        }
        setDragTick(target);
    }

    private float calculateStickiness(float velocity) {
        final float speed = Math.abs(velocity);
        if (speed <= 100f) {
            return 0.7f;
        }
        if (speed >= 1000f) {
            return 0f;
        }
        return (1000f - speed) / 900f * 0.7f;
    }

    private void performTickHaptic(int tick) {
        if (lastHapticTick == tick) {
            return;
        }
        lastHapticTick = tick;
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    private void setDragTick(float tick) {
        dragTick = clamp(tick, 0f, intervalCount);
        setTickInternal(dragTick, true);
    }

    private void setTickInternal(float tick, boolean notify) {
        setZoomInternal(tickToZoom(tick), notify, true);
    }

    private void setZoomFromRulerTap(float x) {
        animateZoomTo(tickToZoom(zoomToTick(zoom) + (x - rulerBounds.centerX()) / tickSpacing), true);
    }

    private void finishDrag(boolean click, boolean haptic) {
        if (click && haptic) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        dragging = false;
        compactGestureDown = false;
        dragStartedFromCompact = false;
        stickyTick = -1;
        stickyDistance = 0f;
        stickyFactor = 0f;
        dragPrimarySegment = Integer.MIN_VALUE;
        pressedToggleIndex = -1;
        setPressed(false);
        requestParentIntercept(true);
        resetAutoCollapseTimeout();
        if (click) {
            performClick();
        }
    }

    private void clearTouchState() {
        dragging = false;
        compactGestureDown = false;
        dragStartedFromCompact = false;
        movedPastSlop = false;
        pressedToggleIndex = -1;
        stickyTick = -1;
        stickyDistance = 0f;
        stickyFactor = 0f;
        dragPrimarySegment = Integer.MIN_VALUE;
        setPressed(false);
        requestParentIntercept(true);
    }

    private void requestParentIntercept(boolean allow) {
        final ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(!allow);
        }
    }

    private void obtainVelocityTracker(MotionEvent event) {
        recycleVelocityTracker();
        velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(event);
    }

    private void recycleVelocityTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    private float getCurrentXVelocity() {
        if (velocityTracker == null) {
            return 0f;
        }
        velocityTracker.computeCurrentVelocity(1000);
        return velocityTracker.getXVelocity();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || toggleStops.length == 0) {
            return false;
        }
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            updateLayoutBounds();
            final RectF bounds = expanded ? rulerTouchBounds : compactTouchBounds;
            if (!bounds.contains(event.getX(), event.getY())) {
                return false;
            }
            downX = event.getX();
            downY = event.getY();
            lastTouchX = downX;
            movedPastSlop = false;
            compactGestureDown = !expanded;
            dragStartedFromCompact = false;
            pressedToggleIndex = !expanded && compactBounds.contains(downX, downY)
                ? findToggleIndexAt(downX) : -1;
            setPressed(true);
            requestParentIntercept(false);
            obtainVelocityTracker(event);
            if (expanded) {
                beginDrag(downX);
            } else {
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
            }
            return true;
        }

        if (velocityTracker != null) {
            velocityTracker.addMovement(event);
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (dragging) {
                if (!movedPastSlop
                    && Math.hypot(event.getX() - downX, event.getY() - downY) > touchSlop) {
                    movedPastSlop = true;
                }
                moveDrag(event.getX());
                return true;
            }
            if (!compactGestureDown) {
                return true;
            }
            final float dx = event.getX() - downX;
            final float dy = event.getY() - downY;
            if (!movedPastSlop && Math.hypot(dx, dy) > touchSlop) {
                movedPastSlop = true;
                removeCallbacks(longPressRunnable);
                if (Math.abs(dx) >= Math.abs(dy)) {
                    dragStartedFromCompact = true;
                    beginDrag(downX);
                    setExpanded(true, true);
                    moveDrag(event.getX());
                }
            }
            return true;
        }

        if (action == MotionEvent.ACTION_UP) {
            removeCallbacks(longPressRunnable);
            if (dragging) {
                final boolean tap = !movedPastSlop && !dragStartedFromCompact;
                if (tap) {
                    setZoomFromRulerTap(event.getX());
                }
                finishDrag(true, tap);
            } else if (movedPastSlop || pressedToggleIndex < 0) {
                clearTouchState();
            } else {
                final int index = findToggleIndexAt(event.getX());
                if (compactBounds.contains(event.getX(), event.getY()) && index == pressedToggleIndex) {
                    selectToggle(index);
                }
                clearTouchState();
                performClick();
            }
            recycleVelocityTracker();
            return true;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(longPressRunnable);
            if (dragging) {
                finishDrag(false, false);
            } else {
                clearTouchState();
            }
            recycleVelocityTracker();
            return true;
        }

        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    // ---------- жизненный цикл ----------

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        cancelTransientSprings();
        settleTransientAnimationValues();
        if (expanded && !dragging) {
            resetAutoCollapseTimeout();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelZoomConfigurationTransition();
        removeCallbacks(longPressRunnable);
        removeCallbacks(autoCollapseRunnable);
        animate().cancel();
        cancelZoomAnimator();
        cancelTransientSprings();
        settleTransientAnimationValues();
        clearTouchState();
        recycleVelocityTracker();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        cancelTransientSprings();
        tickSpacing = Math.max(1f, Math.round(AndroidUtilities.dp(8f)));
        configurePaints();
        rebuildScale();
        settleTransientAnimationValues();
        requestLayout();
        invalidate();
    }

    // ---------- доступность ----------

    @Override
    public CharSequence getAccessibilityClassName() {
        return (expanded ? SeekBar.class : View.class).getName();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(getAccessibilityClassName());
        info.setContentDescription(formatBubble(zoom));
        if (!expanded) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            return;
        }
        info.setScrollable(true);
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, minZoom, maxZoom, zoom));
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_CLICK) {
            setExpanded(!expanded, true);
            return true;
        }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
            cancelZoomAnimator();
            setTickInternal(zoomToTick(zoom) + 1f, true);
            resetAutoCollapseTimeout();
            return true;
        }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            cancelZoomAnimator();
            setTickInternal(zoomToTick(zoom) - 1f, true);
            resetAutoCollapseTimeout();
            return true;
        }
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()
            && arguments != null
            && arguments.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) {
            cancelZoomAnimator();
            setZoomInternal(arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE), true, true);
            resetAutoCollapseTimeout();
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    // ---------- публичный интерфейс ----------

    public float getZoom() {
        return zoom;
    }

    public float getMinimumZoom() {
        return minZoom;
    }

    public float getMaximumZoom() {
        return maxZoom;
    }

    public void setOnZoomChangeListener(OnZoomChangeListener listener) {
        onZoomChangeListener = listener;
    }

    public void setColors(int protectionBackground, int minorTick, int primary, int secondaryFixed, int onSecondaryFixed) {
        protectionBackgroundColor = protectionBackground;
        minorTickColor = minorTick;
        primaryColor = primary;
        secondaryFixedColor = secondaryFixed;
        onSecondaryFixedColor = onSecondaryFixed;
        configurePaintColors();
        invalidate();
    }

    public void setToggleTextColor(int color) {
        unselectedToggleColor = color;
        invalidate();
    }

    public void setDisplayNormalizationFactor(float factor) {
        if (!Float.isFinite(factor) || factor <= 0f) {
            factor = 1f;
        }
        if (Math.abs(displayNormalizationFactor - factor) < 1.0E-4f) {
            return;
        }
        displayNormalizationFactor = factor;
        rebuildScale();
        syncSelectedToggle(false);
        updateAccessibilityDescription();
        invalidate();
    }

    public void setExpanded(boolean value, boolean animated) {
        final float progress = value ? 1f : 0f;
        final float width = value ? getExpandedBackgroundWidth() : getCompactWidth();
        if (expanded == value
            && Math.abs(expandedProgress - progress) < 1.0E-4f
            && Math.abs(animatedControlWidth - width) < 0.1f) {
            if (value) {
                resetAutoCollapseTimeout();
            }
            return;
        }
        expanded = value;
        removeCallbacks(longPressRunnable);
        removeCallbacks(autoCollapseRunnable);
        animateExpandedProgress(progress, animated);
        if (animated && isLaidOut()) {
            widthSpring.animateToFinalPosition(width);
        } else {
            widthSpring.cancel();
            animatedControlWidth = width;
            invalidate();
        }
        if (value && !dragging) {
            resetAutoCollapseTimeout();
        }
        updateAccessibilityDescription();
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    public void setExternalZoomGestureActive(boolean active) {
        if (externalZoomGesture == active) {
            return;
        }
        externalZoomGesture = active;
        if (active) {
            setExpanded(true, true);
        } else {
            resetAutoCollapseTimeout();
        }
    }

    public void setZoom(float value) {
        setZoom(value, false);
    }

    public void setZoom(float value, boolean animated) {
        final float clamped = clamp(value, minZoom, maxZoom);
        cancelZoomAnimator();
        if (animated && isLaidOut()) {
            animateZoomTo(clamped, false);
        } else {
            setZoomInternal(clamped, false, true);
        }
    }

    public void prepareZoomConfigurationTransition() {
        pendingConfigurationSelectorX = Float.NaN;
        if (!isLaidOut() || toggleStops.length == 0) {
            return;
        }
        updateLayoutBounds();
        if (compactBounds.isEmpty()) {
            return;
        }
        pendingConfigurationSelectorX = compactBounds.left + animatedSelectorOffset;
    }

    public void cancelZoomConfigurationTransition() {
        pendingConfigurationSelectorX = Float.NaN;
    }

    public void setZoomConfiguration(float min, float max, float[] toggles, float[] ruler,
                                     float current, boolean animated) {
        if (!Float.isFinite(min) || !Float.isFinite(max) || min <= 0f || max <= min) {
            return;
        }
        final float selectorX = pendingConfigurationSelectorX;
        pendingConfigurationSelectorX = Float.NaN;
        final boolean morph = animated && isLaidOut() && Float.isFinite(selectorX);

        minZoom = min;
        maxZoom = max;
        toggleStops = sanitizeStops(toggles, min, max);
        rulerStops = sanitizeStops(ruler, min, max);
        zoom = clamp(current, min, max);
        rebuildScale();

        if (morph) {
            selectedToggleIndex = findToggleSegment(zoom);
            final float available = Math.max(0f, getWidth() - getPaddingLeft() - getPaddingRight());
            final float usable = Math.max(0f, available - AndroidUtilities.dp(8f) * 2f);
            final float compactLeft = centeredChildLeft(available, Math.min(usable, Math.round(getCompactWidth())));
            selectorSpring.cancel();
            animatedSelectorOffset = selectorX - compactLeft;
            animateSelectorTo(selectedToggleIndex, true);
            updateTargetControlWidth(true);
        } else {
            syncSelectedToggle(false);
            updateTargetControlWidth(false);
        }
        updateAccessibilityDescription();
        requestLayout();
        invalidate();
    }
}
