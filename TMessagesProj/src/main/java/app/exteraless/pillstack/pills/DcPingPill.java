package app.exteraless.pillstack.pills;

import android.annotation.SuppressLint;
import android.content.Context;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.Theme;

import app.exteraless.pillstack.PillType;

/**
 * Пинг до дата-центра текущего аккаунта. Число берём у нативного MTProto-соединения
 * ({@code native_getCurrentPingTime}) — никаких своих сокетов не открываем.
 */
@SuppressLint("ViewConstructor")
public class DcPingPill extends TelemetryPill {

    public DcPingPill(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider, R.drawable.pillstack_ping);
    }

    @Override
    public int getPillId() {
        return PillType.DC_PING.id;
    }

    @Override
    public long getRefreshInterval() {
        return 5_000L;
    }

    @Override
    protected String measureText() {
        try {
            int ping = ConnectionsManager.native_getCurrentPingTime(UserConfig.selectedAccount);
            if (ping <= 0) {
                return null;
            }
            return LocaleController.formatString(R.string.PillStackDcPingValue, ping);
        } catch (Exception e) {
            return null;
        }
    }
}
