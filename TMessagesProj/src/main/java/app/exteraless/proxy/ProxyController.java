package app.exteraless.proxy;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.proxy.ProxySettings;

import java.util.HashMap;
import java.util.Map;

public final class ProxyController {

    public interface ProxyCountryCallback {
        void onCountryResolved(String country);
    }

    private static final ProxyController INSTANCE = new ProxyController();
    private static final String NAMES_KEY = "exteraless_proxy_names";

    private final HashMap<String, String> names = new HashMap<>();
    private boolean loaded;

    private ProxyController() {
    }

    public static ProxyController getInstance() {
        return INSTANCE;
    }

    private static String keyOf(SharedConfig.ProxyInfo info) {
        return info == null ? "" : info.settings.getLink();
    }

    private synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            String stored = MessagesController.getGlobalMainSettings().getString(NAMES_KEY, null);
            if (TextUtils.isEmpty(stored)) {
                return;
            }
            String[] lines = stored.split("\n");
            for (int i = 0; i + 1 < lines.length; i += 2) {
                if (!TextUtils.isEmpty(lines[i])) {
                    names.put(lines[i], lines[i + 1]);
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private synchronized void persist() {
        try {
            SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
            if (names.isEmpty()) {
                editor.remove(NAMES_KEY);
            } else {
                StringBuilder builder = new StringBuilder();
                for (Map.Entry<String, String> entry : names.entrySet()) {
                    builder.append(entry.getKey()).append('\n').append(entry.getValue()).append('\n');
                }
                editor.putString(NAMES_KEY, builder.toString());
            }
            editor.apply();
        } catch (Throwable ignore) {
        }
    }

    public synchronized String getName(String link) {
        ensureLoaded();
        if (TextUtils.isEmpty(link)) {
            return "";
        }
        String name = names.get(link);
        return name == null ? "" : name;
    }

    public synchronized String getName(SharedConfig.ProxyInfo info) {
        return info == null ? "" : getName(keyOf(info));
    }

    public synchronized void setName(SharedConfig.ProxyInfo info, String name) {
        ensureLoaded();
        String link = keyOf(info);
        if (TextUtils.isEmpty(link)) {
            return;
        }
        if (TextUtils.isEmpty(name)) {
            names.remove(link);
        } else {
            names.put(link, name.replace('\n', ' '));
        }
        persist();
    }

    public synchronized String getDisplayName(SharedConfig.ProxyInfo info) {
        if (info == null) {
            return "";
        }
        String name = getName(info);
        if (TextUtils.isEmpty(name)) {
            name = info.settings.getType() == ProxySettings.Type.WEB
                    ? info.settings.getAddress() + " (WEB)"
                    : info.settings.getAddress() + ":" + info.settings.getPort();
        }
        return name;
    }

    public synchronized String getProxyTypeName(SharedConfig.ProxyInfo info) {
        ProxySettings.Type type = info == null ? ProxySettings.Type.SOCKS5 : info.settings.getType();
        int resId;
        if (type == ProxySettings.Type.WEB) {
            resId = R.string.UseProxyWeb;
        } else if (type == ProxySettings.Type.MTPROTO) {
            resId = R.string.UseProxyTelegram;
        } else {
            resId = R.string.UseProxySocks5;
        }
        return LocaleController.getString(resId);
    }

    public synchronized SharedConfig.ProxyInfo getCurrentProxy() {
        return SharedConfig.currentProxy;
    }

    public synchronized SharedConfig.ProxyInfo addProxy(SharedConfig.ProxyInfo info) {
        return SharedConfig.addProxy(info);
    }

    public synchronized SharedConfig.ProxyInfo saveProxy(SharedConfig.ProxyInfo info, String previousLink, String name) {
        ensureLoaded();
        if (info == null) {
            return null;
        }
        String link = keyOf(info);
        if (!TextUtils.isEmpty(previousLink) && !TextUtils.equals(previousLink, link)) {
            String moved = names.remove(previousLink);
            if (moved != null) {
                names.put(link, moved);
                persist();
            }
        }
        SharedConfig.ProxyInfo saved = SharedConfig.addProxy(info);
        if (name != null) {
            setName(saved, name);
        }
        return saved;
    }

    public synchronized void deleteProxy(SharedConfig.ProxyInfo info) {
        ensureLoaded();
        String link = keyOf(info);
        if (!TextUtils.isEmpty(link) && names.remove(link) != null) {
            persist();
        }
        SharedConfig.deleteProxy(info);
    }

    public void requestProxyCountry(SharedConfig.ProxyInfo info, ProxyCountryCallback callback) {
        if (callback != null) {
            callback.onCountryResolved("");
        }
    }
}
