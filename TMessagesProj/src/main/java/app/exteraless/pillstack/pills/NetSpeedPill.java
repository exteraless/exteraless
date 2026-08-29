package app.exteraless.pillstack.pills;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.TrafficStats;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import app.exteraless.pillstack.PillType;

/**
 * Текущая скорость сети: дельта TrafficStats между замерами.
 * Показываем доминирующее направление: «↓1.2 MB/s» или «↑340 KB/s».
 */
@SuppressLint("ViewConstructor")
public class NetSpeedPill extends TelemetryPill {

    private static long previousRx = -1;
    private static long previousTx;
    private static long previousTime;

    public NetSpeedPill(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider, R.drawable.pillstack_netspeed);
    }

    @Override
    public int getPillId() {
        return PillType.NET_SPEED.id;
    }

    @Override
    public long getRefreshInterval() {
        return 2_000L;
    }

    @Override
    protected String measureText() {
        long rx = TrafficStats.getTotalRxBytes();
        long tx = TrafficStats.getTotalTxBytes();
        if (rx == TrafficStats.UNSUPPORTED || tx == TrafficStats.UNSUPPORTED) {
            return null;
        }
        long now = SystemClock.elapsedRealtime();
        if (previousRx < 0) {
            previousRx = rx;
            previousTx = tx;
            previousTime = now;
            return null;
        }
        long elapsed = now - previousTime;
        if (elapsed <= 0) {
            return null;
        }
        long rxSpeed = Math.max(0, rx - previousRx) * 1000 / elapsed;
        long txSpeed = Math.max(0, tx - previousTx) * 1000 / elapsed;
        previousRx = rx;
        previousTx = tx;
        previousTime = now;
        if (rxSpeed >= txSpeed) {
            return "↓" + AndroidUtilities.formatFileSize(rxSpeed) + "/s";
        }
        return "↑" + AndroidUtilities.formatFileSize(txSpeed) + "/s";
    }
}
