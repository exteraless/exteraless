package app.exteraless.plugins.ui.catalog;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.ImageLocation;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import app.exteraless.plugins.catalog.CatalogPlugin;

final class CatalogAuthorCell {

    static final class Model {
        final CatalogPlugin plugin;
        final CharSequence name;
        final CharSequence status;
        final String avatarUrl;

        Model(CatalogPlugin plugin, CharSequence name, CharSequence status,
              String avatarUrl) {
            this.plugin = plugin;
            this.name = name;
            this.status = status;
            this.avatarUrl = avatarUrl;
        }
    }

    static final class Factory extends UItem.UItemFactory<UserCell> {
        static { setup(new Factory()); }

        @Override
        public UserCell createView(Context context, RecyclerListView listView,
                                   int currentAccount, int classGuid,
                                   Theme.ResourcesProvider resourcesProvider) {
            return new UserCell(context, 6, 0, false, false, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter,
                             UniversalRecyclerView listView) {
            UserCell cell = (UserCell) view;
            Model model = (Model) item.object;
            cell.setData(null, model.name, model.status, 0, divider);
            cell.setStoriable(false);
            if (!TextUtils.isEmpty(model.avatarUrl)) {
                AvatarDrawable placeholder = new AvatarDrawable();
                placeholder.setInfo(model.plugin.id ^ 0x5f3759df,
                        model.name == null ? "" : model.name.toString(), null);
                cell.avatarImageView.setImage(ImageLocation.getForPath(model.avatarUrl),
                        "108_108", placeholder, model.plugin);
            }
        }

        @Override
        public boolean equals(UItem first, UItem second) {
            return first.id == second.id;
        }

        @Override
        public boolean contentsEquals(UItem first, UItem second) {
            Model a = first.object instanceof Model ? (Model) first.object : null;
            Model b = second.object instanceof Model ? (Model) second.object : null;
            return a != null && b != null
                    && TextUtils.equals(a.name, b.name)
                    && TextUtils.equals(a.status, b.status)
                    && TextUtils.equals(a.avatarUrl, b.avatarUrl);
        }

        static UItem asAuthor(int id, Model model) {
            UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.object = model;
            return item;
        }
    }

    private CatalogAuthorCell() {
    }
}
