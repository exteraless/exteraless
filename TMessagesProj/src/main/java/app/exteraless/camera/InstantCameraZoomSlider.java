package app.exteraless.camera;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.hardware.Camera;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import androidx.camera.core.ZoomState;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.camera.Camera2Session;
import org.telegram.messenger.camera.CameraInfo;
import org.telegram.messenger.camera.CameraSession;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimationProperties;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;

import java.util.ArrayList;
import java.util.List;

import app.exteraless.chats.ChatsConfig;

/**
 * Ползунок зума для кружка: перенос {@code InstantCameraZoomSlider} из exteraGram 12.9.0.
 *
 * Привязывается к любому из трёх бэкендов камеры и берёт у него настоящие пределы зума,
 * поэтому «.5» на делениях — это реальный ширик, а не цифровое увеличение. У Camera1 зум
 * задаётся индексом в таблице кратностей, у Camera2 — числом, у CameraX — через
 * {@link ZoomState}, за которым вид следит подпиской.
 *
 * Сессия готова не сразу, поэтому привязка повторяется до 25 раз с шагом 100 мс; пока
 * пределы неизвестны, вид скрыт.
 */
@SuppressLint("ViewConstructor")
public class InstantCameraZoomSlider extends CameraZoomSliderView {

    public interface OnCameraZoomChangeListener {
        void onCameraZoomChanged(float zoom, boolean fromUser);
    }

    public static final AnimationProperties.FloatProperty<InstantCameraZoomSlider> OPEN_ALPHA =
        new AnimationProperties.FloatProperty<InstantCameraZoomSlider>("openAlpha") {
            @Override
            public void setValue(InstantCameraZoomSlider slider, float value) {
                slider.setOpenAlpha(value);
            }

            @Override
            public Float get(InstantCameraZoomSlider slider) {
                return slider.getOpenAlpha();
            }
        };

    public enum Backend {
        NONE,
        CAMERA_1,
        CAMERA_2,
        CAMERA_X
    }

    private static final int MAX_BIND_RETRIES = 25;
    private static final long BIND_RETRY_DELAY = 100L;
    private static final long APPEAR_DURATION = 180L;

    private final Theme.ResourcesProvider resourcesProvider;
    private final Runnable bindRunnable = this::tryBind;
    private final Observer<ZoomState> cameraXZoomObserver = this::onCameraXZoomStateChanged;

    private Backend backend = Backend.NONE;
    private CameraXSession cameraXSession;
    private Camera2Session camera2Session;
    private CameraSession camera1Session;
    private LiveData<ZoomState> cameraXZoomState;

    private float[] camera1ZoomRatios = new float[0];
    private int camera1ZoomIndex = -1;
    private float camera1LinearZoom;

    private float defaultZoom = 1f;
    private float wideZoom = 1f;
    private float displayOneZoom = 1f;

    private OnCameraZoomChangeListener cameraZoomChangeListener;
    private BlurredBackgroundDrawable blurBackground;
    private float blurCornerRadius = -1f;

    private ValueAnimator appearAnimator;
    private float appearProgress;
    private float openAlpha;
    private float baseTranslationY;
    private int textureViewSize;

    private boolean switchingCamera;
    private boolean animateNextConfiguration;
    private int bindRetries;

    private final Runnable zoomFlushRunnable = this::flushPendingZoom;
    private float pendingZoom = Float.NaN;
    private float lastAppliedZoom = Float.NaN;
    private long lastZoomAppliedAt;
    private boolean zoomFlushScheduled;

    public InstantCameraZoomSlider(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setVisibility(GONE);
        applyAppearProgress();
        setOnZoomChangeListener(this::applyZoom);
        applyTelegramColors();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void applyTelegramColors() {
        final int background = Theme.getColor(Theme.key_chat_messagePanelBackground, resourcesProvider);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        final int onAccent = Theme.getColor(Theme.key_chats_actionIcon, resourcesProvider);
        final int text = Theme.getColor(Theme.key_chat_messagePanelText, resourcesProvider);
        setColors(background, text, accent, accent, onAccent);
        setToggleTextColor(text);
    }

    // ---------- деления ----------

    private static void addDistinctStop(ArrayList<Float> stops, float value) {
        for (int i = 0; i < stops.size(); i++) {
            final float existing = stops.get(i);
            if (Math.abs(existing - value) <= 1.0E-4f) {
                return;
            }
            if (existing > value) {
                stops.add(i, value);
                return;
            }
        }
        stops.add(value);
    }

    private static float[] boundStops(float[] values, float min, float max, boolean withEdges) {
        final ArrayList<Float> stops = new ArrayList<>(values.length + 2);
        if (withEdges) {
            addDistinctStop(stops, min);
        }
        for (float value : values) {
            if (value <= 0f || !Float.isFinite(value)) {
                continue;
            }
            if (value < min - 1.0E-4f) {
                if (!withEdges) {
                    addDistinctStop(stops, min);
                }
            } else if (value <= max + 1.0E-4f) {
                addDistinctStop(stops, clamp(value, min, max));
            }
        }
        if (withEdges) {
            addDistinctStop(stops, max);
        }
        final float[] result = new float[stops.size()];
        for (int i = 0; i < stops.size(); i++) {
            result[i] = stops.get(i);
        }
        return result;
    }

    /** {@code unit} — реальная кратность, которая подписывается как «1×». */
    private static float[] buildRulerStops(float min, float max, float unit) {
        final float[] stops = boundStops(new float[]{min, unit, 2f * unit, 5f * unit, 10f * unit, 30f * unit}, min, max, false);
        // Край подписываем отдельно, только если он заметно дальше последнего деления:
        // иначе на фронталке Pixel рядом встают «10» и «11».
        if (stops.length == 0 || stops[stops.length - 1] * 1.15f < max) {
            return boundStops(stops, min, max, true);
        }
        return stops;
    }

    private static float[] buildToggleStops(boolean frontFace, float min, float max, float unit) {
        return boundStops(frontFace
            ? new float[]{min, unit, 2f * unit}
            : new float[]{min, unit, 2f * unit, 5f * unit}, min, max, false);
    }

    // ---------- Camera1 ----------

    private static float[] readCamera1ZoomRatios(CameraSession session) {
        try {
            final CameraInfo info = session.cameraInfo;
            final Camera camera = info != null ? info.getCamera() : null;
            if (camera == null) {
                return null;
            }
            final Camera.Parameters parameters = camera.getParameters();
            if (parameters == null || !parameters.isZoomSupported()) {
                return new float[0];
            }
            final List<Integer> ratios = parameters.getZoomRatios();
            if (ratios == null || ratios.size() < 2) {
                return new float[0];
            }
            final float[] result = new float[ratios.size()];
            for (int i = 0; i < result.length; i++) {
                final Integer ratio = ratios.get(i);
                result[i] = ratio == null ? 1f : Math.max(1f, ratio / 100f);
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private float camera1LinearZoomForIndex(int index) {
        final int last = camera1ZoomRatios.length - 1;
        if (index <= 0) {
            return 0f;
        }
        if (index >= last) {
            return 1f;
        }
        return (index + 0.001f) / last;
    }

    private float camera1RatioForLinearZoom(float linear) {
        final int last = camera1ZoomRatios.length - 1;
        return camera1ZoomRatios[Math.min((int) (clamp(linear, 0f, 1f) * last), last)];
    }

    private int camera1ZoomIndexForRatio(float ratio) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < camera1ZoomRatios.length; i++) {
            final float distance = Math.abs(camera1ZoomRatios[i] - ratio);
            if (distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }

    // ---------- зум ----------

    /**
     * Ставит зум в очередь вместо немедленной отправки в камеру.
     *
     * Слайдер двигается по каждому событию касания, а камера принимает изменения
     * не чаще кадра записи; без очереди лишние вызовы копятся в очереди камеры
     * и зум отстаёт от пальца.
     */
    private void applyZoom(float value) {
        pendingZoom = value;
        scheduleZoomFlush();
    }

    private void scheduleZoomFlush() {
        if (zoomFlushScheduled) {
            return;
        }
        zoomFlushScheduled = true;
        postOnAnimation(zoomFlushRunnable);
    }

    private void discardPendingZoom() {
        removeCallbacks(zoomFlushRunnable);
        zoomFlushScheduled = false;
        pendingZoom = Float.NaN;
    }

    private void resetZoomThrottle() {
        discardPendingZoom();
        lastAppliedZoom = Float.NaN;
        lastZoomAppliedAt = 0L;
    }

    private long getZoomUpdateIntervalMs() {
        int frameRate = 0;
        if (backend == Backend.CAMERA_X && cameraXSession != null) {
            frameRate = cameraXSession.getRecordingFrameRate();
        } else if (backend == Backend.CAMERA_2 && camera2Session != null) {
            frameRate = camera2Session.getRecordingFrameRate();
        }
        if (frameRate <= 0) {
            frameRate = 30;
        }
        return Math.max(1L, 1000L / frameRate);
    }

    private void flushPendingZoom() {
        zoomFlushScheduled = false;
        final float value = pendingZoom;
        if (Float.isNaN(value) || backend == Backend.NONE) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        if (!Float.isNaN(lastAppliedZoom)) {
            if (value == lastAppliedZoom) {
                return;
            }
            if (now - lastZoomAppliedAt < getZoomUpdateIntervalMs()) {
                scheduleZoomFlush();
                return;
            }
        }
        lastAppliedZoom = value;
        lastZoomAppliedAt = now;
        sendZoomToCamera(value);
    }

    private void sendZoomToCamera(float value) {
        float reported = value;
        switch (backend) {
            case CAMERA_X:
                if (cameraXSession == null) {
                    return;
                }
                cameraXSession.setZoomRatio(value);
                reported = cameraXSession.getLinearZoom();
                break;
            case CAMERA_2:
                if (camera2Session == null) {
                    return;
                }
                camera2Session.setZoom(value);
                break;
            case CAMERA_1:
                if (camera1Session == null || camera1ZoomRatios.length < 2) {
                    return;
                }
                final int index = camera1ZoomIndexForRatio(value);
                camera1LinearZoom = camera1LinearZoomForIndex(index);
                if (index != camera1ZoomIndex) {
                    camera1ZoomIndex = index;
                    camera1Session.setZoom(camera1LinearZoom);
                }
                reported = camera1LinearZoom;
                break;
            default:
                return;
        }
        if (cameraZoomChangeListener != null) {
            cameraZoomChangeListener.onCameraZoomChanged(reported, true);
        }
    }

    private void onCameraXZoomStateChanged(ZoomState state) {
        if (backend != Backend.CAMERA_X || state == null || cameraZoomChangeListener == null) {
            return;
        }
        cameraZoomChangeListener.onCameraZoomChanged(state.getLinearZoom(), false);
    }

    private void attachCameraXZoomObserver() {
        if (cameraXSession == null || cameraXSession.camera == null) {
            return;
        }
        final LiveData<ZoomState> state = cameraXSession.camera.getCameraInfo().getZoomState();
        if (cameraXZoomState == state) {
            return;
        }
        detachCameraXZoomObserver();
        cameraXZoomState = state;
        state.observeForever(cameraXZoomObserver);
    }

    private void detachCameraXZoomObserver() {
        if (cameraXZoomState != null) {
            cameraXZoomState.removeObserver(cameraXZoomObserver);
            cameraXZoomState = null;
        }
    }

    public void scaleCameraXZoom(float factor) {
        if (backend == Backend.CAMERA_X && cameraXSession != null && Float.isFinite(factor)) {
            setCameraXZoomRatio(getZoom() * factor);
        }
    }

    public void setCameraXZoomRatio(float ratio) {
        if (backend != Backend.CAMERA_X || cameraXSession == null) {
            return;
        }
        final float clamped = clamp(ratio, getMinimumZoom(), getMaximumZoom());
        setZoom(clamped);
        cameraXSession.setZoomRatio(clamped);
    }

    public float getCameraXResetZoom() {
        final boolean front = cameraXSession != null && cameraXSession.isActiveCameraFrontface();
        return !ChatsConfig.startWithWideAngleCamera.Bool() || front ? defaultZoom : wideZoom;
    }

    public float getDisplayOneZoom() {
        return displayOneZoom;
    }

    public void syncZoom() {
        switch (backend) {
            case CAMERA_X:
                if (cameraXSession != null && cameraXSession.isReady()) {
                    setZoom(cameraXSession.getZoomRatio());
                }
                break;
            case CAMERA_2:
                if (camera2Session != null && camera2Session.isInitiated()) {
                    setZoom(camera2Session.getZoom());
                }
                break;
            case CAMERA_1:
                if (camera1ZoomRatios.length > 1) {
                    setZoom(camera1RatioForLinearZoom(camera1LinearZoom));
                }
                break;
            default:
                break;
        }
    }

    public void syncZoom(float value) {
        if (backend == Backend.CAMERA_1) {
            camera1LinearZoom = clamp(value, 0f, 1f);
            camera1ZoomIndex = -1;
            if (camera1ZoomRatios.length > 1) {
                setZoom(camera1RatioForLinearZoom(camera1LinearZoom));
            }
        } else if (backend == Backend.CAMERA_2) {
            setZoom(value);
        } else {
            syncZoom();
        }
    }

    // ---------- привязка к сессии ----------

    public void bindSession(CameraXSession session) {
        final boolean morph = session != null && getVisibility() == VISIBLE && isLaidOut()
            && (switchingCamera || (backend == Backend.CAMERA_X && cameraXSession == session));
        switchingCamera = false;
        if (morph) {
            prepareZoomConfigurationTransition();
        }
        resetBinding(!morph);
        if (session == null) {
            return;
        }
        backend = Backend.CAMERA_X;
        cameraXSession = session;
        animateNextConfiguration = morph;
        if (morph) {
            setEnabled(false);
            setExpanded(false, true);
        }
        tryBind();
    }

    public void bindSession(Camera2Session session) {
        final boolean morph = session != null && switchingCamera;
        switchingCamera = false;
        resetBinding(!morph);
        if (session == null) {
            return;
        }
        backend = Backend.CAMERA_2;
        camera2Session = session;
        animateNextConfiguration = morph;
        tryBind();
    }

    public void bindSession(CameraSession session, float linearZoom) {
        final boolean morph = session != null && switchingCamera;
        switchingCamera = false;
        resetBinding(!morph);
        if (session == null) {
            return;
        }
        backend = Backend.CAMERA_1;
        camera1Session = session;
        camera1LinearZoom = clamp(linearZoom, 0f, 1f);
        animateNextConfiguration = morph;
        tryBind();
    }

    public void unbindSession() {
        switchingCamera = false;
        resetBinding(true);
    }

    public void beginCameraSwitch() {
        if (backend != Backend.NONE && getVisibility() == VISIBLE && isLaidOut()) {
            switchingCamera = true;
            prepareZoomConfigurationTransition();
            setEnabled(false);
            setExpanded(false, true);
        }
    }

    private void resetBinding(boolean hide) {
        setExternalZoomGestureActive(false);
        resetZoomThrottle();
        backend = Backend.NONE;
        detachCameraXZoomObserver();
        setZoom(getZoom());
        cameraXSession = null;
        camera2Session = null;
        camera1Session = null;
        camera1ZoomRatios = new float[0];
        camera1ZoomIndex = -1;
        defaultZoom = 1f;
        wideZoom = 1f;
        displayOneZoom = 1f;
        bindRetries = 0;
        removeCallbacks(bindRunnable);
        animateNextConfiguration = false;
        if (hide) {
            cancelZoomConfigurationTransition();
            setEnabled(true);
            setExpanded(false, false);
            hideImmediately();
        }
    }

    private void retryBinding() {
        if (backend != Backend.NONE && bindRetries++ < MAX_BIND_RETRIES) {
            postDelayed(bindRunnable, BIND_RETRY_DELAY);
            return;
        }
        animateNextConfiguration = false;
        cancelZoomConfigurationTransition();
        setEnabled(true);
        hideImmediately();
    }

    private void tryBind() {
        float minRatio;
        float maxRatio;
        boolean frontFace = false;

        switch (backend) {
            case CAMERA_X: {
                if (cameraXSession == null || !cameraXSession.isReady() || cameraXSession.camera == null) {
                    retryBinding();
                    return;
                }
                final ZoomState state = cameraXSession.camera.getCameraInfo().getZoomState().getValue();
                if (state == null) {
                    retryBinding();
                    return;
                }
                minRatio = state.getMinZoomRatio();
                maxRatio = state.getMaxZoomRatio();
                frontFace = cameraXSession.isActiveCameraFrontface();
                break;
            }
            case CAMERA_2: {
                if (camera2Session == null || !camera2Session.isInitiated()) {
                    retryBinding();
                    return;
                }
                minRatio = camera2Session.getMinZoom();
                maxRatio = camera2Session.getMaxZoom();
                frontFace = camera2Session.isFront();
                break;
            }
            case CAMERA_1: {
                if (camera1Session == null || !camera1Session.isInitied()) {
                    retryBinding();
                    return;
                }
                camera1ZoomRatios = readCamera1ZoomRatios(camera1Session);
                if (camera1ZoomRatios == null) {
                    camera1ZoomRatios = new float[0];
                    retryBinding();
                    return;
                }
                minRatio = camera1ZoomRatios.length == 0 ? 1f : camera1ZoomRatios[0];
                maxRatio = camera1ZoomRatios.length == 0 ? 1f : camera1ZoomRatios[camera1ZoomRatios.length - 1];
                break;
            }
            default:
                return;
        }

        if (!Float.isFinite(minRatio) || !Float.isFinite(maxRatio) || minRatio <= 0f || maxRatio <= minRatio) {
            animateNextConfiguration = false;
            cancelZoomConfigurationTransition();
            setEnabled(true);
            hideImmediately();
            return;
        }

        // Фронталка: её минимум — полный сенсор, а не другая линза (Pixel: 0.9×), и это
        // штатный угол. Подписываем его как «1×» и от него же считаем «2×».
        displayOneZoom = frontFace ? minRatio : 1f;
        defaultZoom = clamp(displayOneZoom, minRatio, maxRatio);
        wideZoom = minRatio;
        setDisplayNormalizationFactor(displayOneZoom);

        final float[] toggles = buildToggleStops(frontFace, minRatio, maxRatio, displayOneZoom);
        final float[] ruler = buildRulerStops(minRatio, maxRatio, displayOneZoom);
        final float current = clamp(
            backend == Backend.CAMERA_X ? getCameraXResetZoom()
                : backend == Backend.CAMERA_2 ? camera2Session.getZoom()
                    : camera1RatioForLinearZoom(camera1LinearZoom),
            minRatio, maxRatio);

        final boolean morph = animateNextConfiguration;
        animateNextConfiguration = false;
        setZoomConfiguration(minRatio, maxRatio, toggles, ruler, current, morph);
        if (!morph) {
            setExpanded(false, false);
        }
        if (backend == Backend.CAMERA_X) {
            attachCameraXZoomObserver();
            cameraXSession.setZoomRatio(current);
        }
        showAnimated();
    }

    // ---------- жесты снаружи ----------

    public void beginExternalZoomGesture() {
        if (backend != Backend.CAMERA_X) {
            return;
        }
        discardPendingZoom();
        setZoom(getZoom());
    }

    public void beginPinchZoomGesture() {
        beginExternalZoomGesture();
        setExternalZoomGestureActive(true);
    }

    public void endPinchZoomGesture() {
        setExternalZoomGestureActive(false);
    }

    public void beginSteppedZoomGesture() {
        beginExternalZoomGesture();
        setExpanded(true, true);
    }

    // ---------- появление и место на экране ----------

    private void applyAppearProgress() {
        setAlpha(openAlpha * appearProgress);
        final float scale = appearProgress * 0.1f + 0.9f;
        setScaleX(scale);
        setScaleY(scale);
    }

    private void setAppearProgress(float value) {
        if (appearProgress != value) {
            appearProgress = value;
            applyAppearProgress();
        }
    }

    private void cancelAppearAnimation() {
        final ValueAnimator animator = appearAnimator;
        if (animator != null) {
            appearAnimator = null;
            animator.cancel();
        }
    }

    private void hideImmediately() {
        cancelAppearAnimation();
        setAppearProgress(0f);
        setVisibility(GONE);
    }

    private void showAnimated() {
        setEnabled(true);
        if (!app.exteraless.chats.ChatsConfig.zoomSlider.Bool()) {
            hideImmediately();
            return;
        }
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
        }
        if (appearAnimator != null || appearProgress >= 1f) {
            return;
        }
        final ValueAnimator animator = ValueAnimator.ofFloat(appearProgress, 1f);
        appearAnimator = animator;
        animator.addUpdateListener(a -> setAppearProgress((float) a.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (appearAnimator == animation) {
                    appearAnimator = null;
                    setAppearProgress(1f);
                }
            }
        });
        animator.setDuration(APPEAR_DURATION);
        animator.setInterpolator(CubicBezierInterpolator.DEFAULT);
        animator.start();
    }

    private void applyPosition() {
        setTranslationY(baseTranslationY + textureViewSize / 2f + AndroidUtilities.dp(80f) - getMeasuredHeight() / 2f);
    }

    public void setBaseTranslationY(float value) {
        if (baseTranslationY != value) {
            baseTranslationY = value;
            applyPosition();
        }
    }

    public void setTextureViewSize(int size) {
        if (textureViewSize != size) {
            textureViewSize = size;
            applyPosition();
        }
    }

    public float getOpenAlpha() {
        return openAlpha;
    }

    public void setOpenAlpha(float value) {
        if (openAlpha != value) {
            openAlpha = value;
            setAlpha(value * appearProgress);
        }
    }

    public void setOnCameraZoomChangeListener(OnCameraZoomChangeListener listener) {
        cameraZoomChangeListener = listener;
    }

    public void setBlurBackground(BlurredBackgroundDrawable drawable) {
        if (blurBackground != drawable) {
            blurBackground = drawable;
            blurCornerRadius = -1f;
            invalidate();
        }
    }

    @Override
    public boolean drawPillBackground(Canvas canvas, RectF bounds, float radius) {
        if (blurBackground == null) {
            return false;
        }
        if (blurCornerRadius != radius) {
            blurCornerRadius = radius;
            blurBackground.setRadius(radius);
        }
        blurBackground.setBounds(Math.round(bounds.left), Math.round(bounds.top),
            Math.round(bounds.right), Math.round(bounds.bottom));
        blurBackground.draw(canvas);
        return true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (backend != Backend.NONE
            && (getVisibility() != VISIBLE || !isEnabled() || animateNextConfiguration)) {
            bindRetries = 0;
            tryBind();
        } else if (backend == Backend.CAMERA_X) {
            attachCameraXZoomObserver();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(bindRunnable);
        discardPendingZoom();
        detachCameraXZoomObserver();
        if (appearAnimator != null) {
            cancelAppearAnimation();
            setAppearProgress(getVisibility() == VISIBLE ? 1f : 0f);
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        applyPosition();
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || getAlpha() < 0.5f) {
            return false;
        }
        return super.onTouchEvent(event);
    }
}
