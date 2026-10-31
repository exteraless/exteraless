# Bot privacy implementation plan

Goal: route only new private bot interactions to a stable, user-selected Telegram identity.

1. Add a dependency-free policy and executable tests for disabled, existing, new,
   ambiguous, missing-account and incomplete-evidence decisions.
2. Implement asynchronous account-local user resolution and history checks. Positive
   local dialog/message evidence counts; otherwise query server peer dialogs. Never
   send a probe. Fail closed on errors or account/settings changes.
3. Gate private chat navigation before fragment creation, generic username links before
   bot handling, private Mini Apps before webview requests, and stale-chat sends.
   Keep payloads and original fragments; use existing account switching.
4. Add global ConfigItems and native English/Russian settings, search, backup/reset.
5. Run policy tests and Android compile, document exact manual acceptance scenarios
   and limits, review the diff, commit, push and create the requested upstream PR.

Scope: GeneralConfig/settings/resources/search; shared routing and policy;
MessagesStorage read-only history lookup; LaunchActivity; SendMessagesHelper and
MessagesController send boundaries; BotWebViewSheet. No new dependencies, histories,
inline/group/attachment/auth workflow changes, or migration messages.
