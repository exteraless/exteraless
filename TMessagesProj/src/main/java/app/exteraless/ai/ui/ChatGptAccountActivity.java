package app.exteraless.ai.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.HashSet;

import app.exteraless.ai.chatgpt.ChatGptAuth;

public final class ChatGptAccountActivity extends BaseFragment {
    private static final int ADD_ACCOUNT = 1;
    private static final int SELECT_ACCOUNT = 2;
    private static final int RECONNECT = 3;
    private static final int CHECK_SESSION = 4;
    private static final int REMOVE_ACCOUNT = 5;
    private static final int[] COLOR_KEYS = {
            Theme.key_windowBackgroundGray, Theme.key_windowBackgroundWhite,
            Theme.key_windowBackgroundWhiteBlackText, Theme.key_windowBackgroundWhiteGrayText,
            Theme.key_featuredStickers_addButton, Theme.key_featuredStickers_buttonText,
            Theme.key_text_RedRegular, Theme.key_listSelector
    };

    private ChatGptAuth auth;
    private ChatGptAuth.Snapshot state;
    private ScrollView scrollView;
    private View card;
    private TextView statusView;
    private TextView emailView;
    private Button actionButton;
    private Button usageButton;
    private ActionBarMenuItem moreItem;
    private final Runnable changed = () -> {
        state = auth.snapshot();
        updateState();
    };

    @Override
    public boolean onFragmentCreate() {
        auth = ChatGptAuth.getInstance();
        state = auth.snapshot();
        auth.addListener(changed);
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(getString(R.string.OEChatGptAccount));
        actionBar.setCastShadows(false);
        if (AndroidUtilities.isTablet()) actionBar.setOccupyStatusBar(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
                else onMenuClick(id);
            }
        });
        moreItem = actionBar.createMenu().addItem(0, R.drawable.ic_ab_other);
        moreItem.setContentDescription(getString(R.string.AccDescrMoreOptions));
        moreItem.addSubItem(ADD_ACCOUNT, R.drawable.msg_add, getString(R.string.OEChatGptAddAccount));
        moreItem.addSubItem(SELECT_ACCOUNT, R.drawable.msg_contacts, getString(R.string.SelectAccount));
        moreItem.addSubItem(RECONNECT, R.drawable.msg_retry, getString(R.string.OEChatGptReconnect));
        moreItem.addSubItem(CHECK_SESSION, R.drawable.msg_log, getString(R.string.OEChatGptCheckSession));
        moreItem.addSubItem(REMOVE_ACCOUNT, R.drawable.msg_leave, getString(R.string.LogOut));

        fragmentView = scrollView = (ScrollView) LayoutInflater.from(context).inflate(R.layout.chatgpt_account, null, false);
        card = scrollView.findViewById(R.id.chatgpt_account_card);
        statusView = scrollView.findViewById(R.id.chatgpt_account_status);
        emailView = scrollView.findViewById(R.id.chatgpt_account_email);
        actionButton = scrollView.findViewById(R.id.chatgpt_account_action);
        actionButton.setTypeface(AndroidUtilities.bold());
        actionButton.setOnClickListener(v -> onAccountAction());
        usageButton = scrollView.findViewById(R.id.chatgpt_manage_usage);
        usageButton.setText(getString(R.string.OEChatGptManageUsage));
        usageButton.setContentDescription(getString(R.string.OEChatGptManageUsageDescription));
        usageButton.setOnClickListener(v -> {
            if (auth.isSignedIn() && getParentActivity() != null) {
                Browser.openInExternalBrowser(getParentActivity(), ChatGptAuth.USAGE_URL, false);
            }
        });
        changed.run();
        return fragmentView;
    }

    private void updateState() {
        if (actionButton == null || state == null) return;
        ChatGptAuth.Account active = state.activeAccount();
        boolean connected = active != null && active.signedIn && !state.storageFailed;
        String status = getString(connected ? R.string.Connected : R.string.OEChatGptNotConnected);
        if (connected && !active.planEnabled) status += "\n" + getString(R.string.OEChatGptPlanRequired);
        if (state.error != 0) status = getString(state.error);
        else if (state.busy) status = getString(state.signingIn ? R.string.OEChatGptWaiting : R.string.OEChatGptUpdating);
        if (!status.contentEquals(statusView.getText())) statusView.setText(status);
        emailView.setText(connected ? active.email : "");
        emailView.setVisibility(connected && !active.email.isEmpty() ? View.VISIBLE : View.GONE);

        int action = state.signingIn ? R.string.Cancel : state.storageFailed ? R.string.OEChatGptReset
                : connected ? R.string.OEChatGptSignOut : R.string.OEChatGptSignIn;
        actionButton.setText(getString(action));
        actionButton.setContentDescription(getString(action));
        actionButton.setEnabled(state.signingIn || !state.busy);
        usageButton.setEnabled(connected && !state.busy);
        moreItem.setVisibility(!state.busy && !state.storageFailed && !state.accounts.isEmpty() ? View.VISIBLE : View.GONE);
        moreItem.setSubItemShown(SELECT_ACCOUNT, state.accounts.size() > 1 || active == null);
        moreItem.setSubItemShown(RECONNECT, connected);
        moreItem.setSubItemShown(CHECK_SESSION, connected);
        moreItem.setSubItemShown(REMOVE_ACCOUNT, active != null && !active.signedIn);
        applyTheme();
    }

    private void onAccountAction() {
        if (state == null || getParentActivity() == null) return;
        if (state.signingIn) {
            auth.cancelSignIn();
        } else if (!state.busy) {
            if (state.storageFailed) {
                showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                        .setTitle(getString(R.string.OEChatGptReset))
                        .setMessage(getString(R.string.OEChatGptResetInfo))
                        .setPositiveButton(getString(R.string.Reset), (dialog, which) -> auth.resetUnreadableStorage())
                        .setNegativeButton(getString(R.string.Cancel), null).create());
                return;
            }
            ChatGptAuth.Account active = state.activeAccount();
            if (active != null && active.signedIn) confirmSignOut(active.clientId);
            else auth.signIn(getParentActivity(), active == null ? null : active.clientId);
        }
    }

    private void confirmSignOut(String clientId) {
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(getString(R.string.OEChatGptSignOut))
                .setMessage(getString(R.string.OEChatGptSignOutInfo))
                .setPositiveButton(getString(R.string.OEChatGptSignOut), (dialog, which) -> auth.signOut(clientId))
                .setNegativeButton(getString(R.string.Cancel), null).create());
    }

    private void onMenuClick(int id) {
        if (state == null || state.busy || state.storageFailed || getParentActivity() == null) return;
        ChatGptAuth.Account active = state.activeAccount();
        if (id == ADD_ACCOUNT) auth.signIn(getParentActivity(), null);
        else if (id == RECONNECT && active != null) auth.signIn(getParentActivity(), active.clientId);
        else if (id == CHECK_SESSION) auth.refreshSession();
        else if (id == REMOVE_ACCOUNT && active != null) confirmSignOut(active.clientId);
        else if (id == SELECT_ACCOUNT) {
            ArrayList<ChatGptAuth.Account> accounts = new ArrayList<>(state.accounts);
            CharSequence[] labels = new CharSequence[accounts.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = accounts.get(i).label;
            showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                    .setTitle(getString(R.string.SelectAccount))
                    .setItems(labels, (dialog, which) -> {
                        ChatGptAuth.Account selected = accounts.get(which);
                        if (selected.signedIn) auth.selectAccount(selected.clientId);
                        else auth.signIn(getParentActivity(), selected.clientId);
                    }).create());
        }
    }

    private void applyTheme() {
        if (card == null || state == null) return;
        scrollView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        card.setBackground(Theme.createRoundRectDrawable(dp(12), getThemedColor(Theme.key_windowBackgroundWhite)));
        int text = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
        int secondary = getThemedColor(Theme.key_windowBackgroundWhiteGrayText);
        statusView.setTextColor(getThemedColor(state.error != 0 ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteGrayText));
        emailView.setTextColor(secondary);
        int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        ChatGptAuth.Account active = state.activeAccount();
        int primary = active != null && active.signedIn && !state.signingIn
                ? getThemedColor(Theme.key_text_RedRegular) : accent;
        actionButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(8), primary, Theme.multAlpha(primary, .8f)));
        actionButton.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
        usageButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(8), Theme.multAlpha(accent, .12f), Theme.multAlpha(accent, .2f)));
        usageButton.setTextColor(accent);
        actionButton.setAlpha(actionButton.isEnabled() ? 1f : .45f);
        usageButton.setAlpha(usageButton.isEnabled() ? 1f : .45f);
        actionBar.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        actionBar.setTitleColor(text);
        actionBar.setItemsColor(text, false);
        actionBar.setItemsBackgroundColor(getThemedColor(Theme.key_listSelector), false);
    }

    @Override
    public void onThemeColorsChanged() {
        super.onThemeColorsChanged();
        applyTheme();
    }

    @Override
    public void addThemeColorKeys(HashSet<Integer> keys) {
        super.addThemeColorKeys(keys);
        for (int key : COLOR_KEYS) keys.add(key);
    }

    @Override
    public boolean isLightStatusBar() {
        return ColorUtils.calculateLuminance(getThemedColor(Theme.key_windowBackgroundWhite)) > .7f;
    }

    @Override
    public boolean isSupportEdgeToEdge() { return true; }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        if (scrollView != null) scrollView.setPadding(left, 0, right, bottom);
    }

    @Override
    public void onResume() {
        super.onResume();
        changed.run();
    }

    @Override
    public void clearViews() {
        super.clearViews();
        scrollView = null;
        card = null;
        statusView = emailView = null;
        actionButton = usageButton = null;
        moreItem = null;
    }

    @Override
    public void onFragmentDestroy() {
        auth.removeListener(changed);
        auth.cancelSignIn();
        super.onFragmentDestroy();
    }
}
