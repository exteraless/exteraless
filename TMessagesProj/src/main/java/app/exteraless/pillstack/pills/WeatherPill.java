package app.exteraless.pillstack.pills;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Stories.recorder.Weather;

import app.exteraless.pillstack.PillStackConfig;
import app.exteraless.pillstack.PillStackEvents;
import app.exteraless.pillstack.PillType;
import app.exteraless.pillstack.pills.weather.WeatherSettingsActivity;

/**
 * Погода: эмодзи + температура. Локация — либо текущая (через {@link Weather#fetch}),
 * либо точка, выбранная в настройках.
 */
@SuppressLint("ViewConstructor")
public class WeatherPill extends BasePill implements PillStackEvents.Listener {

    private final LinearLayout layout;
    private final ImageView iconView;
    private final AnimatedTextView textView;
    private boolean requestInFlight;
    private boolean showingWeather;

    public WeatherPill(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider);

        layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER);
        layout.setMinimumWidth(AndroidUtilities.dp(48));
        layout.setPadding(AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8), 0);
        addView(layout, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 28,
                (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL));

        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        layout.addView(iconView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

        textView = new AnimatedTextView(context, true, true, true);
        textView.setTextSize(AndroidUtilities.dp(13));
        textView.setTypeface(AndroidUtilities.bold());
        textView.setIncludeFontPadding(false);
        textView.adaptWidth = true;
        NotificationCenter.listenEmojiLoading(textView);
        layout.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        setLoadingTargetView(layout);
        updateColors();
        ScaleStateListAnimator.apply(layout);

        Weather.State cached = Weather.getCached();
        if (cached != null) {
            setData(cached, false);
        }
    }

    @Override
    public int getPillId() {
        return PillType.WEATHER.id;
    }

    @Override
    public long getRefreshInterval() {
        return 15 * 60 * 1000L;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (PillStackEvents.checkAndClearPendingUpdate(getPillId()) || Weather.getCached() == null || isRefreshDue()) {
            onUpdateData(false);
        }
        PillStackEvents.addListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        PillStackEvents.removeListener(this);
    }

    @Override
    public void onPillStackSettingsChanged(int[] pillIds) {
        if (PillStackEvents.shouldUpdatePill(pillIds, getPillId())) {
            PillStackEvents.checkAndClearPendingUpdate(getPillId());
            onUpdateData(true);
        }
    }

    @Override
    public void onUpdateData(boolean force) {
        if (PillStackConfig.useCurrentLocation()) {
            // без разрешения/со включёнными службами геолокации сеть дёргать бессмысленно —
            // показываем состояние-подсказку, тап по которой запросит разрешение
            if (!WeatherSettingsActivity.isLocationPermissionGranted()) {
                setLocationState(R.string.PillStackWeatherPermissionGrant, showingWeather);
                return;
            }
            if (!WeatherSettingsActivity.isLocationEnabled()) {
                setLocationState(R.string.PillStackWeatherServicesEnable, showingWeather);
                return;
            }
        }
        if (requestInFlight) {
            return;
        }
        requestInFlight = true;
        startLoading();
        if (!PillStackConfig.useCurrentLocation() && PillStackConfig.hasCustomWeatherLocation()) {
            Weather.fetch(PillStackConfig.customWeatherLatitude(), PillStackConfig.customWeatherLongitude(), this::onWeatherFetched);
        } else {
            Weather.fetch(false, this::onWeatherFetched);
        }
    }

    private void onWeatherFetched(Weather.State state) {
        requestInFlight = false;
        if (state == null) {
            setErrorState(true);
        } else {
            markDataUpdated();
            setData(state, true);
        }
    }

    public void setData(Weather.State state, boolean animated) {
        stopLoading();
        if (animated) {
            animateSizeChange();
        }
        // Эмодзи погоды подменяется векторной иконкой, и тогда в тексте остаётся
        // только температура. Если иконки для этого эмодзи нет — показываем
        // «эмодзи + температура».
        final int iconRes = getWeatherIconRes(state.getEmoji());
        if (iconRes != 0) {
            iconView.setImageResource(iconRes);
            iconView.setVisibility(VISIBLE);
            textView.setText(state.getTemperature(), animated);
        } else {
            iconView.setVisibility(GONE);
            String emoji = state.getEmoji();
            String text = (emoji == null ? "" : emoji + " ") + state.getTemperature();
            textView.setText(text, animated);
        }
        textView.setVisibility(VISIBLE);
        showingWeather = true;
    }

    /**
     * Карта «эмодзи погоды → вектор». Восстановлена из smali exteraGram: jadx не осилил
     * {@code WeatherPill.getWeatherIconRes} (:66), тело осталось пустым switch'ом.
     *
     * Сравнение точное, без вариационного селектора U+FE0F — {@code Weather.State.getEmoji()}
     * отдаёт базовый кодпоинт.
     */
    private static int getWeatherIconRes(String emoji) {
        if (emoji == null) {
            return 0;
        }
        switch (emoji) {
            case "☀":            return R.drawable.weather_sunny;
            case "☁":            return R.drawable.weather_cloudy;
            case "⚡":
            case "⛈":            return R.drawable.weather_thunderstorm;
            case "⛅":
            case "🌤":      return R.drawable.weather_partly_cloudy;
            case "❄":
            case "🌨":      return R.drawable.weather_snowy;
            case "🌦":
            case "🌧":      return R.drawable.weather_rainy;
            case "😶‍🌫": return R.drawable.weather_foggy;
            case "🌓":
            case "🌔":
            case "🌖":
            case "🌗":
            case "🌚":
            case "🌛":
            case "🌜":
            case "🌝":      return R.drawable.weather_night;
            default:                  return 0;
        }
    }

    private void setErrorState(boolean animated) {
        stopLoading();
        if (animated) {
            animateSizeChange();
        }
        iconView.setImageResource(R.drawable.msg_retry);
        iconView.setVisibility(VISIBLE);
        textView.setText(LocaleController.getString(R.string.Retry), animated);
        textView.setVisibility(VISIBLE);
        showingWeather = false;
    }

    /** Состояние «нет геопозиции»: иконка пина и подсказка вместо температуры. */
    private void setLocationState(int textResId, boolean animated) {
        requestInFlight = false;
        stopLoading();
        if (animated) {
            animateSizeChange();
        }
        iconView.setImageResource(R.drawable.filled_location);
        iconView.setVisibility(VISIBLE);
        textView.setText(LocaleController.getString(textResId), animated);
        textView.setVisibility(VISIBLE);
        showingWeather = false;
    }

    @Override
    public void onPillClicked() {
        if (PillStackConfig.useCurrentLocation() && !showingWeather
                && (!WeatherSettingsActivity.isLocationPermissionGranted() || !WeatherSettingsActivity.isLocationEnabled())) {
            // Weather.getUserLocation сам покажет системный запрос разрешения / диалог про GPS
            Weather.getUserLocation(true, location -> {
                if (location != null) {
                    onUpdateData(true);
                }
            });
            return;
        }
        onPillLongClicked();
    }

    @Override
    public boolean onPillLongClicked() {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null) {
            return false;
        }
        ItemOptions.makeOptions(fragment, this)
                .add(R.drawable.msg_retry, LocaleController.getString(R.string.Refresh), () -> onUpdateData(true))
                .add(R.drawable.msg_settings, LocaleController.getString(R.string.Settings),
                        () -> fragment.presentFragment(new WeatherSettingsActivity()))
                .setDrawScrim(false)
                .setDimAlpha(0)
                .show();
        return true;
    }

    @Override
    public void drawableHotspotChanged(float x, float y) {
        if (loading) {
            return;
        }
        super.drawableHotspotChanged(x, y);
        layout.drawableHotspotChanged(x - layout.getLeft(), y - layout.getTop());
    }

    @Override
    public void setPressed(boolean pressed) {
        if (loading) {
            pressed = false;
        }
        super.setPressed(pressed);
        layout.setPressed(pressed);
    }

    @Override
    public void updateColors() {
        int color = getThemedColor(Theme.key_windowBackgroundWhiteBlackText, 0.75f);
        layout.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(14),
                Theme.isCurrentThemeDark() ? getThemedColor(Theme.key_windowBackgroundWhite) : Theme.multAlpha(color, 0.09f),
                Theme.multAlpha(color, 0.1f)));
        textView.setTextColor(color);
        iconView.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
        updateLoadingColors();
    }
}
