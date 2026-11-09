package app.exteraless.feed.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SelectAnimatedEmojiDialog;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;

import app.exteraless.feed.FeedConfig;
import app.exteraless.feed.FeedController;

public class FeedCategoryEditActivity extends BaseFragment {

    private static final int MENU_SEARCH = 0;
    private static final int MENU_OTHER = 1;
    private static final int MENU_SELECT_ALL = 2;
    private static final int MENU_DESELECT_ALL = 3;
    private static final int MENU_DELETE = 4;
    private static final int TITLE_MAX_LENGTH = 24;

    private final FeedConfig.Category initialCategory;
    private final boolean selectOnCreate;
    private final HashSet<Long> selectedChannels;
    private final ArrayList<TLRPC.Chat> channels = new ArrayList<>();

    private long emojiId;
    private boolean channelsLoaded;
    private String query;
    private boolean searching;
    private UniversalRecyclerView listView;
    private ActionBarMenuItem otherItem;
    private FrameLayout buttonContainer;
    private FrameLayout iconCell;
    private FrameLayout iconButton;
    private BackupImageView iconView;
    private EditTextCell titleCell;
    private SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow emojiDialog;

    public FeedCategoryEditActivity(int categoryId, boolean selectOnCreate) {
        this.selectOnCreate = selectOnCreate;
        initialCategory = FeedConfig.getInstance(currentAccount).getCategory(categoryId);
        emojiId = initialCategory != null ? initialCategory.getEmojiId() : 0;
        selectedChannels = initialCategory != null ? new HashSet<>(initialCategory.getChannels()) : new HashSet<>();
    }

    public static void showDeleteDialog(BaseFragment fragment, FeedConfig.Category category, Runnable onDeleted) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        int account = fragment.getCurrentAccount();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, fragment.getResourceProvider());
        builder.setTitle(getString(R.string.FeedCategoryDelete));
        builder.setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.FeedCategoryDeleteConfirm, category.getTitle())));
        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
            FeedConfig.getInstance(account).deleteCategory(category.getId());
            if (onDeleted != null) {
                onDeleted.run();
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        fragment.showDialog(dialog);
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold, fragment.getResourceProvider()));
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(getString(initialCategory != null ? R.string.FeedCategoryEdit : R.string.FeedCategoryNew));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (onBackPressed(true)) {
                        finishFragment();
                    }
                } else if (id == MENU_SELECT_ALL) {
                    setAllSelected(true);
                } else if (id == MENU_DESELECT_ALL) {
                    setAllSelected(false);
                } else if (id == MENU_DELETE && initialCategory != null) {
                    showDeleteDialog(FeedCategoryEditActivity.this, initialCategory, FeedCategoryEditActivity.this::finishFragment);
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_SEARCH, R.drawable.outline_header_search)
                .setIsSearchField(true)
                .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                    @Override
                    public void onSearchExpand() {
                        searching = true;
                        otherItem.setVisibility(View.GONE);
                        buttonContainer.setVisibility(View.GONE);
                    }

                    @Override
                    public void onSearchCollapse() {
                        searching = false;
                        query = null;
                        otherItem.setVisibility(View.VISIBLE);
                        buttonContainer.setVisibility(View.VISIBLE);
                        update();
                    }

                    @Override
                    public void onTextChanged(EditText editText) {
                        query = editText.getText().toString().trim().toLowerCase(Locale.ROOT);
                        update();
                    }
                })
                .setSearchFieldHint(getString(R.string.Search));
        otherItem = menu.addItem(MENU_OTHER, R.drawable.ic_ab_other);
        otherItem.setContentDescription(getString(R.string.AccDescrMoreOptions));
        otherItem.addSubItem(MENU_SELECT_ALL, R.drawable.msg_select, getString(R.string.SelectAll));
        otherItem.addSubItem(MENU_DESELECT_ALL, R.drawable.msg_cancel, getString(R.string.DeselectAll));
        if (initialCategory != null) {
            otherItem.addColoredGap();
            ActionBarMenuSubItem deleteItem = otherItem.addSubItem(MENU_DELETE, R.drawable.msg_delete, getString(R.string.FeedCategoryDelete));
            int red = getThemedColor(Theme.key_text_RedRegular);
            deleteItem.setColors(red, red);
            deleteItem.setSelectorColor(Theme.multAlpha(red, 0.12f));
        }

        createHeaderViews(context);

        FrameLayout contentView = new FrameLayout(context);
        int background = getThemedColor(Theme.key_windowBackgroundGray);
        contentView.setBackgroundColor(background);

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, (item, view, position, x, y) -> false);
        listView.setSections();
        listView.adapter.setApplyBackground(false);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, AndroidUtilities.dp(72));
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(listView);

        ButtonWithCounterView button = new ButtonWithCounterView(context, true, getResourceProvider());
        button.setRound();
        button.setText(getString(initialCategory != null ? R.string.Save : R.string.Create), false);
        button.setOnClickListener(v -> save());
        buttonContainer = new FrameLayout(context);
        buttonContainer.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{background, ColorUtils.setAlphaComponent(background, 0)}));
        buttonContainer.addView(button, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.BOTTOM, 16, 12, 16, 12));
        contentView.addView(buttonContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        fragmentView = contentView;
        loadChannels();
        return fragmentView;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        if (buttonContainer != null) {
            buttonContainer.setPadding(0, 0, 0, bottom);
        }
        if (listView != null) {
            listView.setPadding(0, 0, 0, bottom + AndroidUtilities.dp(72));
        }
    }

    private void createHeaderViews(Context context) {
        iconCell = new FrameLayout(context);
        iconCell.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
        iconView = new BackupImageView(context);
        iconButton = createIconCircle(context, iconView);
        iconButton.setOnClickListener(v -> openEmojiDialog());
        ScaleStateListAnimator.apply(iconButton);
        iconCell.addView(iconButton, LayoutHelper.createFrame(100, 100, Gravity.CENTER));
        bindIcon(iconView);

        titleCell = new EditTextCell(context, getString(R.string.FeedCategoryName), false, false, TITLE_MAX_LENGTH, getResourceProvider());
        titleCell.editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        titleCell.editText.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        titleCell.hideKeyboardOnEnter();
        titleCell.setText(initialCategory != null ? initialCategory.getTitle() : "");
    }

    private FrameLayout createIconCircle(Context context, BackupImageView imageView) {
        FrameLayout circle = new FrameLayout(context);
        circle.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(100), getThemedColor(Theme.key_windowBackgroundWhite)));
        circle.addView(imageView, LayoutHelper.createFrame(64, 64, Gravity.CENTER));
        return circle;
    }

    private void bindIcon(BackupImageView imageView) {
        if (emojiId == 0) {
            imageView.setAnimatedEmojiDrawable(null);
            imageView.setImageResource(R.drawable.menu_smile_add);
            imageView.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_dialogEmptyImage), PorterDuff.Mode.SRC_IN));
            return;
        }
        imageView.setColorFilter(null);
        imageView.setAnimatedEmojiDrawable(AnimatedEmojiDrawable.make(currentAccount, AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW, emojiId));
        imageView.setEmojiColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
    }

    private void openEmojiDialog() {
        if (emojiDialog != null || !iconButton.isAttachedToWindow()) {
            return;
        }
        AndroidUtilities.hideKeyboard(titleCell.editText);
        titleCell.editText.clearFocus();
        Context context = iconButton.getContext();
        SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow[] window = new SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow[1];
        BackupImageView previewIcon = new BackupImageView(context);
        FrameLayout previewCircle = createIconCircle(context, previewIcon);
        previewCircle.setOnClickListener(v -> {
            if (window[0] != null) {
                window[0].dismiss();
            }
        });
        bindIcon(previewIcon);
        SelectAnimatedEmojiDialog selector = new SelectAnimatedEmojiDialog(this, context, true, getResourceProvider()) {
            @Override
            public boolean isBottom() {
                return false;
            }

            @Override
            protected void onEmojiSelected(View view, Long documentId, TLRPC.Document document, TL_stars.TL_starGiftUnique gift, Integer until) {
                emojiId = documentId != null ? documentId : 0;
                bindIcon(iconView);
                bindIcon(previewIcon);
                if (window[0] != null) {
                    window[0].dismiss();
                }
            }
        };
        selector.setSelected(emojiId != 0 ? emojiId : null);
        FrameLayout layout = new FrameLayout(context);
        layout.addView(previewCircle, LayoutHelper.createFrame(100, 100, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        layout.addView(selector, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 108, 0, 0));
        window[0] = new SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow(layout, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT) {
            private boolean dismissing;

            @Override
            public void dismiss() {
                if (dismissing) {
                    return;
                }
                dismissing = true;
                emojiDialog = null;
                selector.onDismiss(this::dismissWindow);
            }

            private void dismissWindow() {
                super.dismiss();
            }
        };
        emojiDialog = window[0];
        int width = Math.min(AndroidUtilities.dp(324), (int) (AndroidUtilities.displaySize.x * 0.95f));
        int[] location = new int[2];
        iconButton.getLocationInWindow(location);
        window[0].showAtLocation(iconButton, Gravity.LEFT | Gravity.TOP, location[0] + (iconButton.getWidth() - width) / 2, location[1]);
        window[0].dimBehind();
        selector.onShow(() -> {
            if (window[0] != null) {
                window[0].dismiss();
            }
        });
    }

    private void loadChannels() {
        FeedController.getInstance(currentAccount).loadChannels((loaded, includedCount) -> {
            channels.clear();
            channels.addAll(loaded);
            HashSet<Long> members = initialCategory != null ? new HashSet<>(initialCategory.getChannels()) : new HashSet<>();
            channels.sort(Comparator.<TLRPC.Chat, Boolean>comparing(chat -> !members.contains(-chat.id))
                    .thenComparing(chat -> chat.title == null ? "" : chat.title.toLowerCase(Locale.ROOT)));
            channelsLoaded = true;
            update();
        });
    }

    private void update() {
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        FeedConfig config = FeedConfig.getInstance(currentAccount);
        boolean noQuery = TextUtils.isEmpty(query);
        if (noQuery) {
            items.add(UItem.asCustomShadow(iconCell));
            items.add(UItem.asCustom(titleCell));
            items.add(UItem.asShadow((CharSequence) null));
        }
        ArrayList<UItem> shown = new ArrayList<>();
        ArrayList<UItem> hidden = new ArrayList<>();
        for (TLRPC.Chat chat : channels) {
            if (!noQuery && (chat.title == null || !chat.title.toLowerCase(Locale.ROOT).contains(query))) {
                continue;
            }
            UItem item = UItem.asUserCheckbox(Long.hashCode(chat.id), chat).setChecked(selectedChannels.contains(-chat.id));
            (config.isHiddenFromAll(-chat.id) ? hidden : shown).add(item);
        }
        if (!shown.isEmpty()) {
            items.add(UItem.asHeader(getString(R.string.FeedChannels)));
            items.addAll(shown);
        }
        if (!hidden.isEmpty()) {
            if (!shown.isEmpty()) {
                items.add(UItem.asShadow((CharSequence) null));
            }
            items.add(UItem.asHeader(getString(R.string.FeedHiddenFromAll)));
            items.addAll(hidden);
        }
        if (noQuery && !(shown.isEmpty() && hidden.isEmpty())) {
            items.add(UItem.asShadow(getString(R.string.FeedCategoryChannelsInfo)));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (!(item.object instanceof TLRPC.Chat)) {
            return;
        }
        long dialogId = -((TLRPC.Chat) item.object).id;
        boolean checked = !item.checked;
        if (checked) {
            selectedChannels.add(dialogId);
        } else {
            selectedChannels.remove(dialogId);
        }
        item.setChecked(checked);
        View cell = listView.findViewByItemId(item.id);
        if (cell instanceof CheckBoxCell) {
            ((CheckBoxCell) cell).setChecked(checked, true);
        }
        update();
    }

    private void setAllSelected(boolean selected) {
        if (selected) {
            for (TLRPC.Chat chat : channels) {
                selectedChannels.add(-chat.id);
            }
        } else {
            selectedChannels.clear();
        }
        update();
    }

    private String currentTitle() {
        CharSequence text = titleCell == null ? null : titleCell.getText();
        return text == null ? "" : text.toString().trim();
    }

    private boolean hasChanges() {
        if (initialCategory == null) {
            return !currentTitle().isEmpty() || emojiId != 0 || !selectedChannels.isEmpty();
        }
        return !currentTitle().equals(initialCategory.getTitle()) || emojiId != initialCategory.getEmojiId()
                || !selectedChannels.equals(initialCategory.getChannels());
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (searching) {
            if (invoked) {
                actionBar.closeSearchField();
            }
            return false;
        }
        if (!hasChanges()) {
            return super.onBackPressed(invoked);
        }
        if (invoked) {
            showUnsavedDialog();
        }
        return false;
    }

    @Override
    public boolean isSwipeBackEnabled(MotionEvent event) {
        return !hasChanges() && super.isSwipeBackEnabled(event);
    }

    private void showUnsavedDialog() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(getString(R.string.UnsavedChanges));
        builder.setMessage(getString(R.string.FeedCategoryUnsavedChanges));
        builder.setPositiveButton(getString(R.string.Save), (dialog, which) -> save());
        builder.setNegativeButton(getString(R.string.PassportDiscard), (dialog, which) -> finishFragment());
        showDialog(builder.create());
    }

    private void save() {
        String title = currentTitle();
        if (title.isEmpty()) {
            AndroidUtilities.shakeViewSpring(titleCell);
            titleCell.editText.requestFocus();
            AndroidUtilities.showKeyboard(titleCell.editText);
            return;
        }
        HashSet<Long> members = new HashSet<>();
        if (channelsLoaded) {
            for (TLRPC.Chat chat : channels) {
                if (selectedChannels.contains(-chat.id)) {
                    members.add(-chat.id);
                }
            }
        } else {
            members.addAll(selectedChannels);
        }
        FeedConfig config = FeedConfig.getInstance(currentAccount);
        FeedConfig.Category saved = config.saveCategory(initialCategory != null ? initialCategory.getId() : 0, title, emojiId, members);
        if (initialCategory == null && selectOnCreate) {
            config.selectCategory(saved.getId());
        }
        finishFragment();
    }

    @Override
    public void onFragmentDestroy() {
        if (emojiDialog != null) {
            emojiDialog.dismiss();
        }
        super.onFragmentDestroy();
    }
}
