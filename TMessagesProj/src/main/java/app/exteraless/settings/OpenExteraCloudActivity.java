package app.exteraless.settings;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.LaunchActivity;

import tw.nekomimi.nekogram.helpers.AppRestartHelper;
import tw.nekomimi.nekogram.helpers.CloudSettingsHelper;
import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;

public class OpenExteraCloudActivity extends BaseNekoSettingsActivity {

    private static final int TYPE_HERO = 100;

    private static final int IDLE = 0;
    private static final int UPLOADING = 1;
    private static final int RESTORING = 2;
    private static final int CLEARING = 3;

    private int heroRow;
    private int statusHeaderRow;
    private int localDateRow;
    private int cloudDateRow;
    private int statusShadowRow;
    private int actionsHeaderRow;
    private int uploadRow;
    private int restoreRow;
    private int clearRow;
    private int actionsShadowRow;
    private int optionsHeaderRow;
    private int autoSyncRow;
    private int apiKeysRow;
    private int bottomShadowRow;

    private int state = IDLE;
    private boolean cloudDateLoaded;

    @Override
    public boolean onFragmentCreate() {
        refreshCloudDate();
        return super.onFragmentCreate();
    }

    @Override
    protected void updateRows() {
        super.updateRows();
        heroRow = addRow();
        statusHeaderRow = addRow("cloudStatus");
        localDateRow = addRow();
        cloudDateRow = addRow();
        statusShadowRow = addRow();
        actionsHeaderRow = addRow("cloudActions");
        uploadRow = addRow("cloudUpload");
        restoreRow = addRow("cloudRestore");
        clearRow = addRow("cloudClear");
        actionsShadowRow = addRow();
        optionsHeaderRow = addRow("cloudOptions");
        autoSyncRow = addRow("cloudAutoSync");
        apiKeysRow = addRow("cloudApiKeys");
        bottomShadowRow = addRow();
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.OECloudTitle);
    }

    @Override
    public int getSearchGuid() {
        return 28000;
    }

    @Override
    public int getSearchIcon() {
        return R.drawable.cloud_sync;
    }

    @Override
    public String getSearchPrefix() {
        return "OECloud";
    }

    @Override
    protected String getKey() {
        return "exteraless_cloud";
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    private CloudSettingsHelper helper() {
        return CloudSettingsHelper.getInstance();
    }

    private boolean hasCloudCopy() {
        return helper().getCloudSyncedDate() > 0;
    }

    private void refreshCloudDate() {
        helper().fetchCloudSyncedDate(date -> AndroidUtilities.runOnUIThread(() -> {
            cloudDateLoaded = true;
            notifyRows(cloudDateRow, restoreRow, clearRow);
        }));
    }

    private void notifyRows(int... rows) {
        if (listAdapter == null) {
            return;
        }
        for (int row : rows) {
            if (row >= 0) {
                listAdapter.notifyItemChanged(row);
            }
        }
    }

    private void setState(int newState) {
        state = newState;
        notifyRows(uploadRow, restoreRow, clearRow, localDateRow, cloudDateRow);
    }

    private void showError(int title, String error) {
        if (error == null) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(title)).show();
        } else {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(title), error).show();
        }
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == autoSyncRow) {
            boolean value = !helper().isAutoSync();
            helper().setAutoSync(value);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(value);
            }
            return;
        }
        if (position == apiKeysRow) {
            boolean value = !helper().isIncludeApiKeys();
            helper().setIncludeApiKeys(value);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(value);
            }
            return;
        }
        if (state != IDLE) {
            return;
        }
        if (position == uploadRow) {
            upload();
        } else if (position == restoreRow && hasCloudCopy()) {
            confirmRestore();
        } else if (position == clearRow && hasCloudCopy()) {
            confirmClear();
        }
    }

    private void upload() {
        setState(UPLOADING);
        helper().sync((success, error) -> {
            setState(IDLE);
            if (success) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.done, getString(R.string.OECloudUploaded)).show();
            } else {
                showError(R.string.CloudConfigSyncFailed, error);
            }
        });
    }

    private void confirmRestore() {
        if (getParentActivity() == null) {
            return;
        }
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(getString(R.string.OECloudRestore))
                .setMessage(getString(R.string.OECloudRestoreConfirm))
                .setPositiveButton(getString(R.string.OECloudRestoreAction), (dialog, which) -> restore())
                .setNegativeButton(getString(R.string.Cancel), null)
                .create());
    }

    private void restore() {
        setState(RESTORING);
        helper().restore((success, error) -> {
            setState(IDLE);
            if (!success) {
                showError(R.string.CloudConfigRestoreFailed, error);
                return;
            }
            Context context = getParentActivity();
            if (context != null) {
                AppRestartHelper.triggerRebirth(context, new Intent(context, LaunchActivity.class));
            }
        });
    }

    private void confirmClear() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(getString(R.string.OECloudClear))
                .setMessage(getString(R.string.OECloudClearConfirm))
                .setPositiveButton(getString(R.string.OECloudClearAction), (d, which) -> clear())
                .setNegativeButton(getString(R.string.Cancel), null)
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .create();
        showDialog(dialog);
    }

    private void clear() {
        setState(CLEARING);
        helper().delete((success, error) -> {
            setState(IDLE);
            if (success) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.done, getString(R.string.DeleteCloudBackupSuccess)).show();
            } else if (error == null) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.CloudConfigNoBackupToDelete)).show();
            } else {
                showError(R.string.DeleteCloudBackupFailed, error);
            }
        });
    }

    private View createHero(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(dp(32), dp(20), dp(32), dp(16));

        FrameLayout badge = new FrameLayout(context);
        badge.setBackground(Theme.createCircleDrawable(dp(84), getThemedColor(Theme.key_featuredStickers_addButton)));
        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setImageResource(R.drawable.cloud_sync);
        icon.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_featuredStickers_buttonText), PorterDuff.Mode.SRC_IN));
        badge.addView(icon, LayoutHelper.createFrame(44, 44, Gravity.CENTER));
        layout.addView(badge, LayoutHelper.createLinear(84, 84, Gravity.CENTER_HORIZONTAL));

        TextView title = new TextView(context);
        title.setText(getString(R.string.OECloudHeroTitle));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));

        TextView subtitle = new TextView(context);
        subtitle.setText(getString(R.string.OECloudHeroText));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLineSpacing(dp(2), 1f);
        subtitle.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        layout.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));
        return layout;
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_HERO) {
                View view = createHero(mContext);
                view.setLayoutParams(new RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
                return new RecyclerListView.Holder(view);
            }
            return super.onCreateViewHolder(parent, viewType);
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            if (position == localDateRow || position == cloudDateRow) {
                return false;
            }
            return super.isEnabled(holder);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position, boolean partial) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    if (position == statusHeaderRow) {
                        cell.setText(getString(R.string.OECloudStatus));
                    } else if (position == actionsHeaderRow) {
                        cell.setText(getString(R.string.OECloudActions));
                    } else if (position == optionsHeaderRow) {
                        cell.setText(getString(R.string.OECloudOptions));
                    }
                    break;
                }
                case TYPE_SETTINGS: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                    if (position == localDateRow) {
                        cell.setTextAndValue(getString(R.string.OECloudLocal),
                                CloudSettingsHelper.formatSyncDate(helper().getLocalSyncedDate()), true);
                    } else if (position == cloudDateRow) {
                        String value;
                        if (state == UPLOADING || state == CLEARING || !cloudDateLoaded) {
                            value = getString(R.string.OECloudChecking);
                        } else {
                            value = CloudSettingsHelper.formatSyncDate(helper().getCloudSyncedDate());
                        }
                        cell.setTextAndValue(getString(R.string.OECloudRemote), value, false);
                    }
                    break;
                }
                case TYPE_TEXT: {
                    TextCell cell = (TextCell) holder.itemView;
                    boolean hasCloud = hasCloudCopy();
                    if (position == uploadRow) {
                        cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueText4);
                        cell.setTextAndIcon(getString(state == UPLOADING ? R.string.OECloudUploading : R.string.OECloudUpload),
                                R.drawable.cloud_sync, true);
                        cell.setAlpha(state == IDLE || state == UPLOADING ? 1f : 0.5f);
                    } else if (position == restoreRow) {
                        cell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
                        cell.setTextAndIcon(getString(state == RESTORING ? R.string.OECloudRestoring : R.string.OECloudRestore),
                                R.drawable.msg_download, true);
                        cell.setAlpha(state == RESTORING || state == IDLE && hasCloud ? 1f : 0.5f);
                    } else if (position == clearRow) {
                        cell.setColors(Theme.key_text_RedRegular, Theme.key_text_RedRegular);
                        cell.setTextAndIcon(getString(state == CLEARING ? R.string.OECloudClearing : R.string.OECloudClear),
                                R.drawable.msg_delete, false);
                        cell.setAlpha(state == CLEARING || state == IDLE && hasCloud ? 1f : 0.5f);
                    }
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == autoSyncRow) {
                        cell.setTextAndValueAndCheck(getString(R.string.OECloudAutoSync),
                                getString(R.string.OECloudAutoSyncInfo), helper().isAutoSync(), true, true);
                    } else if (position == apiKeysRow) {
                        cell.setTextAndValueAndCheck(getString(R.string.CloudConfigIncludeApiKeys),
                                getString(R.string.CloudConfigIncludeApiKeysDesc), helper().isIncludeApiKeys(), true, false);
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == heroRow) {
                return TYPE_HERO;
            } else if (position == statusHeaderRow || position == actionsHeaderRow || position == optionsHeaderRow) {
                return TYPE_HEADER;
            } else if (position == localDateRow || position == cloudDateRow) {
                return TYPE_SETTINGS;
            } else if (position == statusShadowRow || position == actionsShadowRow || position == bottomShadowRow) {
                return TYPE_SHADOW;
            } else if (position == uploadRow || position == restoreRow || position == clearRow) {
                return TYPE_TEXT;
            }
            return TYPE_CHECK;
        }
    }
}
