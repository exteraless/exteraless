package app.exteraless.math;

import android.graphics.Canvas;
import android.graphics.Region;
import android.text.Editable;
import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.MetricAffectingSpan;
import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.QuoteSpan;
import org.telegram.ui.Components.TextStyleSpan;

import java.text.DecimalFormatSymbols;
import java.util.Locale;

import app.exteraless.chats.ChatsConfig;

public final class InlineMathController {

    public interface Delegate {
        int accentColor();

        int account();

        void runProgrammatic(Runnable action);
    }

    private final TextView view;
    private final Delegate delegate;
    private final GhostTextLayout ghost = new GhostTextLayout();
    private final AnimatedFloat appear;
    private final MathRevealAnimation reveal;
    private final Runnable announce = this::announce;
    private final Runnable refresh = this::refresh;

    private String pendingQuery;
    private int pendingEquals = -1;

    private MathExpression.Suggestion suggestion;
    private String announcedValue;
    private Locale separatorLocale;
    private char decimalSeparator = '.';
    private boolean dirty = true;
    private boolean insertingSelf;
    private boolean caretMovedByTouch;
    private int layoutWidth = -1;
    private int swallowKeyCode;
    private int undoStart = -1;
    private int undoEnd = -1;
    private int suppressedAt = -1;

    public InlineMathController(TextView view, Delegate delegate) {
        this.view = view;
        this.delegate = delegate;
        this.appear = new AnimatedFloat(view, 0, 120, CubicBezierInterpolator.EASE_OUT_QUINT);
        this.reveal = new MathRevealAnimation(view, () -> dirty = true);
    }

    private static boolean enabled() {
        ChatsConfig.ensureLoaded();
        return ChatsConfig.inlineMathResult.Bool();
    }

    private static boolean botAllowed() {
        return ChatsConfig.inlineMathCurrency.Bool() && ChatsConfig.inlineMathCalcmula.Bool();
    }

    private InlineOptions options() {
        return InlineResults.options(decimalSeparator(), refresh);
    }

    private char decimalSeparator() {
        Locale locale = LocaleController.getInstance().getCurrentLocale();
        if (locale == null) {
            locale = Locale.US;
        }
        if (!locale.equals(separatorLocale)) {
            separatorLocale = locale;
            decimalSeparator = DecimalFormatSymbols.getInstance(locale).getDecimalSeparator();
        }
        return decimalSeparator;
    }

    private boolean canTrigger() {
        if (!enabled()) {
            return false;
        }
        CharSequence text = view.getText();
        int caret = view.getSelectionStart();
        return text != null && caret >= 1 && caret <= text.length() && text.charAt(caret - 1) == '=';
    }

    private void schedule() {
        dirty = true;
        view.invalidate();
        if (ghost.getExtraHeight() != 0 || suggestion != null || canTrigger()) {
            view.requestLayout();
        }
    }

    private void refresh() {
        if (pendingQuery != null && acceptPending()) {
            return;
        }
        dirty = true;
        view.requestLayout();
        view.invalidate();
    }

    private void markPendingAccept() {
        pendingQuery = null;
        pendingEquals = -1;
        if (!botAllowed() || delegate == null) {
            return;
        }
        CharSequence text = view.getText();
        int caret = view.getSelectionStart();
        if (text == null || caret != view.getSelectionEnd()) {
            return;
        }
        if (MathExpression.suggestionAt(text, caret, options()) != null) {
            return;
        }
        String query = CalcmulaCurrency.queryAt(text, caret);
        if (query == null || CalcmulaCurrency.isKnown(query)) {
            return;
        }
        pendingQuery = query;
        pendingEquals = caret - 1;
        CalcmulaCurrency.request(delegate.account(), query, refresh);
    }

    private boolean acceptPending() {
        String query = pendingQuery;
        int equals = pendingEquals;
        pendingQuery = null;
        pendingEquals = -1;
        String value = CalcmulaCurrency.resultFor(query);
        CharSequence text = view.getText();
        Editable editable = text instanceof Editable ? (Editable) text : null;
        int caret = view.getSelectionStart();
        if (value == null || editable == null || !view.isFocused() || caret != view.getSelectionEnd()
                || equals < 0 || caret != equals + 2 || caret > editable.length()
                || editable.charAt(equals) != '=' || editable.charAt(caret - 1) != ' '
                || !query.equals(CalcmulaCurrency.queryAt(editable, equals + 1))) {
            return false;
        }
        BaseInputConnection.removeComposingSpans(editable);
        if (!edit(() -> editable.insert(caret, value))) {
            return false;
        }
        Selection.setSelection(editable, caret + value.length());
        suggestion = null;
        ghost.clear();
        undoStart = caret;
        undoEnd = caret + value.length();
        dirty = true;
        view.requestLayout();
        view.invalidate();
        return true;
    }

    private void announce() {
        String value = suggestion != null ? suggestion.value : null;
        if (value != null) {
            announcedValue = value;
            AndroidUtilities.makeAccessibilityAnnouncement(LocaleController.formatString(R.string.OEChatsInlineMathAnnouncement, value));
        }
    }

    private void clearUndo() {
        undoStart = -1;
        undoEnd = -1;
    }

    private boolean edit(Runnable block) {
        boolean[] ok = {true};
        Runnable action = () -> {
            insertingSelf = true;
            try {
                block.run();
            } catch (Exception e) {
                FileLog.e(e);
                ok[0] = false;
            } finally {
                insertingSelf = false;
            }
        };
        if (delegate != null) {
            delegate.runProgrammatic(action);
        } else {
            action.run();
        }
        return ok[0];
    }

    private boolean commit() {
        MathExpression.Suggestion current = suggestion;
        if (current == null) {
            return false;
        }
        CharSequence text = view.getText();
        Editable editable = text instanceof Editable ? (Editable) text : null;
        if (editable == null || current.insertAt != view.getSelectionStart() || view.getSelectionStart() != view.getSelectionEnd() || current.insertAt > editable.length()) {
            return false;
        }
        int lead = current.insertText.length() - current.value.length();
        int count = current.value.length();
        float[] x = new float[count];
        float[] y = new float[count];
        ghost.readInsertedPositions(lead, count, x, y);
        BaseInputConnection.removeComposingSpans(editable);
        if (!edit(() -> editable.insert(current.insertAt, current.insertText))) {
            return false;
        }
        Selection.setSelection(editable, current.insertAt + current.insertText.length());
        suggestion = null;
        ghost.clear();
        dirty = true;
        reveal.begin(editable, current.insertAt + lead, count, x, y);
        undoStart = current.insertAt;
        undoEnd = current.insertAt + current.insertText.length();
        appear.set(0f, true);
        view.requestLayout();
        view.invalidate();
        return true;
    }

    private boolean undo() {
        if (undoStart < 0) {
            return false;
        }
        CharSequence text = view.getText();
        Editable editable = text instanceof Editable ? (Editable) text : null;
        if (editable == null) {
            return false;
        }
        if (undoEnd > editable.length() || view.getSelectionStart() != undoEnd || view.getSelectionEnd() != undoEnd) {
            clearUndo();
            return false;
        }
        reveal.cancel();
        int start = undoStart;
        int end = undoEnd;
        if (!edit(() -> editable.delete(start, end))) {
            return false;
        }
        Selection.setSelection(editable, start);
        clearUndo();
        suppressedAt = start;
        dirty = true;
        view.requestLayout();
        view.invalidate();
        return true;
    }

    private boolean hasUnsupportedSpans(CharSequence text, int start, int end) {
        if (!(text instanceof Spanned)) {
            return false;
        }
        Spanned spanned = (Spanned) text;
        if (spanned.getSpans(start, end, AnimatedEmojiSpan.class).length > 0 || spanned.getSpans(start, end, QuoteSpan.class).length > 0) {
            return true;
        }
        for (TextStyleSpan span : spanned.getSpans(start, end, TextStyleSpan.class)) {
            if (span.isSpoiler()) {
                return true;
            }
        }
        return false;
    }

    private MathExpression.Suggestion findSuggestion(CharSequence text, int caret) {
        MathExpression.Suggestion local = MathExpression.suggestionAt(text, caret, options());
        if (local != null || !botAllowed() || delegate == null || InlineResults.ratesPending()) {
            return local;
        }
        String query = CalcmulaCurrency.queryAt(text, caret);
        if (query == null) {
            return null;
        }
        if (!CalcmulaCurrency.isKnown(query)) {
            CalcmulaCurrency.request(delegate.account(), query, refresh);
            return null;
        }
        return CalcmulaCurrency.suggestionAt(text, caret, query);
    }

    private void update() {
        if (!dirty) {
            return;
        }
        dirty = false;
        suggestion = null;
        ghost.clear();
        if (!enabled() || reveal.isRunning() || suppressedAt >= 0 || !view.isFocused() || !view.isEnabled()) {
            return;
        }
        Layout layout = view.getLayout();
        CharSequence text = view.getText();
        int caret = view.getSelectionStart();
        if (layout == null || text == null || caret <= 0 || caret != view.getSelectionEnd() || caret > text.length()) {
            return;
        }
        MathExpression.Suggestion found = findSuggestion(text, caret);
        if (found == null) {
            return;
        }
        if (text instanceof Spannable && BaseInputConnection.getComposingSpanStart((Spannable) text) != -1) {
            return;
        }
        if (text instanceof Spanned && ((Spanned) text).getSpans(caret - 1, caret, MetricAffectingSpan.class).length > 0) {
            return;
        }
        if (layout.getParagraphDirection(layout.getLineForOffset(caret)) != Layout.DIR_LEFT_TO_RIGHT) {
            return;
        }
        int paragraphStart = TextUtils.lastIndexOf(text, '\n', caret - 1) + 1;
        int paragraphEnd = TextUtils.indexOf(text, '\n', caret);
        if (paragraphEnd < 0) {
            paragraphEnd = text.length();
        }
        boolean lastParagraph = paragraphEnd >= text.length();
        if (hasUnsupportedSpans(text, paragraphStart, paragraphEnd)) {
            if (lastParagraph && ghost.buildDetached(view, layout, paragraphEnd, found.insertText)) {
                suggestion = found;
            }
            return;
        }
        if (!ghost.build(view, layout, paragraphStart, paragraphEnd, caret, found.insertText)) {
            return;
        }
        boolean fits = (ghost.getExtraHeight() <= 0 || lastParagraph) && !(ghost.hasMovedText() && caretMovedByTouch);
        if (fits || (lastParagraph && ghost.buildDetached(view, layout, paragraphEnd, found.insertText))) {
            suggestion = found;
        } else {
            ghost.clear();
        }
    }

    private void updateAnnouncement() {
        String value = suggestion != null ? suggestion.value : null;
        if (value == null) {
            announcedValue = null;
            AndroidUtilities.cancelRunOnUIThread(announce);
        } else if (!value.equals(announcedValue)) {
            AndroidUtilities.cancelRunOnUIThread(announce);
            AndroidUtilities.runOnUIThread(announce, 600);
        }
    }

    public void cancel() {
        pendingQuery = null;
        pendingEquals = -1;
        AndroidUtilities.cancelRunOnUIThread(announce);
        announcedValue = null;
        reveal.cancel();
        clearUndo();
        suppressedAt = -1;
        caretMovedByTouch = false;
        suggestion = null;
        ghost.clear();
        schedule();
    }

    public void clipReplacedParagraph(Canvas canvas, int top) {
        Layout layout = view.getLayout();
        if (ghost.isEmpty() || ghost.isDetached() || layout == null) {
            return;
        }
        canvas.clipRect(0, top + layout.getLineTop(layout.getLineForOffset(ghost.getParagraphStart())),
                view.getWidth(), top + layout.getLineBottom(layout.getLineForOffset(ghost.getParagraphEnd())), Region.Op.DIFFERENCE);
    }

    public void draw(Canvas canvas, int left, int top, float clipTop, float clipBottom) {
        boolean revealing = reveal.isRunning();
        if (!revealing && ghost.isEmpty()) {
            appear.set(0f, true);
            return;
        }
        canvas.save();
        canvas.clipRect(0, clipTop, view.getWidth(), clipBottom);
        canvas.translate(left, top);
        if (revealing) {
            reveal.draw(canvas, delegate != null ? delegate.accentColor() : view.getCurrentTextColor());
        } else {
            ghost.setAlpha(appear.set(1f) * 0.4f);
            ghost.draw(canvas);
        }
        canvas.restore();
    }

    public boolean hasCursorShift() {
        return ghost.hasMovedText();
    }

    public float getCursorShiftX() {
        return hasCursorShift() ? ghost.getCursorShiftX() : 0f;
    }

    public float getCursorShiftY() {
        return hasCursorShift() ? ghost.getCursorShiftY() : 0f;
    }

    public int getExtraBottom() {
        return ghost.getExtraHeight();
    }

    public void invalidateState() {
        if (!insertingSelf) {
            int caret = view.getSelectionStart();
            if (caret != suppressedAt) {
                suppressedAt = -1;
            }
            if (caret != undoEnd || view.getSelectionEnd() != undoEnd) {
                clearUndo();
            }
            if (reveal.isRunning() && reveal.isCaretOutside(caret)) {
                reveal.cancel();
            }
        }
        schedule();
    }

    public void onFocusChanged(boolean focused) {
        if (!focused) {
            reveal.cancel();
            clearUndo();
        }
        schedule();
    }

    public void onTextChanged() {
        if (!insertingSelf) {
            reveal.cancel();
            clearUndo();
            suppressedAt = -1;
            caretMovedByTouch = false;
        }
        schedule();
    }

    public void onTouchDown() {
        if (!caretMovedByTouch) {
            caretMovedByTouch = true;
            schedule();
        }
    }

    public boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (swallowKeyCode != 0 && event.getKeyCode() == swallowKeyCode) {
                swallowKeyCode = 0;
                return true;
            }
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0 || event.isCtrlPressed() || event.isAltPressed() || event.isShiftPressed()) {
            return false;
        }
        boolean handled;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_SPACE:
                handled = commit();
                if (!handled) {
                    markPendingAccept();
                }
                break;
            case KeyEvent.KEYCODE_DEL:
                handled = undo();
                break;
            default:
                handled = false;
        }
        if (handled) {
            swallowKeyCode = event.getKeyCode();
        }
        return handled;
    }

    public boolean updateOnMeasure() {
        int extraHeight = ghost.getExtraHeight();
        Layout layout = view.getLayout();
        int width = layout != null ? layout.getWidth() : 0;
        if (width != layoutWidth) {
            layoutWidth = width;
            dirty = true;
        }
        update();
        if (AndroidUtilities.isAccessibilityScreenReaderEnabled()) {
            updateAnnouncement();
        }
        return ghost.getExtraHeight() != extraHeight;
    }

    public InputConnection wrap(InputConnection connection) {
        return new InputConnectionWrapper(connection, true) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                if (text != null && text.length() == 1 && text.charAt(0) == ' ') {
                    if (suggestion != null) {
                        finishComposingText();
                        if (commit()) {
                            return true;
                        }
                    }
                    markPendingAccept();
                }
                return super.commitText(text, newCursorPosition);
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (beforeLength == 1 && afterLength == 0 && undo()) {
                    return true;
                }
                return super.deleteSurroundingText(beforeLength, afterLength);
            }

            @Override
            public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
                if (beforeLength == 1 && afterLength == 0 && undo()) {
                    return true;
                }
                return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
            }
        };
    }
}
