package app.exteraless.plugins.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import app.exteraless.plugins.PluginsController;

final class PluginSliderCell extends LinearLayout {
    private final TextView title;
    private final TextView subtitle;
    private final SeekBar slider;
    private JSONObject row;
    private double minimum, maximum, step;
    private boolean tracking;
    private String pendingValue;

    PluginSliderCell(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(12), AndroidUtilities.dp(21), AndroidUtilities.dp(6));
        title = new TextView(context);
        title.setTextSize(16);
        addView(title, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        subtitle = new TextView(context);
        subtitle.setTextSize(13);
        addView(subtitle, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        slider = new SeekBar(context);
        addView(slider, new LayoutParams(LayoutParams.MATCH_PARENT, AndroidUtilities.dp(40)));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (row == null || !fromUser) return;
                double value = Math.min(maximum, minimum + progress * step);
                try {
                    row.put("value", value);
                } catch (org.json.JSONException ignored) {
                }
                String json = row.optBoolean("integral") ? Long.toString(Math.round(value)) : Double.toString(value);
                showValue(json);
                if (tracking) {
                    pendingValue = json;
                } else {
                    PluginsController.getInstance().notifySettingChanged(row.optString("plugin_id"), row.optString("key"), json);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                tracking = true;
                pendingValue = null;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                tracking = false;
                String value = pendingValue;
                pendingValue = null;
                if (row == null) return;
                if (value != null) {
                    PluginsController.getInstance().notifySettingChanged(row.optString("plugin_id"), row.optString("key"), value);
                }
                PluginsController.getInstance().reloadSettingsScreen(row.optString("plugin_id"));
            }
        });
    }

    private void showValue(String value) {
        title.setText(row.optString("text") + " · " + value);
    }

    void bind(JSONObject data) {
        row = data;
        minimum = row.optDouble("min", 0);
        maximum = row.optDouble("max", 100);
        step = row.optDouble("step", 1);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        subtitle.setText(row.optString("subtext", ""));
        subtitle.setVisibility(subtitle.length() == 0 ? GONE : VISIBLE);
        ColorStateList tint = ColorStateList.valueOf(Theme.getColor(Theme.key_switchTrackChecked));
        slider.setProgressTintList(tint);
        slider.setThumbTintList(tint);
        boolean valid = Double.isFinite(minimum) && Double.isFinite(maximum) && Double.isFinite(step)
                && maximum > minimum && step > 0 && Math.ceil((maximum - minimum) / step) <= Integer.MAX_VALUE;
        slider.setEnabled(valid);
        if (!valid) return;
        double value = Math.max(minimum, Math.min(maximum, row.optDouble("value", minimum)));
        slider.setMax((int) Math.ceil((maximum - minimum) / step));
        slider.setProgress((int) Math.round((value - minimum) / step));
        showValue(row.optBoolean("integral") ? Long.toString(Math.round(value)) : Double.toString(value));
    }

    public static class Factory extends UItem.UItemFactory<PluginSliderCell> {
        @Override
        public PluginSliderCell createView(Context context, RecyclerListView list, int account, int guid,
                                           Theme.ResourcesProvider provider) {
            return new PluginSliderCell(context);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView list) {
            ((PluginSliderCell) view).bind((JSONObject) item.object);
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
            if (first.id != second.id) {
                return false;
            }
            if (first.object == second.object) {
                return true;
            }
            if (!(first.object instanceof JSONObject) || !(second.object instanceof JSONObject)) {
                return false;
            }
            JSONObject a = (JSONObject) first.object;
            JSONObject b = (JSONObject) second.object;
            return a.optDouble("value", 0) == b.optDouble("value", 0)
                    && a.optDouble("min", 0) == b.optDouble("min", 0)
                    && a.optDouble("max", 100) == b.optDouble("max", 100)
                    && a.optDouble("step", 1) == b.optDouble("step", 1)
                    && a.optBoolean("integral") == b.optBoolean("integral")
                    && a.optString("text").equals(b.optString("text"))
                    && a.optString("subtext").equals(b.optString("subtext"))
                    && a.optString("key").equals(b.optString("key"));
        }
    }
}
