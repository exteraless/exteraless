package app.exteraless.feed.ui;

import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.StickerEmptyView;

import app.exteraless.feed.FeedConfig;
import app.exteraless.feed.FeedController;

public final class FeedEmptyState {

    private FeedEmptyState() {
    }

    public static void bind(BaseFragment fragment, StickerEmptyView view) {
        int account = fragment.getCurrentAccount();
        FeedController controller = FeedController.getInstance(account);
        FeedConfig config = FeedConfig.getInstance(account);
        view.subtitle.setVisibility(View.GONE);
        view.button.setVisibility(View.GONE);
        if (!controller.hasChannels() || controller.hasIncludedChannels()) {
            view.title.setText(LocaleController.getString(R.string.FeedNoPosts));
            return;
        }
        FeedConfig.Category category = config.getSelectedCategory();
        if (category != null) {
            view.title.setText(LocaleController.getString(R.string.FeedCategoryEmpty));
            view.subtitle.setText(LocaleController.getString(R.string.FeedCategoryEmptyInfo));
            view.subtitle.setVisibility(View.VISIBLE);
            setButton(view, LocaleController.getString(R.string.FeedCategoryAddChannels),
                    () -> fragment.presentFragment(new FeedCategoryEditActivity(category.getId(), false)));
            return;
        }
        view.title.setText(LocaleController.getString(R.string.FeedAllChannelsExcluded));
        if (!config.getVisibleCategories().isEmpty()) {
            view.subtitle.setText(LocaleController.getString(R.string.FeedAllChannelsExcludedInfo));
            view.subtitle.setVisibility(View.VISIBLE);
        }
        setButton(view, LocaleController.getString(R.string.FeedSettings),
                () -> fragment.presentFragment(new FeedChannelsActivity()));
    }

    private static void setButton(StickerEmptyView view, CharSequence text, Runnable action) {
        view.button.setText(text, false);
        view.button.setOnClickListener(v -> action.run());
        view.button.setVisibility(View.VISIBLE);
    }
}
