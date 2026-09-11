package app.exteraless.plugins.ui.catalog;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.List;

import app.exteraless.plugins.PluginsConstants;
import app.exteraless.plugins.PluginsController;
import app.exteraless.plugins.catalog.CatalogCacheStats;
import app.exteraless.plugins.catalog.CatalogCall;
import app.exteraless.plugins.catalog.CatalogCategory;
import app.exteraless.plugins.catalog.CatalogConfig;
import app.exteraless.plugins.catalog.CatalogException;
import app.exteraless.plugins.catalog.CatalogRepository;
import com.exteragram.messenger.preferences.BasePreferencesActivity;

public class PluginCatalogSettingsActivity extends BasePreferencesActivity {

    private static final int ID_SOURCE = 1;
    private static final int ID_TEST = 2;
    private static final int ID_RESET = 3;
    private static final int ID_COMPATIBLE = 4;
    private static final int ID_AUTO_REFRESH = 5;
    private static final int ID_AUTO_UPDATES = 6;
    private static final int ID_CACHE_AGE = 7;
    private static final int ID_CLEAR_CACHE = 8;
    private static final int ID_INSECURE = 9;
    private static final int ID_LOCAL = 10;

    private CatalogRepository repository;
    private CatalogConfig config;
    private SharedPreferences preferences;
    private CatalogCall testCall;
    private boolean sourceVerified;
    private int verifiedCategories;
    private long testGeneration;
    private boolean destroyed;

    @Override
    public String getTitle() {
        return getString(R.string.PluginCatalogSettingsTitle);
    }

    @Override
    public View createView(Context context) {
        repository = new CatalogRepository(context);
        config = repository.getConfig();
        preferences = context.getSharedPreferences(PluginsConstants.PREFS_NAME,
                Context.MODE_PRIVATE);
        View view = super.createView(context);
        if (listView != null) listView.setSections(true);
        return view;
    }

    @Override
    public void fillItems(java.util.ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.PluginCatalogSourceHeader)));
        items.add(CatalogDetailCell.ActionFactory.asAction(ID_SOURCE, R.drawable.msg_link2,
                getString(R.string.PluginCatalogSourceUrl), config.getBaseUrl())
                .setEnabled(testCall == null));
        UItem testItem = UItem.asButton(ID_TEST,
                sourceVerified ? R.drawable.msg_check : R.drawable.msg_retry,
                getString(R.string.PluginCatalogTestSource)).accent();
        if (testCall != null) {
            testItem.setValue(getString(R.string.PluginCatalogLoading)).setEnabled(false);
        } else if (sourceVerified) {
            testItem.setValue(LocaleController.formatPluralString(
                    "PluginCatalogSourceOk", verifiedCategories));
        }
        items.add(testItem);
        items.add(UItem.asButton(ID_RESET, R.drawable.msg_reset,
                getString(R.string.PluginCatalogResetSource))
                .setEnabled(testCall == null));
        items.add(UItem.asShadow(getString(R.string.PluginCatalogSourceHint)));

        items.add(UItem.asHeader(getString(R.string.PluginCatalogBehaviorHeader)));
        items.add(UItem.asCheck(ID_COMPATIBLE,
                        getString(R.string.PluginCatalogCompatibleOnly),
                        getString(R.string.PluginCatalogCompatibleOnlyHint), true)
                .setChecked(config.isCompatibleOnly()));
        items.add(UItem.asCheck(ID_AUTO_REFRESH,
                        getString(R.string.PluginCatalogAutoRefresh),
                        getString(R.string.PluginCatalogAutoRefreshHint), true)
                .setChecked(preferences.getBoolean(PluginCatalogActivity.PREF_AUTO_REFRESH, true)));
        items.add(UItem.asCheck(ID_AUTO_UPDATES,
                        getString(R.string.PluginCatalogAutoUpdateCheck),
                        getString(R.string.PluginCatalogAutoUpdateCheckHint), true)
                .setChecked(preferences.getBoolean(
                        PluginCatalogActivity.PREF_AUTO_UPDATE_CHECK, true)));
        items.add(UItem.asButton(ID_CACHE_AGE, getString(R.string.PluginCatalogCacheAge),
                cacheAgeLabel(config.getCacheMaxAgeMs())));
        items.add(UItem.asShadow(getString(R.string.PluginCatalogBehaviorHint)));

        CatalogCacheStats stats = repository.getCacheStats();
        items.add(UItem.asHeader(getString(R.string.PluginCatalogStorageHeader)));
        items.add(UItem.asButton(ID_CLEAR_CACHE, R.drawable.msg_clear,
                getString(R.string.PluginCatalogClearCache),
                AndroidUtilities.formatFileSize(stats.totalBytes())).red());
        items.add(UItem.asShadow(getString(R.string.PluginCatalogCacheHint)));

        items.add(UItem.asHeader(getString(R.string.PluginCatalogDeveloperHeader)));
        boolean developer = PluginsController.getInstance().isDeveloperMode();
        items.add(UItem.asCheck(ID_INSECURE,
                getString(R.string.PluginCatalogAllowHttp),
                        getString(R.string.PluginCatalogAllowHttpWarning), true)
                .setChecked(config.isInsecureAllowed())
                .setEnabled(developer));
        items.add(UItem.asCheck(ID_LOCAL,
                        getString(R.string.PluginCatalogAllowLocal),
                        getString(R.string.PluginCatalogAllowLocalWarning), true)
                .setChecked(config.isLocalSourcesAllowed())
                .setEnabled(developer));
        items.add(UItem.asShadow(developer
                ? getString(R.string.PluginCatalogDeveloperWarning)
                : getString(R.string.PluginCatalogDeveloperModeRequired)));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (!item.enabled) {
            return;
        }
        switch (item.id) {
            case ID_SOURCE: editSource(); break;
            case ID_TEST: testSource(); break;
            case ID_RESET: resetSource(); break;
            case ID_COMPATIBLE:
                config.setCompatibleOnly(!config.isCompatibleOnly());
                updateRows();
                break;
            case ID_AUTO_REFRESH:
                togglePreference(PluginCatalogActivity.PREF_AUTO_REFRESH, true);
                break;
            case ID_AUTO_UPDATES:
                togglePreference(PluginCatalogActivity.PREF_AUTO_UPDATE_CHECK, true);
                break;
            case ID_CACHE_AGE: chooseCacheAge(); break;
            case ID_CLEAR_CACHE: clearCache(); break;
            case ID_INSECURE: toggleInsecure(); break;
            case ID_LOCAL: toggleLocal(); break;
        }
    }

    private void editSource() {
        EditTextBoldCursor input = new EditTextBoldCursor(getParentActivity());
        input.setSingleLine(true);
        input.setText(config.getBaseUrl());
        input.setSelection(input.length());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        input.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), 0);
        FrameLayout container = new FrameLayout(getParentActivity());
        container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56,
                Gravity.CENTER_VERTICAL, 0, 8, 0, 0));
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.PluginCatalogSourceUrl))
                .setView(container)
                .setPositiveButton(getString(R.string.Save), null)
                .setNegativeButton(getString(R.string.Cancel), null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    try {
                        String normalized = CatalogConfig.normalizeBaseUrl(
                                input.getText().toString(), config.isInsecureAllowed());
                        cancelSourceTest();
                        config.setBaseUrl(normalized);
                        repository.clearCache();
                        sourceVerified = false;
                        verifiedCategories = 0;
                        dialog.dismiss();
                        updateRows();
                    } catch (IllegalArgumentException error) {
                        input.setError(getString(R.string.PluginCatalogInvalidUrl));
                    }
                }));
        dialog.show();
        input.requestFocus();
        AndroidUtilities.showKeyboard(input);
    }

    private void testSource() {
        if (!PluginsController.getInstance().isEngineEnabled()) {
            showInfo(getString(R.string.PluginCatalogEngineOffTitle),
                    getString(R.string.PluginCatalogEngineOffText));
            return;
        }
        if (testCall != null) {
            return;
        }
        final String candidate = config.getBaseUrl();
        final long generation = ++testGeneration;
        testCall = repository.testSource(candidate,
                new CatalogCall.Callback<List<CatalogCategory>>() {
                    @Override
                    public void onSuccess(List<CatalogCategory> value) {
                        if (destroyed || generation != testGeneration
                                || !TextUtils.equals(candidate, config.getBaseUrl())) {
                            return;
                        }
                        testCall = null;
                        sourceVerified = true;
                        verifiedCategories = value.size();
                        updateRows();
                        if (getContext() != null) {
                            BulletinFactory.of(PluginCatalogSettingsActivity.this)
                                    .createSimpleBulletin(R.raw.contact_check,
                                            LocaleController.formatPluralString(
                                                    "PluginCatalogSourceOk", value.size())).show();
                        }
                    }

                    @Override
                    public void onError(CatalogException error) {
                        if (destroyed || generation != testGeneration
                                || !TextUtils.equals(candidate, config.getBaseUrl())) {
                            return;
                        }
                        testCall = null;
                        sourceVerified = false;
                        verifiedCategories = 0;
                        updateRows();
                        if (error.kind != CatalogException.Kind.CANCELLED
                                && getContext() != null) {
                            BulletinFactory.of(PluginCatalogSettingsActivity.this)
                                    .createSimpleBulletin(R.raw.error,
                                            getString(R.string.PluginCatalogSourceFailed)).show();
                        }
                    }
                });
        updateRows();
    }

    private void resetSource() {
        new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.PluginCatalogResetSource))
                .setMessage(getString(R.string.PluginCatalogResetSourceConfirm))
                .setPositiveButton(getString(R.string.Reset), (dialog, which) -> {
                    cancelSourceTest();
                    config.resetBaseUrl();
                    config.setAllowInsecure(false,
                            PluginsController.getInstance().isDeveloperMode());
                    repository.clearCache();
                    sourceVerified = false;
                    verifiedCategories = 0;
                    updateRows();
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private void cancelSourceTest() {
        testGeneration++;
        if (testCall != null) {
            testCall.cancel();
            testCall = null;
        }
    }

    private void clearCache() {
        CatalogCacheStats stats = repository.getCacheStats();
        new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.PluginCatalogClearCache))
                .setMessage(LocaleController.formatString(R.string.PluginCatalogClearCacheConfirm,
                        AndroidUtilities.formatFileSize(stats.totalBytes())))
                .setPositiveButton(getString(R.string.Clear), (dialog, which) -> {
                    repository.clearCache(true);
                    updateRows();
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private void chooseCacheAge() {
        long[] values = {60 * 60 * 1000L, 6 * 60 * 60 * 1000L,
                24 * 60 * 60 * 1000L, 7 * 24 * 60 * 60 * 1000L};
        CharSequence[] labels = {
                getString(R.string.PluginCatalogCacheOneHour),
                getString(R.string.PluginCatalogCacheSixHours),
                getString(R.string.PluginCatalogCacheOneDay),
                getString(R.string.PluginCatalogCacheSevenDays)};
        new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.PluginCatalogCacheAge))
                .setItems(labels, (dialog, which) -> {
                    config.setCacheMaxAgeMs(values[which]);
                    dialog.dismiss();
                    updateRows();
                }).show();
    }

    private void toggleInsecure() {
        boolean developer = PluginsController.getInstance().isDeveloperMode();
        if (!developer) {
            showInfo(getString(R.string.PluginCatalogDeveloperHeader),
                    getString(R.string.PluginCatalogDeveloperModeRequired));
            return;
        }
        if (config.isInsecureAllowed()) {
            config.setAllowInsecure(false, true);
            updateRows();
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.PluginCatalogAllowHttp))
                .setMessage(getString(R.string.PluginCatalogAllowHttpConfirm))
                .setPositiveButton(getString(R.string.Enable), (dialog, which) -> {
                    config.setAllowInsecure(true, true);
                    updateRows();
                })
                .setNegativeButton(getString(R.string.Cancel), null).show();
    }

    private void toggleLocal() {
        boolean developer = PluginsController.getInstance().isDeveloperMode();
        if (!developer) {
            showInfo(getString(R.string.PluginCatalogDeveloperHeader),
                    getString(R.string.PluginCatalogDeveloperModeRequired));
            return;
        }
        boolean enabled = config.isLocalSourcesAllowed();
        if (enabled) {
            config.setAllowLocalSources(false, true);
            updateRows();
        } else {
            new AlertDialog.Builder(getParentActivity())
                    .setTitle(getString(R.string.PluginCatalogAllowLocal))
                    .setMessage(getString(R.string.PluginCatalogAllowLocalConfirm))
                    .setPositiveButton(getString(R.string.Enable), (dialog, which) -> {
                        config.setAllowLocalSources(true, true);
                        updateRows();
                    })
                    .setNegativeButton(getString(R.string.Cancel), null).show();
        }
    }

    private void togglePreference(String key, boolean fallback) {
        preferences.edit().putBoolean(key, !preferences.getBoolean(key, fallback)).apply();
        updateRows();
    }

    private CharSequence cacheAgeLabel(long value) {
        if (value <= 60 * 60 * 1000L) return getString(R.string.PluginCatalogCacheOneHour);
        if (value <= 6 * 60 * 60 * 1000L) return getString(R.string.PluginCatalogCacheSixHours);
        if (value <= 24 * 60 * 60 * 1000L) return getString(R.string.PluginCatalogCacheOneDay);
        return getString(R.string.PluginCatalogCacheSevenDays);
    }

    private void showInfo(CharSequence title, CharSequence message) {
        new AlertDialog.Builder(getParentActivity()).setTitle(title).setMessage(message)
                .setPositiveButton(getString(R.string.OK), null).show();
    }

    private void updateRows() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(org.telegram.messenger.SharedConfig.animationsEnabled());
        }
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        cancelSourceTest();
        super.onFragmentDestroy();
    }
}
