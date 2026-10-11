"""Run the actual navigation methods on a JVM with UI/lifecycle test doubles.

These tests check position mapping and rebuild decisions, not Android rendering.
"""
import shutil
import subprocess
from pathlib import Path

import pytest

import corpus

SOURCE = Path(corpus.JAVA_ROOT) / 'org/telegram/ui/MainTabsActivity.java'


def extract_method(source, signature):
    start = source.find(signature)
    if start < 0:
        return ''
    brace = source.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


@pytest.fixture(scope='module')
def harness(tmp_path_factory):
    java = shutil.which('java')
    if java is None:
        pytest.skip('A JDK with java and jdk.compiler is needed for JVM navigation tests')
    version = subprocess.run([java, 'com.sun.tools.javac.Main', '-version'],
                             text=True, capture_output=True)
    if version.returncode != 0:
        pytest.skip('jdk.compiler is unavailable; install a standalone JDK to run these tests')
    source = SOURCE.read_text(encoding='utf-8')
    methods = '\n'.join(extract_method(source, signature) for signature in [
        'private void rebuildContactsSlot()',
        'private static int remapContactsSlotPosition(',
        'public void clearViews()',
    ])
    assert 'private void rebuildContactsSlot()' in methods
    root = tmp_path_factory.mktemp('main_tabs_java')
    program = r'''
class BaseHarness {
    public void clearViews() {}
}
public class MainTabsRebuildHarness extends BaseHarness {
    private static final int TABS_COUNT = 4;
    private static final int POSITION_CHATS = 0;
    private static final int POSITION_CONTACTS = 1;
    private boolean lastHideContacts, lastFeedTabEnabled;
    private boolean lastHideCalls, lastHideProfile;
    private boolean dropCallsFragmentAfterPageScroll;
    private boolean feedEnabled;
    private Pager viewPager = new Pager();
    private int rebuilds, drops, badges;
    private static class Pager {
        int currentPosition;
        int getCurrentPosition() { return currentPosition; }
        void setPosition(int value) { currentPosition = value; }
        void rebuild(boolean animated) {}
    }
    private static class MainTabsHelper {
        static boolean contactsHidden, callsHidden, profileHidden;
        static boolean isContactsTabHidden() { return contactsHidden; }
        static boolean isCallsTabHidden() { return callsHidden; }
        static boolean isProfileTabHidden() { return profileHidden; }
    }
    private boolean isFeedTabEnabled() { return feedEnabled; }
    private int getTabsCount() {
        return 2 + ((!MainTabsHelper.contactsHidden || feedEnabled) ? 1 : 0)
                 + (MainTabsHelper.profileHidden ? 0 : 1);
    }
    private int getPositionChats() { return 0; }
    private int getStartPosition() { return 0; }
    private int getPositionContacts() { return 1; }
    private void checkUi_contactsOrFeedTabVisible(boolean animated) {}
    private void checkUnreadCount(boolean animated) { badges++; }
    private void clearAllHiddenFragments() {}
    private void dropFragmentAtPosition(int position) { drops++; }
    private void selectTab(int position, boolean animated) { rebuilds++; }

    METHODS

    public static void main(String[] args) {
        MainTabsRebuildHarness h = new MainTabsRebuildHarness();
        h.lastHideContacts = Boolean.parseBoolean(args[0]);
        h.lastFeedTabEnabled = Boolean.parseBoolean(args[1]);
        MainTabsHelper.contactsHidden = Boolean.parseBoolean(args[2]);
        h.feedEnabled = Boolean.parseBoolean(args[3]);
        h.viewPager.currentPosition = Integer.parseInt(args[4]);
        if (args[5].equals("clear")) {
            h.clearViews();
        } else {
            for (int i = 0; i < Integer.parseInt(args[5]); i++) {
                h.rebuildContactsSlot();
            }
        }
        System.out.printf("%d,%d,%d,%d,%s,%s%n", h.viewPager.currentPosition,
                          h.rebuilds, h.drops, h.badges,
                          h.lastHideContacts, h.lastFeedTabEnabled);
    }
}
'''.replace('METHODS', methods)
    path = root / 'MainTabsRebuildHarness.java'
    path.write_text(program, encoding='utf-8')
    result = subprocess.run([java, 'com.sun.tools.javac.Main', '-encoding', 'UTF-8',
                             '-d', str(root), str(path)], text=True, capture_output=True)
    assert result.returncode == 0, result.stderr

    def run(old_hidden, old_feed, new_hidden, new_feed, position, repetitions=1):
        args = [str(v).lower() for v in [old_hidden, old_feed, new_hidden, new_feed,
                                       position, repetitions]]
        result = subprocess.run([java, '-cp', str(root), 'MainTabsRebuildHarness', *args],
                                text=True, capture_output=True)
        assert result.returncode == 0, result.stderr
        parts = result.stdout.strip().split(',')
        return tuple(int(v) for v in parts[:4]) + tuple(v == 'true' for v in parts[4:])
    return run


@pytest.mark.parametrize('position', [0, 1, 2, 3])
def test_badge_only_notification_does_not_move_or_recreate_tabs(harness, position):
    selected, rebuilds, drops, badges, _, _ = harness(True, True, True, True, position)
    assert selected == position
    assert (rebuilds, drops, badges) == (0, 0, 1)


def test_duplicate_feed_notifications_are_idempotent(harness):
    selected, rebuilds, drops, badges, _, _ = harness(True, True, True, True, 2, 3)
    assert selected == 2
    assert (rebuilds, drops, badges) == (0, 0, 3)


@pytest.mark.parametrize('old_hidden,old_feed,new_hidden,new_feed,position,expected', [
    (True, False, True, True, 1, 2),   # Settings retain their identity when feed is inserted.
    (True, False, True, True, 2, 3),   # Profile shifts right.
    (True, True, True, False, 3, 2),   # Profile shifts left when feed is removed.
    (True, True, True, False, 1, 0),   # Removed feed returns to chats.
    (False, False, True, False, 1, 0), # Hidden contacts return to chats.
    (False, False, True, False, 3, 2), # Hiding contacts preserves the profile.
    (False, False, False, True, 2, 2), # Contacts replaced by feed: no index shift.
    (False, True, False, False, 1, 1), # Feed replaced by contacts: no index shift.
])
def test_real_slot_change_preserves_selection_and_rebuilds_once(
        harness, old_hidden, old_feed, new_hidden, new_feed, position, expected):
    selected, rebuilds, _, badges, hidden, feed = harness(
        old_hidden, old_feed, new_hidden, new_feed, position, 2)
    assert selected == expected
    assert rebuilds == 1
    assert badges == 2
    assert (hidden, feed) == (new_hidden, new_feed)


def test_unchanged_contacts_layout_is_not_recreated(harness):
    selected, rebuilds, drops, _, _, _ = harness(False, False, False, False, 2)
    assert selected == 2
    assert rebuilds == drops == 0


def test_clear_views_tracks_feed_changes(harness):
    selected, _, drops, _, _, feed = harness(True, False, True, True, 2, 'clear')
    assert selected == 0
    assert drops == 3
    assert feed is True
