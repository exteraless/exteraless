"""Exercise the production helper against in-memory per-account Android services."""
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'TMessagesProj/src/main/java/app/exteraless/appearance/LocalPremiumFolderIcons.java'

# Keep preferences and notifications observable across successive calls just as the app does.
STUBS = {
    'android.content.SharedPreferences': """
        private final java.util.Map<String, Object> data = new java.util.HashMap<>();
        public long getLong(String key, long fallback) { return (Long) data.getOrDefault(key, fallback); }
        public boolean getBoolean(String key, boolean fallback) { return (Boolean) data.getOrDefault(key, fallback); }
        public Editor edit() { return new Editor(); }
        public class Editor {
            private final java.util.Map<String, Object> edits = new java.util.HashMap<>();
            public Editor putLong(String key, long value) { edits.put(key, value); return this; }
            public Editor putBoolean(String key, boolean value) { edits.put(key, value); return this; }
            public Editor remove(String key) { edits.put(key, null); return this; }
            public void apply() {
                edits.forEach((key, value) -> {
                    if (value == null) data.remove(key);
                    else data.put(key, value);
                });
            }
        }
    """,
    'org.telegram.messenger.UserConfig': """
        private static final UserConfig[] accounts = {new UserConfig(), new UserConfig()};
        private final android.content.SharedPreferences prefs = new android.content.SharedPreferences();
        public boolean premium;
        public static UserConfig getInstance(int account) { return accounts[account]; }
        public android.content.SharedPreferences getPreferences() { return prefs; }
        public boolean isPremium() { return premium; }
    """,
    'org.telegram.messenger.NotificationCenter': """
        public static final int localFolderIconsChanged = 1;
        private static final NotificationCenter[] accounts = {new NotificationCenter(), new NotificationCenter()};
        public static NotificationCenter getInstance(int account) { return accounts[account]; }
        public int updates;
        public void postNotificationName(int id, Object... args) {
            assert id == localFolderIconsChanged;
            updates++;
        }
    """,
    'org.telegram.messenger.AndroidUtilities': """
        public static void runOnUIThread(Runnable task) { task.run(); }
    """,
    'app.exteraless.appearance.AppearanceConfig': """
        public static boolean enabled;
        public static boolean localPremiumFolderIcons() { return enabled; }
    """,
}


@pytest.fixture(scope='module')
def classpath(tmp_path_factory):
    if not shutil.which('javac'):
        pytest.skip('JDK required')
    tmp = tmp_path_factory.mktemp('folder_icons')
    files = []
    for name, body in STUBS.items():
        package, simple_name = name.rsplit('.', 1)
        path = tmp / f'{simple_name}.java'
        path.write_text(f'package {package}; public class {simple_name} {{ {body} }}')
        files.append(str(path))
    subprocess.run(['javac', '-d', str(tmp), *files, str(SOURCE),
                    str(ROOT / 'tests/folder_icons/LocalPremiumFolderIconsTest.java')], check=True)
    return str(tmp)


@pytest.mark.parametrize('scenario', ['visibility', 'isolation', 'deletion', 'notifications', 'hint'])
def test_folder_icon_policy(classpath, scenario):
    subprocess.run(['java', '-ea', '-cp', classpath, 'LocalPremiumFolderIconsTest', scenario], check=True)
