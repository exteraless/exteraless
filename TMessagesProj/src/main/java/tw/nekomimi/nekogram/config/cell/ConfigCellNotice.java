package tw.nekomimi.nekogram.config.cell;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;

import tw.nekomimi.nekogram.config.CellGroup;

public class ConfigCellNotice extends AbstractConfigCell {

    private final CharSequence text;

    public ConfigCellNotice(CharSequence text) {
        this.text = text;
    }

    public int getType() {
        return CellGroup.ITEM_TYPE_TEXT;
    }

    public boolean isEnabled() {
        return false;
    }

    public void onBindViewHolder(RecyclerView.ViewHolder holder) {
        TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
        cell.setText(text);
        cell.setBackground(Theme.getThemedDrawable(cell.getContext(), R.drawable.greydivider,
                Theme.key_windowBackgroundGrayShadow));
    }
}
