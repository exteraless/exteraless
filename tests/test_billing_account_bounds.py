"""Run the production purchase decoder against malformed and restored slot IDs."""
import os
import shutil
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/utils/BillingUtilities.java"


@pytest.mark.parametrize("capacity", [10, 100])
def test_restored_purchase_account_bounds(tmp_path, capacity):
    home = os.environ.get("JAVA_HOME")
    suffix = ".exe" if os.name == "nt" else ""
    tools = [str(Path(home) / "bin" / (name + suffix)) if home else shutil.which(name)
             for name in ("javac", "java")]
    if not all(tools):
        pytest.skip("JDK required: set JAVA_HOME or put javac and java on PATH")

    production = SOURCE.read_text(encoding="utf-8")
    start = production.index("    public static Pair<AccountInstance, TLRPC.InputStorePaymentPurpose> extractDeveloperPayload(")
    end = production.index("    public static void cleanupPurchase(", start)
    harness = r"""
import java.nio.charset.StandardCharsets;

class BillingAccountBoundsTest {
    static class UserConfig {
        static final int MAX_ACCOUNT_COUNT = CAPACITY;
        boolean isClientActivated() { return false; }
    }
    static class AccountInstance {
        static int lookups;
        final int slot;
        AccountInstance(int slot) { this.slot = slot; }
        static AccountInstance getInstance(int slot) {
            lookups++;
            if (slot < 0 || slot >= UserConfig.MAX_ACCOUNT_COUNT) {
                throw new AssertionError("Out-of-range lookup: " + slot);
            }
            return new AccountInstance(slot);
        }
        UserConfig getUserConfig() { return new UserConfig(); }
    }
    static class TLRPC {
        static class InputStorePaymentPurpose {}
    }
    static class Pair<A, B> {
        final A first;
        final B second;
        Pair(A first, B second) { this.first = first; this.second = second; }
        static <A, B> Pair<A, B> create(A first, B second) {
            return new Pair<>(first, second);
        }
    }
    static class AccountIdentifiers {
        final String account;
        final String purpose;
        AccountIdentifiers(String account, String purpose) {
            this.account = account;
            this.purpose = purpose;
        }
        String getObfuscatedAccountId() { return account; }
        String getObfuscatedProfileId() { return purpose; }
    }
    static class Purchase {
        final AccountIdentifiers identifiers;
        Purchase(AccountIdentifiers identifiers) { this.identifiers = identifiers; }
        AccountIdentifiers getAccountIdentifiers() { return identifiers; }
    }
    static class Base64 {
        static final int DEFAULT = 0;
        static byte[] decode(String value, int flags) {
            return java.util.Base64.getDecoder().decode(value);
        }
    }
    static class Charsets {
        static final java.nio.charset.Charset UTF_8 = StandardCharsets.UTF_8;
    }
    static class FileLog {
        static void d(String text) {}
        static void e(String text, Exception error) {}
    }
    static final TLRPC.InputStorePaymentPurpose PURPOSE = new TLRPC.InputStorePaymentPurpose();
    static TLRPC.InputStorePaymentPurpose getPurpose(String value) { return PURPOSE; }
    static long resolvedUserId;
    static AccountInstance findAccountById(long userId) {
        resolvedUserId = userId;
        return userId == 1234567890123L ? new AccountInstance(2) : null;
    }

    PRODUCTION_METHOD

    static Purchase purchase(String decoded) {
        String encoded = java.util.Base64.getEncoder().encodeToString(decoded.getBytes(Charsets.UTF_8));
        return new Purchase(new AccountIdentifiers(encoded, "saved-purpose"));
    }
    static void rejected(Purchase purchase) {
        AccountInstance.lookups = 0;
        resolvedUserId = 0;
        if (extractDeveloperPayload(purchase) != null) {
            throw new AssertionError("Invalid restored purchase accepted");
        }
        if (AccountInstance.lookups != 0 || resolvedUserId != 0) {
            throw new AssertionError("Rejected slot reached account lookup");
        }
    }
    public static void main(String[] args) {
        // All physical slots, including inactive pending-login slots, remain usable.
        for (int slot = 0; slot < UserConfig.MAX_ACCOUNT_COUNT; slot++) {
            AccountInstance.lookups = 0;
            Pair<AccountInstance, TLRPC.InputStorePaymentPurpose> result =
                    extractDeveloperPayload(purchase("account-" + slot));
            if (result == null || result.first.slot != slot || result.second != PURPOSE
                    || AccountInstance.lookups != 1) {
                throw new AssertionError("Valid pending slot rejected: " + slot);
            }
        }
        for (String decoded : new String[] {
                "account-", "account--1", "account-+1", "account- 1", "account-1 ",
                "account-1\n", "account-1.0", "account-1e0", "account-\u0661", "account-\uff11",
                "account-" + UserConfig.MAX_ACCOUNT_COUNT,
                "account-" + (UserConfig.MAX_ACCOUNT_COUNT + 1),
                "account-2147483647", "account-2147483648", "account-4294967296",
                "account-999999999999999999999999999999999999999999"
        }) {
            rejected(purchase(decoded));
        }
        rejected(new Purchase(null));
        rejected(new Purchase(new AccountIdentifiers(null, "saved-purpose")));
        rejected(new Purchase(new AccountIdentifiers("", "saved-purpose")));
        rejected(new Purchase(new AccountIdentifiers("%%%", "saved-purpose")));
        rejected(new Purchase(new AccountIdentifiers("YWNjb3VudC0w", null)));
        rejected(new Purchase(new AccountIdentifiers("YWNjb3VudC0w", "")));

        // Existing user-ID-based payment payloads retain their resolution path.
        AccountInstance.lookups = 0;
        Pair<AccountInstance, TLRPC.InputStorePaymentPurpose> result =
                extractDeveloperPayload(purchase("1234567890123"));
        if (result == null || result.first.slot != 2 || result.second != PURPOSE
                || resolvedUserId != 1234567890123L || AccountInstance.lookups != 0) {
            throw new AssertionError("User-ID payload resolution changed");
        }
    }
}
""".replace("CAPACITY", str(capacity)).replace("PRODUCTION_METHOD", production[start:end])
    source = tmp_path / "BillingAccountBoundsTest.java"
    source.write_text(harness, encoding="utf-8")
    for command in ([tools[0], "-encoding", "UTF-8", "-d", str(tmp_path), str(source)],
                    [tools[1], "-cp", str(tmp_path), "BillingAccountBoundsTest"]):
        result = subprocess.run(command, capture_output=True, text=True, timeout=60)
        assert result.returncode == 0, result.stdout + result.stderr
