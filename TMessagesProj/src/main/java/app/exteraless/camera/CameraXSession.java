package app.exteraless.camera;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Looper;
import android.util.Range;
import android.util.Size;
import android.view.Display;
import android.view.Surface;

import androidx.annotation.OptIn;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraState;
import androidx.camera.core.ConcurrentCamera;
import androidx.camera.core.DisplayOrientedMeteringPointFactory;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.MirrorMode;
import androidx.camera.core.Preview;
import androidx.camera.core.SessionConfig;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ZoomState;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import com.google.common.util.concurrent.ListenableFuture;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.Stories.recorder.DualCameraView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.exteraless.chats.ChatsConfig;

/**
 * Камера круглых видеосообщений на CameraX.
 *
 * Порт {@code com/exteragram/messenger/camera/CameraXSession.java} из exteraGram 12.9.0.
 * Третий движок рядом с Camera1 ({@link org.telegram.messenger.camera.CameraSession}) и
 * Camera2 ({@link org.telegram.messenger.camera.Camera2Session}); чем снимать, выбирает
 * {@link ChatsConfig#cameraType}.
 *
 * Что он умеет сверх Camera2:
 * <ul>
 *   <li>бесшовное переключение камер — обе линзы привязаны одновременно
 *       ({@link ConcurrentCamera}), и переключение не пересобирает сессию;</li>
 *   <li>старт на широкоугольной линзе ({@link ChatsConfig#startWithWideAngleCamera});</li>
 *   <li>отключение зеркала фронталки ({@link ChatsConfig#cameraMirrorMode});</li>
 *   <li>стабилизацию превью и 60 кадров, когда железо их тянет.</li>
 * </ul>
 *
 * Все методы, кроме явно помеченных, зовутся с UI-потока: CameraX привязывается к
 * жизненному циклу, а {@link LifecycleRegistry} требует главного потока.
 */
public class CameraXSession {

    /** Свой жизненный цикл: у нас нет Activity, к которой CameraX мог бы привязаться. */
    public static class CameraLifecycle implements LifecycleOwner {

        private final LifecycleRegistry lifecycleRegistry;

        public CameraLifecycle() {
            lifecycleRegistry = new LifecycleRegistry(this);
            lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        }

        @Override
        public Lifecycle getLifecycle() {
            return lifecycleRegistry;
        }

        public void start() {
            try {
                if (lifecycleRegistry.getCurrentState() != Lifecycle.State.DESTROYED) {
                    lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }

        public void stop() {
            try {
                lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    /** Размер картинки, который CameraX выдал в поверхность: он же размер превью. */
    public interface PreviewSizeListener {
        void onPreviewSize(int width, int height);
    }

    private static final Range<Integer> FPS_60_RANGE = new Range<>(60, 60);
    private static final Range<Integer> FPS_30_RANGE = new Range<>(30, 30);
    /** Если характеристик сенсора нет, считаем его обычным 4:3. */
    private static final Size FALLBACK_SENSOR_ASPECT = new Size(4, 3);

    private static final Map<CameraSelector, Boolean> STABILIZATION_SUPPORT_CACHE = new ConcurrentHashMap<>();
    private static volatile Boolean seamlessSwitchingAvailableCache;

    private final CameraLifecycle lifecycle;
    private final Preview.SurfaceProvider surfaceProviderPrimary;
    private Preview.SurfaceProvider surfaceProviderSecondary;

    ProcessCameraProvider provider;
    Camera camera;
    Camera cameraFront;
    Camera cameraBack;
    private CameraControl cameraControl;
    private CameraControl cameraControlFront;
    private CameraControl cameraControlBack;
    private CameraSelector cameraSelector;
    private Preview previewUseCase;
    private Preview previewUseCaseBack;

    private boolean isFrontface;
    private boolean isInitiated;
    private boolean isDualMode;
    private boolean isBinding;
    private boolean isTorchOn;
    private volatile int recordingFrameRate = 30;

    public CameraXSession(CameraLifecycle lifecycle, Preview.SurfaceProvider surfaceProvider) {
        this.lifecycle = lifecycle;
        this.surfaceProviderPrimary = surfaceProvider;
    }

    // ---- Создание и разбор ----

    public void initCamera(Context context, boolean frontface, boolean dual, Runnable onReady) {
        isFrontface = frontface;
        final ListenableFuture<ProcessCameraProvider> future;
        try {
            future = ProcessCameraProvider.getInstance(context);
        } catch (Throwable t) {
            FileLog.e(t);
            isInitiated = false;
            return;
        }
        future.addListener(() -> {
            try {
                provider = future.get();
                if (lifecycle.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED) {
                    return;
                }
                isDualMode = dual && supportsConcurrentFrontBackPair();
                rebindCamera();
                lifecycle.start();
                if (onReady != null) {
                    onReady.run();
                }
            } catch (Exception e) {
                FileLog.e(e);
                isInitiated = false;
            }
        }, ContextCompat.getMainExecutor(context));
    }

    /** Закрытие может прилететь с потока камеры — уводим на UI, там живёт жизненный цикл. */
    public void closeCamera() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            AndroidUtilities.runOnUIThread(this::closeCamera);
            return;
        }
        try {
            if (provider != null) {
                provider.unbindAll();
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            lifecycle.stop();
            isInitiated = false;
            clearCameraReferences();
        }
    }

    private void rebindCamera() {
        if (provider == null
                || lifecycle.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED
                || isBinding) {
            return;
        }
        isBinding = true;
        recordingFrameRate = 30;
        clearCameraReferences();
        try {
            provider.unbindAll();
            if (isDualMode) {
                bindDualUseCases();
            } else {
                bindSingleUseCases();
            }
            isInitiated = camera != null;
        } catch (Exception e) {
            FileLog.e(e);
            isInitiated = false;
            try {
                provider.unbindAll();
            } catch (Exception e2) {
                FileLog.e(e2);
            }
            clearCameraReferences();
        } finally {
            isBinding = false;
        }
    }

    private void clearCameraReferences() {
        cameraBack = null;
        cameraFront = null;
        camera = null;
        cameraControlBack = null;
        cameraControlFront = null;
        cameraControl = null;
        previewUseCaseBack = null;
        previewUseCase = null;
    }

    // ---- Привязка ----

    /**
     * Одна линза. Порядок попыток тот же, что у exteraGram: сперва запрошенные 60 кадров,
     * потом без стабилизации, потом 30, потом вообще без требований к частоте — на
     * каждом шаге превью пересобирается, потому что частота задаётся при сборке.
     */
    private void bindSingleUseCases() {
        try {
            cameraSelector = isFrontface ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
            if (!provider.hasCamera(cameraSelector)) {
                isInitiated = false;
                return;
            }
            final boolean extendedFps = ChatsConfig.extendedFramesPerSecond.Bool();
            final boolean stabilization = ChatsConfig.cameraStabilization.Bool()
                    && isPreviewStabilizationSupported(cameraSelector);

            previewUseCase = buildPreview(cameraSelector, stabilization, null, extendedFps);
            previewUseCase.setSurfaceProvider(surfaceProviderPrimary);

            if (extendedFps) {
                camera = tryBindSingleAtExtendedFrameRate();
                if (camera == null && stabilization) {
                    previewUseCase = buildPreview(cameraSelector, false, null, true);
                    previewUseCase.setSurfaceProvider(surfaceProviderPrimary);
                    camera = tryBindSingleAtExtendedFrameRate();
                }
                if (camera == null) {
                    previewUseCase = buildPreview(cameraSelector, stabilization, null, false);
                    previewUseCase.setSurfaceProvider(surfaceProviderPrimary);
                    camera = tryBindSingleAtFrameRate(FPS_30_RANGE);
                }
            } else {
                camera = tryBindSingleAtFrameRate(FPS_30_RANGE);
            }

            if (camera == null) {
                try {
                    camera = provider.bindToLifecycle(lifecycle, cameraSelector, previewUseCase);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            if (camera == null && stabilization) {
                previewUseCase = buildPreview(cameraSelector, false, null, false);
                previewUseCase.setSurfaceProvider(surfaceProviderPrimary);
                camera = provider.bindToLifecycle(lifecycle, cameraSelector, previewUseCase);
            }
            if (camera == null) {
                isInitiated = false;
                return;
            }
            cameraControl = camera.getCameraControl();
            observeCameraState(camera);
            applyInitialZoom(camera, cameraControl);
            updateTorchState();
        } catch (Exception e) {
            FileLog.e(e);
            isInitiated = false;
        }
    }

    /**
     * Обе линзы сразу — ради переключения без паузы. Частота при двух камерах даётся
     * тяжелее, поэтому 60 → 30 → как получится, и только потом откат на одну линзу.
     */
    private void bindDualUseCases() {
        final boolean extendedFps = ChatsConfig.extendedFramesPerSecond.Bool();
        Range<Integer> range = extendedFps ? FPS_60_RANGE : FPS_30_RANGE;
        ConcurrentCamera concurrent = tryBindConcurrentCameras(range);
        if (concurrent == null && extendedFps) {
            range = FPS_30_RANGE;
            concurrent = tryBindConcurrentCameras(range);
        }
        if (concurrent == null && range != null) {
            range = null;
            concurrent = tryBindConcurrentCameras(null);
        }
        if (concurrent == null) {
            isDualMode = false;
            bindSingleUseCases();
            return;
        }
        if (range != null) {
            recordingFrameRate = range.getLower();
        }
        for (Camera bound : concurrent.getCameras()) {
            if (bound.getCameraInfo().getLensFacing() == CameraSelector.LENS_FACING_FRONT) {
                cameraFront = bound;
                cameraControlFront = bound.getCameraControl();
                applyInitialZoom(cameraFront, cameraControlFront);
            } else {
                cameraBack = bound;
                cameraControlBack = bound.getCameraControl();
                applyInitialZoom(cameraBack, cameraControlBack);
            }
            observeCameraState(bound);
        }
        updateActiveControl(isFrontface);
        updateTorchState();
    }

    private ConcurrentCamera tryBindConcurrentCameras(Range<Integer> range) {
        try {
            final boolean sixty = FPS_60_RANGE.equals(range);

            previewUseCase = buildPreview(CameraSelector.DEFAULT_FRONT_CAMERA, false, range, sixty);
            previewUseCase.setSurfaceProvider(surfaceProviderPrimary);

            previewUseCaseBack = buildPreview(CameraSelector.DEFAULT_BACK_CAMERA, false, range, sixty);
            if (surfaceProviderSecondary != null) {
                previewUseCaseBack.setSurfaceProvider(surfaceProviderSecondary);
            }

            List<ConcurrentCamera.SingleCameraConfig> configs = new ArrayList<>(2);
            configs.add(new ConcurrentCamera.SingleCameraConfig(CameraSelector.DEFAULT_FRONT_CAMERA,
                    new UseCaseGroup.Builder().addUseCase(previewUseCase).build(), lifecycle));
            configs.add(new ConcurrentCamera.SingleCameraConfig(CameraSelector.DEFAULT_BACK_CAMERA,
                    new UseCaseGroup.Builder().addUseCase(previewUseCaseBack).build(), lifecycle));
            return provider.bindToLifecycle(configs);
        } catch (Exception e) {
            FileLog.e(e);
            try {
                provider.unbindAll();
            } catch (Exception e2) {
                FileLog.e(e2);
            }
            return null;
        }
    }

    private Camera tryBindSingleAtExtendedFrameRate() {
        try {
            SessionConfig.Builder builder = new SessionConfig.Builder(previewUseCase);
            Range<Integer> range = selectExtendedFpsRange(
                    provider.getCameraInfo(cameraSelector).getSupportedFrameRateRanges(builder.build()));
            if (range == null) {
                return null;
            }
            builder.setFrameRateRange(range);
            return tryBindSingleSession(builder.build(), range.getUpper());
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private Camera tryBindSingleAtFrameRate(Range<Integer> range) {
        try {
            CameraInfo info = provider.getCameraInfo(cameraSelector);
            SessionConfig.Builder builder = new SessionConfig.Builder(previewUseCase);
            if (!info.getSupportedFrameRateRanges(builder.build()).contains(range)) {
                return null;
            }
            builder.setFrameRateRange(range);
            return tryBindSingleSession(builder.build(), range.getUpper());
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private Camera tryBindSingleSession(SessionConfig sessionConfig, int frameRate) {
        try {
            if (!provider.getCameraInfo(cameraSelector).isSessionConfigSupported(sessionConfig)) {
                return null;
            }
            Camera bound = provider.bindToLifecycle(lifecycle, cameraSelector, sessionConfig);
            if (bound != null) {
                recordingFrameRate = frameRate;
            }
            return bound;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * Превью под круглое видео: 4:3, свой порядок разрешений и, если попросили,
     * стабилизация и незеркальная фронталка.
     */
    private Preview buildPreview(CameraSelector selector, boolean stabilization,
                                 Range<Integer> range, boolean prefer60) {
        final Size sensorAspect = getSensorAspect(selector);
        final Set<Size> capable60 = prefer60 ? get60FpsCapableSizes(selector) : Collections.emptySet();

        Preview.Builder builder = new Preview.Builder()
                .setResolutionSelector(new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionFilter((sizes, rotation) ->
                                sortRoundPreviewSizes(sizes, sensorAspect, capable60))
                        .setAllowedResolutionMode(ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION)
                        .build());
        if (range != null) {
            builder.setTargetFrameRate(range);
        }
        // При двух привязанных линзах стабилизация превью не даётся вовсе.
        builder.setPreviewStabilizationEnabled(!isDualMode && stabilization);
        if (!ChatsConfig.cameraMirrorMode.Bool()) {
            builder.setMirrorMode(MirrorMode.MIRROR_MODE_OFF);
        }
        return builder.build();
    }

    // ---- Выбор разрешения ----

    /**
     * Порядок предпочтений для круглого видео.
     * Сначала то, что не меньше кружка; из них — умеющее 60 кадров; дальше меньше
     * обрезки по краям, и лишь потом просто ближе к нужной стороне.
     */
    private static List<Size> sortRoundPreviewSizes(List<Size> sizes, Size sensorAspect, Set<Size> capable60) {
        List<Size> sorted = new ArrayList<>(sizes);
        final int target = com.exteragram.messenger.utils.system.SystemUtils
                .getRoundVideoResolution();
        final int comfortable = target * 2;
        final int sensorShort = Math.min(sensorAspect.getWidth(), sensorAspect.getHeight());
        final int sensorLong = Math.max(sensorAspect.getWidth(), sensorAspect.getHeight());
        sorted.sort((a, b) -> compareRoundPreviewSizes(a, b, target, comfortable, sensorShort, sensorLong, capable60));
        return sorted;
    }

    private static int compareRoundPreviewSizes(Size a, Size b, int target, int comfortable,
                                                int sensorShort, int sensorLong, Set<Size> capable60) {
        final int aShort = Math.min(a.getWidth(), a.getHeight());
        final int aLong = Math.max(a.getWidth(), a.getHeight());
        final int bShort = Math.min(b.getWidth(), b.getHeight());
        final int bLong = Math.max(b.getWidth(), b.getHeight());

        final boolean aEnough = aShort >= target;
        if (aEnough != (bShort >= target)) {
            return aEnough ? -1 : 1;
        }
        if (!aEnough) {
            int cmp = Integer.compare(bShort, aShort);
            if (cmp != 0) {
                return cmp;
            }
        }
        // Угол обзора важнее 60 кадров: 16:9 у Pixel — это кроп 4:3 (3840×2160 из
        // 3840×2736), и кружок из него теряет ~20 % ширины. 60 fps берём только
        // среди размеров с тем же углом.
        int cmp = Long.compare(
                calculateFieldOfViewPenalty(aShort, aLong, sensorShort, sensorLong) * bShort,
                calculateFieldOfViewPenalty(bShort, bLong, sensorShort, sensorLong) * aShort);
        if (cmp != 0) {
            return cmp;
        }
        final boolean aFast = capable60.contains(a);
        if (aFast != capable60.contains(b)) {
            return aFast ? -1 : 1;
        }
        final boolean aComfortable = aShort >= comfortable;
        if (aComfortable != (bShort >= comfortable)) {
            return aComfortable ? -1 : 1;
        }
        if (!aComfortable) {
            cmp = Integer.compare(bShort, aShort);
            if (cmp != 0) {
                return cmp;
            }
        }
        cmp = Integer.compare(Math.abs(aShort - comfortable), Math.abs(bShort - comfortable));
        if (cmp != 0) {
            return cmp;
        }
        return Long.compare((long) b.getWidth() * b.getHeight(), (long) a.getWidth() * a.getHeight());
    }

    /** Насколько кадр уже сенсора: чем шире — тем меньше штраф. */
    private static long calculateFieldOfViewPenalty(int shortSide, int longSide, int sensorShort, int sensorLong) {
        long frame = (long) shortSide * sensorLong;
        return Math.max(0L, (((long) longSide * sensorShort) - frame) * 100 - frame);
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private Set<Size> get60FpsCapableSizes(CameraSelector selector) {
        Set<Size> result = new HashSet<>();
        try {
            if (provider == null || selector == null || !provider.hasCamera(selector)) {
                return result;
            }
            StreamConfigurationMap map = Camera2CameraInfo.from(provider.getCameraInfo(selector))
                    .getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map != null ? map.getOutputSizes(SurfaceTexture.class) : null;
            if (sizes != null) {
                for (Size size : sizes) {
                    long minDuration = map.getOutputMinFrameDuration(SurfaceTexture.class, size);
                    // 16 666 667 нс на кадр — это ровно 60 кадров в секунду.
                    if (minDuration > 0 && minDuration <= 16666667L) {
                        result.add(size);
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private Size getSensorAspect(CameraSelector selector) {
        try {
            if (provider != null && selector != null && provider.hasCamera(selector)) {
                Rect array = Camera2CameraInfo.from(provider.getCameraInfo(selector))
                        .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                if (array != null && array.width() > 0 && array.height() > 0) {
                    return new Size(array.width(), array.height());
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return FALLBACK_SENSOR_ASPECT;
    }

    private static Range<Integer> selectExtendedFpsRange(Collection<Range<Integer>> ranges) {
        if (ranges == null) {
            return null;
        }
        Range<Integer> best = null;
        for (Range<Integer> range : ranges) {
            if (range == null || !range.getUpper().equals(FPS_60_RANGE.getUpper())) {
                continue;
            }
            if (FPS_60_RANGE.equals(range)) {
                return range;  // строго 60 — лучше плавающего диапазона
            }
            if (best == null || range.getLower() > best.getLower()) {
                best = range;
            }
        }
        return best;
    }

    private boolean isPreviewStabilizationSupported(CameraSelector selector) {
        if (selector == null) {
            return false;
        }
        try {
            if (provider == null || !provider.hasCamera(selector)) {
                return false;
            }
            Boolean cached = STABILIZATION_SUPPORT_CACHE.get(selector);
            if (cached != null) {
                return cached;
            }
            boolean supported = Preview.getPreviewCapabilities(provider.getCameraInfo(selector))
                    .isStabilizationSupported();
            STABILIZATION_SUPPORT_CACHE.put(selector, supported);
            return supported;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    // ---- Две камеры сразу ----

    /** Доступно ли бесшовное переключение: нужна и поддержка кружка, и пара линз. */
    public static boolean isRoundDualAvailable(Context context) {
        return DualCameraView.roundDualAvailableStatic(context) && isSeamlessSwitchingAvailable(context);
    }

    public static boolean isSeamlessSwitchingAvailable(Context context) {
        if (seamlessSwitchingAvailableCache != null) {
            return seamlessSwitchingAvailableCache;
        }
        seamlessSwitchingAvailableCache = SharedConfig.getDevicePerformanceClass() >= SharedConfig.PERFORMANCE_CLASS_AVERAGE
                && SharedConfig.allowPreparingHevcPlayers()
                && hasConcurrentFrontBackPair(context);
        return seamlessSwitchingAvailableCache;
    }

    /**
     * Система обещает пару «фронтальная + основная» одновременно. Спрашиваем camera2
     * напрямую: {@link ProcessCameraProvider} на этот вопрос отвечает только после
     * инициализации, а знать надо раньше — до создания сессии.
     */
    private static boolean hasConcurrentFrontBackPair(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;
        }
        PackageManager packageManager = context.getPackageManager();
        if (packageManager == null
                || !packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_CONCURRENT)) {
            return false;
        }
        CameraManager cameraManager = context.getSystemService(CameraManager.class);
        if (cameraManager == null) {
            return false;
        }
        try {
            for (Set<String> ids : cameraManager.getConcurrentCameraIds()) {
                boolean front = false;
                boolean back = false;
                for (String id : ids) {
                    Integer facing = cameraManager.getCameraCharacteristics(id)
                            .get(CameraCharacteristics.LENS_FACING);
                    if (facing == null) {
                        continue;
                    }
                    if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        front = true;
                    } else if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        back = true;
                    }
                }
                if (front && back) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    private boolean supportsConcurrentFrontBackPair() {
        if (provider == null) {
            return false;
        }
        for (List<CameraInfo> combination : provider.getAvailableConcurrentCameraInfos()) {
            boolean front = false;
            boolean back = false;
            for (CameraInfo info : combination) {
                if (info.getLensFacing() == CameraSelector.LENS_FACING_FRONT) {
                    front = true;
                } else if (info.getLensFacing() == CameraSelector.LENS_FACING_BACK) {
                    back = true;
                }
            }
            if (front && back) {
                return true;
            }
        }
        return false;
    }

    // ---- Зум, фокус, вспышка ----

    /**
     * Стартовый зум. Широкоугольная линза — это зум меньше единицы, и он приезжает не
     * сразу: пока камера не открылась, {@link ZoomState} пуст, поэтому ждём его через
     * наблюдателя и ставим минимум один раз.
     */
    private void applyInitialZoom(final Camera camera, final CameraControl control) {
        if (control == null || camera == null) {
            return;
        }
        control.setZoomRatio(1.0f);
        if (!wantsWideAngleStart(camera)) {
            return;
        }
        final LiveData<ZoomState> zoomState = camera.getCameraInfo().getZoomState();
        if (zoomState.getValue() != null) {
            applyWideAngle(control, zoomState.getValue());
            return;
        }
        zoomState.observe(lifecycle, new Observer<ZoomState>() {
            @Override
            public void onChanged(ZoomState state) {
                if (state == null) {
                    return;
                }
                zoomState.removeObserver(this);
                if (wantsWideAngleStart(camera)) {
                    applyWideAngle(control, state);
                }
            }
        });
    }

    private void applyWideAngle(CameraControl control, ZoomState state) {
        if (state == null || state.getMinZoomRatio() >= 1.0f) {
            return;
        }
        control.setZoomRatio(state.getMinZoomRatio());
    }

    /**
     * Основная камера — по настройке. Фронталка — всегда: у неё зум меньше единицы
     * означает не другую линзу, а полный сенсор вместо кропа (Pixel: 0.9× — это
     * 3840×2736 против 3440×2448 на «1×»), и это её штатный угол.
     */
    private boolean wantsWideAngleStart(Camera camera) {
        if (camera == null) {
            return false;
        }
        return camera.getCameraInfo().getLensFacing() == CameraSelector.LENS_FACING_FRONT
                || ChatsConfig.startWithWideAngleCamera.Bool();
    }

    public void focusToPoint(float x, float y, float viewWidth, float viewHeight) {
        Display display;
        if (cameraControl == null || camera == null || viewWidth <= 0 || viewHeight <= 0
                || (display = getDefaultDisplay()) == null) {
            return;
        }
        try {
            cameraControl.startFocusAndMetering(new FocusMeteringAction.Builder(
                    new DisplayOrientedMeteringPointFactory(display, camera.getCameraInfo(), viewWidth, viewHeight)
                            .createPoint(x, y),
                    FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE).build());
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public void setTorchEnabled(boolean enabled) {
        isTorchOn = enabled;
        updateTorchState();
    }

    /** Подсветка есть только у основной камеры, поэтому фронтальную всегда гасим. */
    private void updateTorchState() {
        try {
            if (!isDualMode) {
                if (camera == null || cameraControl == null || !camera.getCameraInfo().hasFlashUnit()) {
                    return;
                }
                boolean back = camera.getCameraInfo().getLensFacing() == CameraSelector.LENS_FACING_BACK;
                cameraControl.enableTorch(isTorchOn && back);
                return;
            }
            if (cameraControlFront != null) {
                cameraControlFront.enableTorch(false);
            }
            if (cameraControlBack == null || cameraBack == null
                    || !cameraBack.getCameraInfo().hasFlashUnit()) {
                return;
            }
            cameraControlBack.enableTorch(isTorchOn && !isFrontface);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public void setZoomRatio(float ratio) {
        if (cameraControl == null) {
            return;
        }
        cameraControl.setZoomRatio(ratio);
    }

    public float getZoomRatio() {
        ZoomState state = zoomState();
        return state == null ? 1f : state.getZoomRatio();
    }

    public float getMinZoomRatio() {
        ZoomState state = zoomState();
        return state == null ? 1f : state.getMinZoomRatio();
    }

    public float getMaxZoomRatio() {
        ZoomState state = zoomState();
        return state == null ? 1f : state.getMaxZoomRatio();
    }

    public float getLinearZoom() {
        ZoomState state = zoomState();
        return state == null ? 0f : state.getLinearZoom();
    }

    private ZoomState zoomState() {
        return camera == null ? null : camera.getCameraInfo().getZoomState().getValue();
    }

    // ---- Переключение камер ----

    public void switchCamera() {
        isFrontface = !isFrontface;
        if (isDualMode && cameraFront != null && cameraBack != null) {
            // Обе линзы уже привязаны — меняется только та, с которой снимаем.
            updateActiveControl(isFrontface);
            updateTorchState();
        } else {
            rebindCamera();
        }
    }

    private void updateActiveControl(boolean frontface) {
        camera = frontface ? cameraFront : cameraBack;
        cameraControl = frontface ? cameraControlFront : cameraControlBack;
    }

    public void setSecondSurfaceProvider(Preview.SurfaceProvider surfaceProvider) {
        surfaceProviderSecondary = surfaceProvider;
        if (isInitiated && isDualMode && previewUseCaseBack != null) {
            previewUseCaseBack.setSurfaceProvider(surfaceProvider);
        }
    }

    // ---- Состояние ----

    public boolean isInitiated() {
        return isInitiated;
    }

    public boolean isReady() {
        return isInitiated && !isBinding && camera != null;
    }

    public boolean isDualMode() {
        return isDualMode;
    }

    public boolean isFrontface() {
        return isFrontface;
    }

    public boolean isActiveCameraFrontface() {
        if (camera == null) {
            return isFrontface;
        }
        switch (camera.getCameraInfo().getLensFacing()) {
            case CameraSelector.LENS_FACING_FRONT:
                return true;
            case CameraSelector.LENS_FACING_BACK:
                return false;
            default:
                return isFrontface;
        }
    }

    public int getRecordingFrameRate() {
        return recordingFrameRate;
    }

    public int getDisplayOrientation() {
        Display display = getDefaultDisplay();
        int rotation = display != null ? display.getRotation() : Surface.ROTATION_0;
        switch (rotation) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            default:
                return 0;
        }
    }

    private void observeCameraState(Camera camera) {
        try {
            camera.getCameraInfo().getCameraState().observe(lifecycle, state -> {
                CameraState.StateError error = state.getError();
                if (error == null) {
                    return;
                }
                FileLog.e("CameraX camera state error: code=" + error.getCode() + " type=" + state.getType());
                if (error.getCause() != null) {
                    FileLog.e(error.getCause());
                }
            });
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static Display getDefaultDisplay() {
        DisplayManager manager = (DisplayManager) ApplicationLoader.applicationContext
                .getSystemService(Context.DISPLAY_SERVICE);
        return manager != null ? manager.getDisplay(Display.DEFAULT_DISPLAY) : null;
    }

    // ---- Поверхность ----

    /**
     * Мост между CameraX и текстурой GL-потока: CameraX сам просит поверхность, мы
     * отдаём ему {@link SurfaceTexture} кружка и сообщаем наверх итоговый размер —
     * сперва запрошенный, потом уточнённый по кадрированию.
     */
    public static Preview.SurfaceProvider createSurfaceProvider(final Context context,
                                                                final SurfaceTexture surfaceTexture,
                                                                final PreviewSizeListener listener) {
        return request -> {
            try {
                final Size resolution = request.getResolution();
                listener.onPreviewSize(resolution.getWidth(), resolution.getHeight());
                request.setTransformationInfoListener(ContextCompat.getMainExecutor(context), info -> {
                    Rect crop = info.getCropRect();
                    listener.onPreviewSize(
                            crop.width() > 0 ? crop.width() : resolution.getWidth(),
                            crop.height() > 0 ? crop.height() : resolution.getHeight());
                });
                surfaceTexture.setDefaultBufferSize(resolution.getWidth(), resolution.getHeight());
                final Surface surface = new Surface(surfaceTexture);
                request.provideSurface(surface, ContextCompat.getMainExecutor(context), result -> {
                    request.clearTransformationInfoListener();
                    surface.release();
                });
            } catch (Exception e) {
                FileLog.e(e);
                request.willNotProvideSurface();
            }
        };
    }
}
