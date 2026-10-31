"""Keep the real composer available while the preview covers it with blur."""
import subprocess
from pathlib import Path

SOURCE = Path("TMessagesProj/src/main/java/org/telegram/ui/MessageSendPreview.java")


def test_preview_does_not_hide_composer_at_full_blur(tmp_path):
    source = SOURCE.read_text()
    start = source.index("protected void dispatchDraw(@NonNull Canvas canvas) {")
    start = source.index("{", start) + 1
    gate = source[start:source.index("if (openProgress > 0", start)]
    java = """
class PreviewFocusTest {
    static class Controller {
        boolean hidden;
        void setHidden(boolean value) { hidden = value; }
    }
    public static void main(String[] args) {
        Controller activityVisibilityController = new Controller();
        for (float openProgress : new float[] {0f, .5f, 1f, 1f, .5f, 0f}) {
            for (Object blurBitmapPaint : new Object[] {null, new Object()}) {
                GATE
                if (activityVisibilityController.hidden)
                    throw new AssertionError("Preview hid composer and deferred message layout");
            }
        }
    }
}
""".replace("GATE", gate)
    path = tmp_path / "PreviewFocusTest.java"
    path.write_text(java)
    subprocess.run(["javac", str(path)], check=True, capture_output=True, text=True)
    subprocess.run(["java", "-cp", str(tmp_path), "PreviewFocusTest"], check=True, capture_output=True, text=True)


def test_preview_window_keeps_composer_as_keyboard_target():
    source = SOURCE.read_text()
    setup = source[source.index("params.dimAmount = 0;"):source.index("private boolean focusable;")]
    assert "params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;" in setup
    focus = source[source.index("public void makeFocusable()"):source.index("public boolean isShowing()")]
    assert "FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM" in focus
