package app.exteraless.plugins.ui.catalog;

import android.app.Activity;
import android.os.Build;
import android.text.TextUtils;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;

import java.util.List;

import app.exteraless.plugins.PluginInstallHelper;
import app.exteraless.plugins.Plugin;
import app.exteraless.plugins.PluginsController;
import app.exteraless.plugins.catalog.CatalogCall;
import app.exteraless.plugins.catalog.CatalogDownload;
import app.exteraless.plugins.catalog.CatalogException;
import app.exteraless.plugins.catalog.CatalogPlugin;
import app.exteraless.plugins.catalog.CatalogRepository;
import app.exteraless.plugins.catalog.CatalogVersion;

final class CatalogInstallCoordinator {

    interface Delegate {
        void onBusyChanged(boolean busy);
        void onFailure(CatalogException error);
        void onInstalled();
    }

    static final class Labels {
        final CharSequence unknownTitle;
        final CharSequence unknownMessage;
        final CharSequence unsupportedTitle;
        final CharSequence unsupportedMessage;
        final CharSequence riskTitle;
        final CharSequence riskMessage;
        final CharSequence continueText;
        final CharSequence telegramTitle;
        final CharSequence telegramMessage;
        final CharSequence openTelegram;
        final CharSequence noVersion;

        Labels(CharSequence unknownTitle, CharSequence unknownMessage,
               CharSequence unsupportedTitle, CharSequence unsupportedMessage,
               CharSequence riskTitle, CharSequence riskMessage,
               CharSequence continueText, CharSequence telegramTitle,
               CharSequence telegramMessage, CharSequence openTelegram,
               CharSequence noVersion) {
            this.unknownTitle = unknownTitle;
            this.unknownMessage = unknownMessage;
            this.unsupportedTitle = unsupportedTitle;
            this.unsupportedMessage = unsupportedMessage;
            this.riskTitle = riskTitle;
            this.riskMessage = riskMessage;
            this.continueText = continueText;
            this.telegramTitle = telegramTitle;
            this.telegramMessage = telegramMessage;
            this.openTelegram = openTelegram;
            this.noVersion = noVersion;
        }
    }

    private final Activity activity;
    private final CatalogRepository repository;
    private final Labels labels;
    private final Delegate delegate;
    private CatalogCall call;
    private AlertDialog dialog;
    private boolean busy;
    private CatalogPlugin activePlugin;
    private long activeVersionId = -1;
    private boolean cancelled;

    CatalogInstallCoordinator(Activity activity, CatalogRepository repository, Labels labels,
                              Delegate delegate) {
        this.activity = activity;
        this.repository = repository;
        this.labels = labels;
        this.delegate = delegate;
    }

    void install(CatalogPlugin plugin) {
        install(plugin, null);
    }

    void install(CatalogPlugin plugin, CatalogVersion requestedVersion) {
        if (plugin == null || busy) {
            return;
        }
        cancelled = false;
        if (Boolean.FALSE.equals(plugin.exteralessCompatible)) {
            if (!PluginsController.getInstance().isDeveloperMode()) {
                showInfo(labels.unsupportedTitle, labels.unsupportedMessage);
                return;
            }
            confirm(labels.unsupportedTitle, labels.unsupportedMessage,
                    () -> confirmRisk(plugin, requestedVersion));
        } else if (plugin.exteralessCompatible == null) {
            confirm(labels.unknownTitle, labels.unknownMessage,
                    () -> confirmRisk(plugin, requestedVersion));
        } else {
            confirmRisk(plugin, requestedVersion);
        }
    }

    private void confirmRisk(CatalogPlugin plugin, CatalogVersion requestedVersion) {
        confirm(labels.riskTitle, labels.riskMessage,
                () -> startInstall(plugin, requestedVersion));
    }

    void cancel() {
        cancelled = true;
        if (call != null) {
            call.cancel();
            call = null;
        }
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
        setBusy(false);
    }

    String getActiveSlug() {
        return activePlugin == null ? null : activePlugin.slug;
    }

    long getActiveVersionId() {
        return activeVersionId;
    }

    private void startInstall(CatalogPlugin plugin, CatalogVersion requestedVersion) {
        activePlugin = plugin;
        activeVersionId = requestedVersion == null ? -1 : requestedVersion.id;
        setBusy(true);
        if (requestedVersion != null) {
            download(plugin, requestedVersion);
        } else {
            fetchVersions(plugin);
        }
    }

    private void fetchVersions(CatalogPlugin plugin) {
        call = repository.getVersions(plugin.slug, new CatalogCall.Callback<List<CatalogVersion>>() {
            @Override
            public void onSuccess(List<CatalogVersion> versions) {
                if (!canContinue()) return;
                CatalogVersion version = selectVersion(plugin, versions);
                if (version == null) {
                    setBusy(false);
                    showInfo(LocaleController.getString(R.string.AppName), labels.noVersion);
                    return;
                }
                activeVersionId = version.id;
                if (delegate != null) delegate.onBusyChanged(true);
                download(plugin, version);
            }

            @Override
            public void onError(CatalogException error) {
                if (!canContinue()) return;
                fail(error);
            }
        });
    }

    private void download(CatalogPlugin plugin, CatalogVersion version) {
        call = repository.downloadVersion(plugin, version,
                new CatalogCall.Callback<CatalogDownload>() {
                    @Override
                    public void onSuccess(CatalogDownload download) {
                        if (!canContinue()) return;
                        call = null;
                        if (download.kind == CatalogDownload.Kind.VERIFIED_FILE
                                && download.verified && download.file != null) {
                            PluginInstallHelper.confirmAndInstall(activity, download.file,
                                    (ok, error, installed) -> onInstallCompleted(
                                            download, ok, installed));
                        } else if (download.kind == CatalogDownload.Kind.TELEGRAM
                                && !TextUtils.isEmpty(download.telegramDeeplink)) {
                            setBusy(false);
                            showTelegram(download.telegramDeeplink);
                        } else {
                            fail(new CatalogException(CatalogException.Kind.PROTOCOL,
                                    "Catalog returned no installable artifact"));
                        }
                    }

                    @Override
                    public void onError(CatalogException error) {
                        if (!canContinue()) return;
                        fail(error);
                    }
                });
    }

    private CatalogVersion selectVersion(CatalogPlugin plugin, List<CatalogVersion> versions) {
        if (versions == null) {
            return null;
        }
        CatalogVersion stable = null;
        for (CatalogVersion version : versions) {
            if (TextUtils.equals(version.version, plugin.version)) {
                return version;
            }
            if (stable == null && version.stable) {
                stable = version;
            }
        }
        return stable != null ? stable : versions.isEmpty() ? null : versions.get(0);
    }

    private void showTelegram(String url) {
        if (!canContinue()) return;
        dialog = new AlertDialog.Builder(activity)
                .setTitle(labels.telegramTitle)
                .setMessage(labels.telegramMessage)
                .setPositiveButton(labels.openTelegram, (dialog, which) ->
                        Browser.openUrl(activity, url))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void confirm(CharSequence title, CharSequence message, Runnable positive) {
        if (!canContinue()) return;
        dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(labels.continueText, (dialog, which) -> {
                    if (canContinue()) positive.run();
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void showInfo(CharSequence title, CharSequence message) {
        if (!canContinue()) return;
        dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .show();
    }

    private void fail(CatalogException error) {
        call = null;
        setBusy(false);
        if (error.kind != CatalogException.Kind.CANCELLED && delegate != null) {
            delegate.onFailure(error);
        }
    }

    private void setBusy(boolean value) {
        if (busy == value) {
            return;
        }
        busy = value;
        if (!value) {
            activePlugin = null;
            activeVersionId = -1;
        }
        if (delegate != null) {
            delegate.onBusyChanged(value);
        }
    }

    private void onInstallCompleted(CatalogDownload download, boolean ok, Plugin installed) {
        setBusy(false);
        if (!ok || installed == null || TextUtils.isEmpty(installed.id)) {
            return;
        }
        try {
            repository.getIdentityStore().recordVerifiedDownloadIdentity(download, installed.id);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        if (!cancelled && delegate != null) {
            delegate.onInstalled();
        }
    }

    private boolean canContinue() {
        return !cancelled && !activity.isFinishing()
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1
                || !activity.isDestroyed());
    }
}
