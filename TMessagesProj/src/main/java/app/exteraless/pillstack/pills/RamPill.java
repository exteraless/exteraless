package app.exteraless.pillstack.pills;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.Context;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import app.exteraless.pillstack.PillType;

/** Занятость оперативной памяти в процентах. */
@SuppressLint("ViewConstructor")
public class RamPill extends TelemetryPill {

    public RamPill(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider, R.drawable.pillstack_ram);
    }

    @Override
    public int getPillId() {
        return PillType.RAM.id;
    }

    @Override
    public long getRefreshInterval() {
        return 3_000L;
    }

    @Override
    protected String measureText() {
        try {
            ActivityManager manager = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            if (info.totalMem <= 0) {
                return null;
            }
            long used = info.totalMem - info.availMem;
            return (used * 100 / info.totalMem) + "%";
        } catch (Exception e) {
            return null;
        }
    }
}
