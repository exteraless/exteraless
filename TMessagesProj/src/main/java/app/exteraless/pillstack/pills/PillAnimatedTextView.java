package app.exteraless.pillstack.pills;

import android.content.Context;
import android.view.View;

import org.telegram.ui.Components.AnimatedTextView;

public class PillAnimatedTextView extends AnimatedTextView {

    public PillAnimatedTextView(Context context, boolean splitByWords, boolean preserveIndex, boolean startFromEnd) {
        super(context, splitByWords, preserveIndex, startFromEnd);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (adaptWidth && View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.AT_MOST) {
            setMeasuredDimension(finalWidth(), getMeasuredHeight());
        }
    }

    @Override
    public void setText(CharSequence text, boolean animated, boolean moveDown) {
        int previousWidth = finalWidth();
        super.setText(text, animated, moveDown);
        if (animated && previousWidth == finalWidth()) {
            return;
        }
        requestLayout();
    }
}
