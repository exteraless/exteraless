package app.exteraless.speech;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;

import java.util.List;
import java.util.Locale;

import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;

public class VoskSettingsActivity extends BaseNekoSettingsActivity implements VoskManager.DownloadListener {

    private final List<VoskModel> models = VoskModels.all();

    private int languagesHeaderRow;
    private int languagesStartRow;
    private int languagesEndRow;
    private int languagesInfoRow;

    @Override
    public boolean onFragmentCreate() {
        VoskManager.addListener(this);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        VoskManager.removeListener(this);
        super.onFragmentDestroy();
    }

    @Override
    protected void updateRows() {
        super.updateRows();
        languagesHeaderRow = addRow("voskLanguagesHeader");
        languagesStartRow = rowCount;
        rowCount += models.size();
        languagesEndRow = rowCount;
        languagesInfoRow = addRow();
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.VoskTitle);
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position < languagesStartRow || position >= languagesEndRow) {
            return;
        }
        VoskModel model = models.get(position - languagesStartRow);
        if (model.code.equals(VoskManager.getDownloadingCode())) {
            VoskManager.cancelDownload();
            return;
        }
        if (VoskManager.isInstalled(model.code)) {
            VoskManager.setLanguage(model.code);
            if (listAdapter != null) {
                listAdapter.notifyItemRangeChanged(languagesStartRow, models.size());
            }
            return;
        }
        if (VoskManager.getDownloadingCode() != null) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.VoskDownloadBusy)).show();
            return;
        }
        VoskManager.download(model);
        if (listAdapter != null) {
            listAdapter.notifyItemChanged(position);
        }
    }

    @Override
    protected boolean onItemLongClick(View view, int position, float x, float y) {
        if (position < languagesStartRow || position >= languagesEndRow) {
            return false;
        }
        VoskModel model = models.get(position - languagesStartRow);
        if (!VoskManager.isInstalled(model.code) || getParentActivity() == null) {
            return false;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(model.language);
        builder.setMessage(getString(R.string.VoskDeleteModelConfirm));
        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
            VoskManager.delete(model.code);
            if (listAdapter != null) {
                listAdapter.notifyItemRangeChanged(languagesStartRow, models.size());
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button != null) {
            ((android.widget.TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
        return true;
    }

    @Override
    public void onProgress(long done, long total) {
        String code = VoskManager.getDownloadingCode();
        if (code == null || listAdapter == null) {
            return;
        }
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).code.equals(code)) {
                listAdapter.notifyItemChanged(languagesStartRow + i, PARTIAL);
                break;
            }
        }
    }

    @Override
    public void onFinished(VoskModel model, Throwable error) {
        if (error != null && !(error instanceof InterruptedException)) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.VoskDownloadFailed)).show();
        } else if (error == null) {
            VoskManager.setLanguage(model.code);
        }
        if (listAdapter != null) {
            listAdapter.notifyItemRangeChanged(languagesStartRow, models.size());
        }
    }

    private CharSequence stateOf(VoskModel model) {
        if (model.code.equals(VoskManager.getDownloadingCode())) {
            long total = VoskManager.getTotalBytes();
            int percent = total > 0 ? (int) (VoskManager.getDownloadedBytes() * 100 / total) : 0;
            return String.format(Locale.US, "%d%%", percent);
        }
        if (VoskManager.isInstalled(model.code)) {
            return model.code.equals(VoskManager.getLanguage())
                    ? getString(R.string.VoskModelSelected)
                    : AndroidUtilities.formatFileSize(VoskManager.installedSize(model.code));
        }
        return AndroidUtilities.formatFileSize(model.size);
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public int getItemViewType(int position) {
            if (position == languagesHeaderRow) {
                return TYPE_HEADER;
            }
            if (position == languagesInfoRow) {
                return TYPE_INFO_PRIVACY;
            }
            return TYPE_SETTINGS;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER: {
                    ((HeaderCell) holder.itemView).setText(getString(R.string.VoskLanguages));
                    break;
                }
                case TYPE_INFO_PRIVACY: {
                    ((TextInfoPrivacyCell) holder.itemView).setText(getString(R.string.VoskLanguagesInfo));
                    break;
                }
                case TYPE_SETTINGS: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    int index = position - languagesStartRow;
                    if (index < 0 || index >= models.size()) {
                        break;
                    }
                    VoskModel model = models.get(index);
                    cell.setTextAndValue(model.language, stateOf(model), partial, position != languagesEndRow - 1);
                    boolean selected = VoskManager.isInstalled(model.code)
                            && model.code.equals(VoskManager.getLanguage());
                    cell.setTextValueColor(Theme.getColor(selected
                            ? Theme.key_windowBackgroundWhiteBlueText4
                            : Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
                    break;
                }
            }
        }
    }
}
