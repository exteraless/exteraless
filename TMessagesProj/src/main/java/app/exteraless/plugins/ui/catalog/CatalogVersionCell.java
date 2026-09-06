package app.exteraless.plugins.ui.catalog;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.SharedDocumentCell;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import app.exteraless.plugins.catalog.CatalogPlugin;
import app.exteraless.plugins.catalog.CatalogVersion;

final class CatalogVersionCell {

    static final class Model {
        final CatalogPlugin plugin;
        final CatalogVersion version;
        final String fileName;
        final String details;
        final String contentDescription;

        Model(CatalogPlugin plugin, CatalogVersion version, String fileName, String details,
              String contentDescription) {
            this.plugin = plugin;
            this.version = version;
            this.fileName = fileName;
            this.details = details;
            this.contentDescription = contentDescription;
        }
    }

    static final class Factory extends UItem.UItemFactory<SharedDocumentCell> {
        static { setup(new Factory()); }

        @Override
        public SharedDocumentCell createView(Context context, RecyclerListView listView,
                                              int currentAccount, int classGuid,
                                              Theme.ResourcesProvider resourcesProvider) {
            SharedDocumentCell cell = new SharedDocumentCell(context,
                    SharedDocumentCell.VIEW_TYPE_DEFAULT, resourcesProvider);
            cell.setDrawDownloadIcon(false);
            return cell;
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter,
                             UniversalRecyclerView listView) {
            Model model = (Model) item.object;
            ((SharedDocumentCell) view).setTextAndValueAndTypeAndThumb(model.fileName,
                    model.details, "plugin", null, 0, divider);
            view.setContentDescription(model.contentDescription);
        }

        @Override
        public boolean equals(UItem first, UItem second) {
            Model a = first.object instanceof Model ? (Model) first.object : null;
            Model b = second.object instanceof Model ? (Model) second.object : null;
            return a != null && b != null
                    && a.version.id == b.version.id
                    && TextUtils.equals(a.plugin.slug, b.plugin.slug);
        }

        @Override
        public boolean contentsEquals(UItem first, UItem second) {
            Model a = first.object instanceof Model ? (Model) first.object : null;
            Model b = second.object instanceof Model ? (Model) second.object : null;
            return a != null && b != null
                    && equals(first, second)
                    && TextUtils.equals(a.fileName, b.fileName)
                    && TextUtils.equals(a.details, b.details)
                    && TextUtils.equals(a.contentDescription, b.contentDescription);
        }

        static UItem asVersion(int id, Model model) {
            UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.object = model;
            return item;
        }
    }
}
