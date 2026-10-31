"""Run the sending overlay's production draw block without an Android runtime."""
import subprocess
from pathlib import Path


def test_sending_overlay_draws_parent_owned_bubble_before_content(tmp_path):
    source = Path("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java").read_text()
    start = source.index("                    cell.setTimeAlpha(data.timeAlpha);")
    end = source.index("                    cell.draw(canvas);", start) + len("                    cell.draw(canvas);")
    block = source[start:end]
    java = """
class BubbleOverlayTest {
    static class Canvas {
        void save() {}
        void translate(int x, int y) {}
        void restore() {}
    }
    static class Data { float timeAlpha = 1f; }
    static class Cell {
        boolean gradient;
        float alpha;
        int backgrounds, contents;
        float getAlpha() { return alpha; }
        void setAlpha(float value) { alpha = value; }
        boolean drawBackgroundInParent() { return gradient; }
        int getPaddingTop() { return 3; }
        void setTimeAlpha(float value) {}
        void drawBackgroundInternal(Canvas canvas, boolean parent) {
            if (alpha != 1f) throw new AssertionError("Transparent overlay background");
            backgrounds++;
        }
        void draw(Canvas canvas) {
            if (!gradient) backgrounds++;
            if (backgrounds != 1) throw new AssertionError("Content rendered without its bubble");
            contents++;
        }
    }
    public static void main(String[] args) {
        Canvas canvas = new Canvas();
        Data data = new Data();
        // Regular/silent messages share this renderer; solid/gradient themes differ in ownership.
        for (boolean gradient : new boolean[] {false, true}) {
            // Pending optimistic insertion, partial animation, acknowledgment and later redraw.
            for (float alpha : new float[] {0f, .25f, 1f, 1f}) {
                Cell cell = new Cell();
                cell.gradient = gradient;
                cell.alpha = alpha;
                BLOCK
                if (cell.alpha != alpha || cell.contents != 1 || cell.backgrounds != 1)
                    throw new AssertionError("Overlay altered list state or duplicated its bubble");
            }
        }
    }
}
""".replace("BLOCK", block)
    path = tmp_path / "BubbleOverlayTest.java"
    path.write_text(java)
    subprocess.run(["javac", str(path)], check=True, capture_output=True, text=True)
    subprocess.run(["java", "-cp", str(tmp_path), "BubbleOverlayTest"], check=True, capture_output=True, text=True)
