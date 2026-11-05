package tw.nekomimi.nekogram.folder;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.SelectAnimatedEmojiDialog;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ExtendedGridLayoutManager;
import org.telegram.ui.Components.RecyclerListView;

import app.exteraless.appearance.LocalPremiumFolderIcons;

public class IconSelectorAlert {

    public static void show(BaseFragment fragment, OnIconSelectedListener listener) {
        show(fragment, 0, listener, null);
    }

    public static void show(BaseFragment fragment, long selectedDocumentId, OnIconSelectedListener onIconSelectedListener, Utilities.Callback<Long> onCustomIcon) {
        Context context = fragment.getParentActivity();

        AlertDialog.Builder builder = new AlertDialog.Builder(context);

        GridAdapter gridAdapter = new GridAdapter();
        RecyclerListView recyclerListView = new RecyclerListView(context);
        recyclerListView.setClipToPadding(false);
        recyclerListView.setPadding(AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8), 0);
        recyclerListView.setLayoutManager(new ExtendedGridLayoutManager(recyclerListView.getContext(), 6));
        recyclerListView.setAdapter(gridAdapter);
        recyclerListView.setSelectorDrawableColor(0);
        recyclerListView.setOnItemClickListener((view, position) -> {
            onIconSelectedListener.onIconSelected((String) view.getTag());
            builder.getDismissRunnable().run();
        });

        if (onCustomIcon != null && LocalPremiumFolderIcons.enabledFor(fragment.getCurrentAccount())) {
            builder.setPositiveButton(LocaleController.getString(R.string.OELocalFolderIconChoose),
                    (dialog, which) -> showEmojiPicker(fragment, selectedDocumentId, onCustomIcon));
        }
        builder.setView(recyclerListView);
        fragment.showDialog(builder.create());
    }

    private static void showEmojiPicker(BaseFragment fragment, long selectedDocumentId, Utilities.Callback<Long> onCustomIcon) {
        if (!LocalPremiumFolderIcons.enabledFor(fragment.getCurrentAccount())) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getContext(), fragment.getResourceProvider());
        SelectAnimatedEmojiDialog picker = new SelectAnimatedEmojiDialog(fragment, fragment.getContext(), false, null,
                SelectAnimatedEmojiDialog.TYPE_FOLDER_ICON, false, fragment.getResourceProvider(), 0) {
            @Override
            protected void onEmojiSelected(View view, Long documentId, TLRPC.Document document, TL_stars.TL_starGiftUnique gift, Integer until) {
                if (documentId == null || documentId == 0) return;
                if (LocalPremiumFolderIcons.enabledFor(fragment.getCurrentAccount())) {
                    onCustomIcon.run(documentId);
                }
                builder.getDismissRunnable().run();
            }
        };
        picker.setDrawBackground(false);
        picker.setSelected(selectedDocumentId);
        builder.setTitle(LocaleController.getString(R.string.OELocalFolderIconChoose));
        builder.setView(picker, Math.min(360, (int) (AndroidUtilities.displaySize.y / AndroidUtilities.density / 2)));
        fragment.showDialog(builder.create());
        picker.post(() -> picker.onShow(builder.getDismissRunnable()));
    }

    private static class GridAdapter extends RecyclerListView.SelectionAdapter {
        private final String[] icons = FolderIconHelper.folderIcons.keySet().toArray(new String[0]);

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            var view = new AppCompatImageView(parent.getContext()) {
                @Override
                public void setImageResource(int resId) {
                    if (resId == 0) {
                        setImageDrawable(null);
                        return;
                    }
                    try {
                        setImageDrawable(getContext().getResources().getDrawable(resId, getContext().getTheme()));
                    } catch (Throwable t) {
                        super.setImageResource(resId);
                    }
                }

                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    int iconSize = MeasureSpec.makeMeasureSpec(parent.getMeasuredWidth() / 6, MeasureSpec.EXACTLY);
                    super.onMeasure(iconSize, iconSize);
                }
            };
            view.setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector), AndroidUtilities.dp(2), AndroidUtilities.dp(2)));
            view.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
            view.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(10), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            var imageView = (ImageView) holder.itemView;
            imageView.setTag(icons[position]);
            imageView.setImageResource(FolderIconHelper.getTabIcon(icons[position]));
        }

        @Override
        public int getItemCount() {
            return icons.length;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }
    }

    public interface OnIconSelectedListener {
        void onIconSelected(String emoticon);
    }
}
