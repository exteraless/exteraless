# Bot Privacy Account

Settings → exteraless → General → Bot Privacy Account. The feature is off by
default. Enabling it opens the native account picker. Both the picker and setting
show names and public usernames. The global preference stores the Telegram user ID,
not the reusable account slot. GeneralConfig supplies persistence, typed backup
and reset; SettingsHelper supplies settings search. English and Russian are included.

## Always ask

The **Always ask** switch uses the same privacy routing feature without requiring a
preselected account. For each new private bot conversation, the native picker asks
which currently logged-in, visible account to use. Cancelling does not open/start
the bot. Existing conversations still retain their original accounts; generic
links with several established conversations still offer only those accounts.

The two switches are mutually exclusive: turning Always ask on turns Use Selected
Account off and hides the dedicated-account row; turning Use Selected Account on
turns Always ask off. Turning the active switch off disables routing. The saved
account identity is retained; enabling dedicated mode asks for an account only if
that identity is unavailable. Cancelling that picker leaves the current mode intact.
Legacy test-build settings with both flags on normalize to Always ask. Visible
switches animate with TextCheckCell.setChecked; account rows use granular adapter
insert/remove notifications instead of rebinding the checkbox rows. The boolean is global and participates in backup/reset/search.
Changing the mode invalidates pending asynchronous decisions and cached handoffs.
One navigation uses one picker: its selected identity is handed to chat/start/Mini
App handling without asking again for the same action.

Manual follow-up (not run by the agent): enable Always ask without selecting an
account, open a fresh bot from each logged-in account and choose different accounts.
Cancel one picker and check that no message/start was sent. Confirm Start preserves
the chosen identity and deep-link payload without a second picker. Reopen an
established bot and confirm its original account. Switch modes during username or
Mini App resolution and verify no deferred start. Restart/export/import/reset and
verify the mode preference. Switch between both modes and verify the other switch animates off. Turn the
active mode off and confirm normal routing. Enable dedicated mode without a valid
saved account and cancel its picker: the previous mode must remain unchanged.

## Routing and history

BotAccountPolicy makes the decision independently of Android. BotAccountRouter
checks actual Telegram `User.bot` metadata before applying the policy. It reads
account-local dialogs and message storage, without folder restrictions, so archives
and pending outgoing messages count. A cached user or an empty dialog does not count.
Read state retained after clearing history is positive evidence.

When local evidence is absent, the router reads
[messages.getPeerDialogs](https://core.telegram.org/method/messages.getPeerDialogs).
It uses that account's own resolved user/access hash, verifies the returned bot ID,
and examines top-message/read maxima. It never sends probing messages. Uncertain,
failed or timed-out checks block new routing rather than assuming no prior contact.
An explicit chat with positive history on its requested account can continue
immediately, including notification replies without an Activity.

A generic link with one established account uses that account. With several, it
asks the user which existing account to use. Explicit existing chat navigation
keeps its account. With none, it uses the configured identity. A removed, logged-out
or hidden configured account cannot be replaced silently. The error dialog offers
selecting another account or disabling the setting; opening the bot again is required.
Hidden accounts do not appear in the picker or automatically become visible.

Navigation is intercepted before ChatActivity.onFragmentCreate, which can itself
send a start payload. The original fragment/arguments preserve the payload, reply
and navigation context. Generic username links are checked before bot-specific
handling and re-resolved on the chosen account. A short, single-use handoff avoids
repeating history queries for the same navigation. The app's normal switchToAccount
changes the navigation identity; a bulletin identifies the account used.

Private Mini App requests are checked before either webview sheet requests a URL.
Cross-account app metadata is fetched again on the destination account. Dismissing
the sheet cancels the deferred webview launch. Group, inline, attachment-menu,
authentication and verification workflows retain their existing identity. Inline
switch-to-PM retains its originating account so the inline continuation works;
this flow is explicitly outside the feature's protection.

Send/start/forward boundaries also validate stale chats opened before the setting
changed. The text composer validates before clearing its draft. When that check
is asynchronous, successful validation asks for a fresh Send tap, avoiding sending
text edited while waiting. An incompatible stale chat must be reopened; messages
are never copied to another account. Opening the attachment menu also checks first.
Already-open attachment selectors should be closed/reopened after changing settings.

Account identities, the active account and the configured identity are captured
for asynchronous decisions; changed snapshots cancel them. No histories are moved,
merged, deleted or recreated. While enabled, identical account/bot/start-payload
requests are suppressed for the process lifetime; failed requests can retry.
Different payloads/accounts remain independent. An explicit typed `/start` is still
an ordinary message.

## Privacy limits

This is client-side account isolation, not anonymity or a Bot API restriction.
The [Bot API User object](https://core.telegram.org/bots/api#user) includes identifiers
and profile fields; a bot can retain previously received information. This setting
cannot revoke it. Telegram servers still know which accounts are used.

A conversation whose history AND all retained dialog/read state have been fully
deleted locally and on Telegram's servers cannot be identified as prior contact.
There is intentionally no new permanent account-to-bot database. Mini App sessions
without private dialog history are not a permanent conversation-history record.

Generic/new routing may require metadata and peer-dialog reads on every logged-in
account. Checks run in parallel, never scan the full message history, and time out
at 15 seconds. Offline/unresolvable accounts can block new routing. Explicit
positive-history chats avoid this dependency. Bot-only third-party plugins that
send raw MTProto requests outside Telegram's navigation/send paths are not covered.
Cold background sends with no established history are blocked; account-selection UI
requires opening the app. In-flight sends initiated before changing the setting
cannot be revoked.

## Automated validation (actually run)

```
uv run --with pytest python -m pytest tests/test_bot_privacy.py -q
```

10 tests passed. The tests compile/run the real Java policy and duplicate-start
component, and execute the actual MessagesStorage evidence SQL against SQLite.
They cover OFF, new/existing/ambiguous/missing accounts, incomplete evidence,
configuration/active-account/Always-ask changes, reused account slots, Always-ask decisions without a configured account, payload/identity
separation, failure retry, empty cached dialogs, archives, retained read state and
pending messages. They do not exercise Android navigation or real Telegram servers.
Changed Java sources also passed tree-sitter syntax parsing; localized XML parsed.

Android build attempt:

```
git submodule update --init TMessagesProj_Modules/media
./gradlew :TMessagesProj:compileDebugJavaWithJavac --console=plain
```

The initial attempt found a missing media submodule and then a missing SDK. After
provisioning Android SDK 37.0, build-tools 36.0.0, NDK 27.2.12479018, CMake 3.31.6
and Python 3.11, the ARM64 debug APK built successfully (including native code).
The user reports the original feature works on a second phone. The Always-ask
follow-up adds policy/snapshot assertions and the manual scenarios above; the
agent has not executed real multi-account bot interactions or captured screenshots.
The repository's PR workflow provisions SDK/NDK and builds `TMessagesProj:assembleDebug`.

## Device acceptance matrix (manual, not run)

Use two accounts A and B, distinct visible usernames, and test bots you control so
you can inspect received sender IDs and start payloads. Use a fresh bot per new-chat
scenario, or delete test state deliberately. Do not test with a primary identity
you do not intend to disclose to the test bot.

1. **OFF:** disable the setting, activate A, open each URL form below and press Start.
   Verify ordinary navigation and sender A. Repeat a non-bot user and channel link.
2. **ON/new:** select B, activate A, open `https://t.me/NEW_BOT`, then Start. Verify
   account B in the bulletin/navigation and exactly one `/start` from B, none from A.
3. **Payloads:** repeat with fresh bots using `https://t.me/NEW_BOT?start=test_123`,
   `https://telegram.me/NEW_BOT?start=test_123`, and
   `tg://resolve?domain=NEW_BOT&start=test_123`. Verify exact payload and sender B.
   Also test `tg://resolve?domain=NEW_BOT` without a payload.
4. **Internal links/search/profile:** from A tap a bot link in a message; separately
   use global search and open a fresh bot, and open its profile then Message.
   Verify each opens B before Start or the first typed/media message can send.
5. **Existing A:** with feature OFF start OLD_BOT on A, exchange a message, enable B,
   activate A and open the dialog, profile/Message and generic link. Verify A, no
   new conversation or start on B, unchanged history. Repeat after archiving A's
   dialog, restarting the app, and before loading the archive into memory.
6. **Multiple established accounts:** with feature OFF start BOTH_BOT on A and B;
   enable the setting. Open each existing dialog explicitly: it stays on its own
   account. Open a generic link: select A, then B in separate runs. Verify no send
   occurs before choosing, and each chosen account keeps its own history. Cancel
   the picker and verify no start is sent.
7. **Changing selection:** select A, start ONE_BOT; change selection to B. Reopen
   ONE_BOT: still A. Open fresh TWO_BOT: B. Switch the active account repeatedly;
   the saved setting must still show B's name/username.
8. **Persistence/search/backup/reset:** force-stop/relaunch, verify toggle and B;
   find the setting through settings search; export/import settings and verify B
   even if its slot changes. Reset settings and verify OFF. Repeat in Russian.
9. **Unavailable/reused slot:** select B, log B out, activate A and open a fresh bot.
   Verify explicit selection/disable UI and no message from A. Log C into B's old
   slot: it must not be selected automatically. Choose C explicitly and reopen.
10. **Duplicates:** repeatedly open the same payload link, including rapid taps
    before responses complete. Verify a single successful start for that payload
    in this process, never starts on two accounts. Verify a different payload works
    and a failed start can be retried.
11. **Async account changes:** throttle networking, open a new bot from A, switch
    accounts or remove/re-login B before resolution completes. Verify no deferred
    message/start uses a replacement slot identity. Change the selected bot account
    while resolving and verify the old decision is cancelled. Retry the link.
12. **Stale composer/forwarding:** open an unstarted bot on A with feature OFF;
    enable B while leaving that chat in the stack. Type a draft and press Send,
    then try a forward. Verify no A send and that the text draft is retained.
    Reopen via normal navigation: B. For an established A bot, change the setting,
    tap Send, edit the draft during validation, and verify a fresh Send is needed.
13. **Mini Apps:** from A launch a new private bot's main/short-name Mini App with
    a start parameter. Verify the app's `initData.user.id` is B and payload intact.
    Repeat an established A bot: A. Dismiss while checking and verify no URL request
    starts afterwards. Repeat with a changed active account during the check.
14. **Excluded workflows:** exercise inline query/switch-to-PM, group bot adding,
    attachment-menu app, authentication link and verification app on A. Verify
    their normal account-specific continuation still works; do not expect B.
15. **Errors/offline/background:** disconnect another account during a generic new
    bot check: no fallback/start. Explicit established A chats still open with
    local history. Kill the app and reply to an established bot notification;
    verify the original identity. A background new/uncertain bot send must not
    switch identities. Reconnect and reopen to retry.
