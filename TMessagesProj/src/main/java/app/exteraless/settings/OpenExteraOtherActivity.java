package app.exteraless.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.CountDownTimer;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;



import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;

import app.exteraless.backup.EtgBackup;
import app.exteraless.backup.EtgBackupUi;
import app.exteraless.general.GeneralConfig;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import app.exteraless.general.GeneralHelper;
import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.ConfigItem;
import tw.nekomimi.nekogram.helpers.AppRestartHelper;
import tw.nekomimi.nekogram.helpers.SettingsBackupHelper;
import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;
import xyz.nextalone.nagram.NaConfig;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;
import tw.nekomimi.nekogram.utils.AlertUtil;
import tw.nekomimi.nekogram.utils.AndroidUtil;

/**
 * Экран «Other» раздела openExtera — повторяет Other из exteraGram
 * (секция Google + управление настройками).
 */
public class OpenExteraOtherActivity extends BaseNekoSettingsActivity {

    /** Кнопка удаления остаётся заблокированной 30 секунд. */
    private static final long DELETE_ACCOUNT_DELAY = 30_000L;

    private static final int ETG_IMPORT_REQUEST_CODE = 22;

    private int googleHeaderRow;
    private int crashReportsRow;
    private int googleDividerRow;
    private int nagramHeaderRow;
    private int nagramSettingsRow;
    private int ayuMomentsRow;
    private int nagramDividerRow;

    private int experimentalHeaderRow;
    private int localPremiumRow;
    private int unlimitedPinnedDialogsRow;
    private int voiceEnhancementsRow;
    private int enhancedVideoBitrateRow;
    private int sensitiveContentRow;
    private int experimentalDividerRow;
    private boolean sensitiveEnabled;
    private boolean sensitiveCanChange;

    private int exportEtgRow;
    private int importEtgRow;
    private int etgDividerRow;
    private int glyphRow;
    private int glyphDividerRow;
    private int resetSettingsRow;
    private int deleteAccountRow;
    private int bottomDividerRow;

    private CountDownTimer deleteAccountTimer;

    public OpenExteraOtherActivity() {
        super();
    }

    @Override
    public boolean onFragmentCreate() {
        GeneralConfig.init();
        return super.onFragmentCreate();
    }

    @Override
    public void onResume() {
        super.onResume();
        checkSensitiveContent();
    }

    @Override
    public void onFragmentDestroy() {
        cancelDeleteAccountTimer();
        super.onFragmentDestroy();
    }

    @Override
    protected void updateRows() {
        super.updateRows();

        // Секции Google (Analytics + Crashlytics) здесь больше нет. Отправлять
        // было нечего: shouldEnableCrashlytics требует applicationId
        // «nu.gpu.nagram», а у нас com.exteraless.app — переключатель стоял
        // мёртвым. На его месте вход в настройки NagramX, выключенный по
        // умолчанию.
        googleHeaderRow = addRow("googleHeader");
        crashReportsRow = addRow("crashReports");
        googleDividerRow = addRow();

        nagramHeaderRow = addRow("nagramHeader");
        nagramSettingsRow = -1;
        ayuMomentsRow = addRow("ayuMoments");
        nagramDividerRow = addRow();

        experimentalHeaderRow = addRow("experimentalHeader");
        localPremiumRow = addRow("localPremium");
        unlimitedPinnedDialogsRow = addRow("unlimitedPinnedDialogs", "UnlimitedPinnedDialogs");
        voiceEnhancementsRow = addRow("noiseSuppressAndVoiceEnhance", "NoiseSuppressAndVoiceEnhance");
        enhancedVideoBitrateRow = addRow("enhancedVideoBitrate", "EnhancedVideoBitrate");
        sensitiveContentRow = addRow("sensitiveDisableFiltering", "SensitiveDisableFiltering");
        experimentalDividerRow = addRow();

        exportEtgRow = addRow("exportEtgSettings");
        importEtgRow = addRow("importEtgSettings");
        etgDividerRow = addRow();

        glyphRow = addRow("glyph");
        glyphDividerRow = addRow();

        resetSettingsRow = addRow("resetSettings");
        deleteAccountRow = addRow("deleteAccount");
        bottomDividerRow = addRow();
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.OEGeneralOtherTitle);
    }

    @Override
    public int getSearchGuid() {
        return 23000;
    }

    @Override
    public int getSearchIcon() {
        return R.drawable.msg_fave;
    }

    @Override
    public String getSearchPrefix() {
        return "OEGeneral";
    }

    @Override
    protected String getKey() {
        return "exteraless_other";
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == crashReportsRow) {
            boolean enabled = GeneralConfig.crashReports.toggleConfigBool();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(
                    AndroidUtil.shouldEnableCrashlytics());
        } else if (position == nagramSettingsRow) {
            boolean enabled = GeneralConfig.showNagramSettings.toggleConfigBool();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            // Строка появляется и исчезает в общем списке настроек, а он уже
            // построен и на свои уведомления её не пересобирает — поэтому
            // пересобираем вьюхи стека целиком, как это делают остальные
            // настройки, меняющие чужие экраны.
            if (getParentLayout() != null) {
                getParentLayout().rebuildAllFragmentViews(false, false);
            }
        } else if (position == ayuMomentsRow) {
            presentFragment(new OpenExteraAyuMomentsActivity());
        } else if (position == localPremiumRow) {
            toggleCheck(view, NekoConfig.localPremium);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
        } else if (position == unlimitedPinnedDialogsRow) {
            toggleCheck(view, NekoConfig.unlimitedPinnedDialogs);
        } else if (position == voiceEnhancementsRow) {
            toggleCheck(view, NaConfig.INSTANCE.getNoiseSuppressAndVoiceEnhance());
        } else if (position == enhancedVideoBitrateRow) {
            toggleCheck(view, NaConfig.INSTANCE.getEnhancedVideoBitrate());
        } else if (position == sensitiveContentRow) {
            toggleSensitiveContent(view);
        } else if (position == exportEtgRow) {
            exportEtgSettings();
        } else if (position == importEtgRow) {
            openEtgFilePicker();
        } else if (position == glyphRow) {
            presentFragment(new OpenExteraGlyphActivity());
        } else if (position == resetSettingsRow) {
            showResetSettingsDialog();
        } else if (position == deleteAccountRow) {
            showDeleteAccountDialog();
        }
    }

    private void toggleCheck(View view, ConfigItem config) {
        boolean enabled = config.toggleConfigBool();
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(enabled);
        }
    }

    private void checkSensitiveContent() {
        TL_account.contentSettings cached = getMessagesController().getContentSettings();
        if (cached != null) {
            applySensitiveContent(cached);
        } else {
            sensitiveEnabled = getMessagesController().showSensitiveContent();
            bindSensitiveCell();
        }
        getMessagesController().getContentSettings(settings -> {
            if (settings != null) {
                applySensitiveContent(settings);
            }
        });
    }

    private void applySensitiveContent(TL_account.contentSettings settings) {
        if (sensitiveEnabled == settings.sensitive_enabled && sensitiveCanChange == settings.sensitive_can_change) {
            return;
        }
        sensitiveEnabled = settings.sensitive_enabled;
        sensitiveCanChange = settings.sensitive_can_change;
        bindSensitiveCell();
    }

    private void bindSensitiveCell() {
        if (listView == null || sensitiveContentRow < 0) {
            return;
        }
        RecyclerView.ViewHolder holder = listView.findViewHolderForAdapterPosition(sensitiveContentRow);
        if (holder != null && holder.itemView instanceof TextCheckCell) {
            TextCheckCell cell = (TextCheckCell) holder.itemView;
            cell.setChecked(sensitiveEnabled);
            cell.setEnabled(sensitiveCanChange, null);
        }
    }

    private void toggleSensitiveContent(View view) {
        if (!sensitiveCanChange || getParentActivity() == null) {
            return;
        }
        sensitiveEnabled = !sensitiveEnabled;
        getMessagesController().setContentSettings(sensitiveEnabled);
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(sensitiveEnabled);
        }
    }

    private void exportEtgSettings() {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, getResourceProvider());
        builder.setTitle(getString(R.string.OEGeneralExportEtgSettings));
        builder.setItems(new CharSequence[]{
                getString(R.string.OEGeneralExportFormatFull),
                getString(R.string.OEGeneralExportFormatExtera)
        }, (dialog, which) -> {
            if (which == 0) {
                SettingsBackupHelper.backupSettings(activity, getResourceProvider());
            } else {
                EtgBackupUi.export(this);
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void openEtgFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, ETG_IMPORT_REQUEST_CODE);
        } catch (Exception e) {
            AlertUtil.showSimpleAlert(getParentActivity(), e);
        }
    }

    /**
     * Выбранный файл копируется в кэш под своим расширением: провайдер отдаёт content://,
     * а читать бэкап удобнее обычным файлом.
     */
    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != ETG_IMPORT_REQUEST_CODE) {
            super.onActivityResultFragment(requestCode, resultCode, data);
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        String name = MediaController.getFileName(uri);
        boolean json = name != null && name.toLowerCase(Locale.ROOT).endsWith(".json");
        File file = new File(AndroidUtilities.getCacheDir(),
                UUID.randomUUID().toString().replace("-", "") + (json ? ".nekox-settings.json" : EtgBackup.EXTENSION));
        try (InputStream input = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                return;
            }
            try (OutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                output.flush();
            }
        } catch (Exception e) {
            AlertUtil.showSimpleAlert(getParentActivity(), e);
            return;
        }
        if (json) {
            SettingsBackupHelper.importSettings(getParentActivity(), file);
        } else {
            EtgBackupUi.confirmImport(this, file);
        }
    }

    private void showResetSettingsDialog() {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        AlertUtil.showConfirm(activity,
                getString(R.string.OEGeneralResetSettings),
                getString(R.string.OEGeneralResetSettingsInfo),
                R.drawable.msg_reset,
                getString(R.string.OEGeneralResetSettings),
                true,
                () -> {
                    GeneralHelper.resetAllSettings();
                    LocaleController.getInstance().recreateFormatters();
                    // Ресурсы темы надо перечитать: иначе не подхватятся радиусы и цвета,
                    // сброшенные вместе с настройками.
                    Theme.reloadAllResources(activity);
                    if (getParentLayout() != null) {
                        getParentLayout().rebuildAllFragmentViews(false, false);
                    }
                    getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
                    getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
                    getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload, true);
                    // У exteraGram бюллетень именно «ошибочный» (красный) — это предупреждение, а не успех.
                    BulletinFactory.of(OpenExteraOtherActivity.this)
                            .createErrorBulletin(getString(R.string.OEGeneralResetSettingsDone))
                            .show();
                    AppRestartHelper.triggerRebirth(activity, new Intent(activity, LaunchActivity.class));
                });
    }

    /**
     * Удаление аккаунта: подтверждение с обратным отсчётом, затем
     * TL_account.deleteAccount и локальный выход.
     */
    private void showDeleteAccountDialog() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.OEGeneralDeleteAccount));
        builder.setMessage(getString(R.string.TosDeclineDeleteAccount));
        builder.setPositiveButton(getString(R.string.Deactivate), (dialog, which) -> deleteAccount());
        builder.setNegativeButton(getString(R.string.Cancel), null);

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            View button = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (!(button instanceof TextView)) {
                return;
            }
            TextView textView = (TextView) button;
            textView.setTextColor(Theme.getColor(Theme.key_text_RedBold));
            textView.setEnabled(false);
            CharSequence text = textView.getText();
            cancelDeleteAccountTimer();
            deleteAccountTimer = new CountDownTimer(DELETE_ACCOUNT_DELAY, 100L) {
                @Override
                public void onTick(long millisUntilFinished) {
                    textView.setText(String.format(Locale.getDefault(), "%s • %d",
                            text, (millisUntilFinished / 1000) + 1));
                }

                @Override
                public void onFinish() {
                    textView.setText(text);
                    textView.setEnabled(true);
                }
            };
            deleteAccountTimer.start();
        });
        // Слушателя ставим через showDialog: BaseFragment.showDialog (:834) затирает
        // тот, что назначен диалогу напрямую.
        showDialog(dialog, d -> cancelDeleteAccountTimer());
    }

    private void cancelDeleteAccountTimer() {
        if (deleteAccountTimer != null) {
            deleteAccountTimer.cancel();
            deleteAccountTimer = null;
        }
    }

    private void deleteAccount() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog progressDialog = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.setCanCancel(false);
        progressDialog.show();
        // Пауза exteraGram перед запросом: спиннер успевает появиться, а не мигнуть.
        AndroidUtilities.runOnUIThread(() -> {
            TL_account.deleteAccount req = new TL_account.deleteAccount();
            req.reason = "openExtera";
            getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                try {
                    progressDialog.dismiss();
                } catch (Exception e) {
                    FileLog.e(e);
                }
                if (response instanceof TLRPC.TL_boolTrue) {
                    getMessagesController().performLogout(0);
                    return;
                }
                if (error != null && error.code == -1000) {
                    return;
                }
                if (getParentActivity() == null) {
                    return;
                }
                String message = getString(R.string.ErrorOccurred);
                if (error != null) {
                    message = message + "\n" + error.text;
                }
                AlertDialog.Builder alert = new AlertDialog.Builder(getParentActivity());
                alert.setTitle(getString(R.string.AppName));
                alert.setMessage(message);
                alert.setPositiveButton(getString(R.string.OK), null);
                showDialog(alert.create());
            }));
        }, 500);
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position, boolean partial) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    if (position == nagramHeaderRow) {
                        cell.setText(getString(R.string.OEGeneralNagramHeader));
                    } else if (position == experimentalHeaderRow) {
                        cell.setText(getString(R.string.Experimental));
                    } else if (position == googleHeaderRow) {
                        cell.setText(getString(R.string.OEGeneralGoogleHeader));
                    }
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    cell.setEnabled(true, null);
                    if (position == crashReportsRow) {
                        cell.setTextAndCheck(getString(R.string.OEGeneralCrashReports),
                                GeneralConfig.crashReports(), false);
                        cell.setIcon(R.drawable.msg_report);
                    } else if (position == nagramSettingsRow) {
                        cell.setTextAndCheck(getString(R.string.OEGeneralNagramSettings),
                                GeneralConfig.showNagramSettings(), true);
                        // setIcon после setTextAndCheck — тот сбрасывает отступы текста.
                        cell.setIcon(R.drawable.msg_settings);
                    } else {
                        cell.setIcon(0);
                        if (position == localPremiumRow) {
                            cell.setTextAndCheck(getString(R.string.localPremium),
                                    NekoConfig.localPremium.Bool(), true);
                        } else if (position == unlimitedPinnedDialogsRow) {
                            cell.setTextAndValueAndCheck(getString(R.string.UnlimitedPinnedDialogs),
                                    getString(R.string.UnlimitedPinnedDialogsAbout),
                                    NekoConfig.unlimitedPinnedDialogs.Bool(), true, true);
                        } else if (position == voiceEnhancementsRow) {
                            cell.setTextAndCheck(getString(R.string.NoiseSuppressAndVoiceEnhance),
                                    NaConfig.INSTANCE.getNoiseSuppressAndVoiceEnhance().Bool(), true);
                        } else if (position == enhancedVideoBitrateRow) {
                            cell.setTextAndCheck(getString(R.string.EnhancedVideoBitrate),
                                    NaConfig.INSTANCE.getEnhancedVideoBitrate().Bool(), true);
                        } else if (position == sensitiveContentRow) {
                            cell.setTextAndValueAndCheck(getString(R.string.SensitiveDisableFiltering),
                                    getString(R.string.SensitiveAbout), sensitiveEnabled, true, false);
                            cell.setEnabled(sensitiveCanChange, null);
                        }
                    }
                    break;
                }
                case TYPE_TEXT: {
                    TextCell cell = (TextCell) holder.itemView;
                    if (position == ayuMomentsRow) {
                        cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
                        cell.setTextAndIcon(getString(R.string.OEGeneralAyuMoments), R.drawable.ayu_ghost, false);
                    } else if (position == exportEtgRow) {
                        cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
                        cell.setTextAndIcon(getString(R.string.OEGeneralExportEtgSettings), R.drawable.msg_shareout, true);
                    } else if (position == importEtgRow) {
                        cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
                        cell.setTextAndIcon(getString(R.string.OEGeneralImportEtgSettings), R.drawable.msg_download, false);
                    } else if (position == glyphRow) {
                        cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
                        cell.setText(getString(R.string.OEGlyphTitle), false);
                    } else if (position == resetSettingsRow) {
                        cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
                        cell.setTextAndIcon(getString(R.string.OEGeneralResetSettings), R.drawable.msg_reset, true);
                    } else if (position == deleteAccountRow) {
                        cell.setColors(Theme.key_text_RedRegular, Theme.key_text_RedBold);
                        cell.setTextAndIcon(getString(R.string.OEGeneralDeleteAccount), R.drawable.msg_clearcache, false);
                    }
                    break;
                }
                case TYPE_INFO_PRIVACY: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    boolean bottom = position == bottomDividerRow;
                    if (position == googleDividerRow) {
                        cell.setText(getString(R.string.OEGeneralCrashReportsInfo));
                    } else if (position == glyphDividerRow) {
                        cell.setText(getString(R.string.OEGlyphInfo));
                    } else {
                        cell.setText(null);
                    }
                    cell.setBackground(Theme.getThemedDrawable(mContext,
                            bottom ? R.drawable.greydivider_bottom : R.drawable.greydivider,
                            Theme.key_windowBackgroundGrayShadow));
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == nagramHeaderRow || position == googleHeaderRow
                    || position == experimentalHeaderRow) {
                return TYPE_HEADER;
            } else if (position == nagramDividerRow || position == experimentalDividerRow
                    || position == etgDividerRow) {
                return TYPE_SHADOW;
            } else if (position == googleDividerRow || position == glyphDividerRow
                    || position == bottomDividerRow) {
                return TYPE_INFO_PRIVACY;
            } else if (position == exportEtgRow || position == importEtgRow
                    || position == resetSettingsRow || position == deleteAccountRow
                    || position == glyphRow || position == ayuMomentsRow) {
                return TYPE_TEXT;
            }
            return TYPE_CHECK;
        }
    }
}
