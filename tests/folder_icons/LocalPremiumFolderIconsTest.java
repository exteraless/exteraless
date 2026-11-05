import static app.exteraless.appearance.LocalPremiumFolderIcons.*;

import app.exteraless.appearance.AppearanceConfig;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;

public class LocalPremiumFolderIconsTest {
    public static void main(String[] args) {
        int folder = 2;
        long emoji = 543210987654321L;
        AppearanceConfig.enabled = true;
        switch (args[0]) {
            case "visibility":
                set(0, folder, emoji);
                AppearanceConfig.enabled = false;
                assert !enabledFor(0);
                assert get(0, folder) == 0;
                assert getStored(0, folder) == emoji;
                set(0, folder, 123);
                AppearanceConfig.enabled = true;
                assert enabledFor(0);
                assert get(0, folder) == emoji;
                UserConfig.getInstance(0).premium = true;
                assert !enabledFor(0);
                assert get(0, folder) == 0;
                assert getStored(0, folder) == emoji;
                set(0, folder, 456);
                UserConfig.getInstance(0).premium = false;
                assert get(0, folder) == emoji;
                break;
            case "isolation":
                set(0, folder, emoji);
                set(1, folder, emoji + 1);
                set(0, folder + 1, emoji + 2);
                assert get(0, folder) == emoji;
                assert get(1, folder) == emoji + 1;
                assert get(0, folder + 1) == emoji + 2;
                break;
            case "deletion":
                set(0, folder, emoji);
                set(1, folder, emoji);
                AppearanceConfig.enabled = false;
                set(0, folder, 0);
                AppearanceConfig.enabled = true;
                assert get(0, folder) == 0;
                assert get(1, folder) == emoji;
                break;
            case "notifications":
                set(0, 0, emoji);
                assert get(0, 0) == 0;
                set(0, folder, 0);
                assert NotificationCenter.getInstance(0).updates == 0;
                set(0, folder, emoji);
                set(0, folder, emoji);
                assert NotificationCenter.getInstance(0).updates == 1;
                set(0, folder, 0);
                assert NotificationCenter.getInstance(0).updates == 2;
                assert NotificationCenter.getInstance(1).updates == 0;
                break;
            case "hint":
                assert markHintShown(0);
                assert !markHintShown(0);
                assert markHintShown(1);
                assert !markHintShown(1);
                break;
            default:
                throw new AssertionError("Unknown scenario: " + args[0]);
        }
    }
}
