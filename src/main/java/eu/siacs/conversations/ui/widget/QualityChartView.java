package eu.siacs.conversations.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.color.MaterialColors;
import eu.siacs.conversations.ui.util.CallQualityMetric;
import eu.siacs.conversations.utils.TimeFrameUtils;
import eu.siacs.conversations.xmpp.jingle.CallQuality;
import java.util.List;

/**
 * Линейный график показателя качества по времени звонка; пунктир - порог ухудшения. В компактном
 * режиме без подписей осей и времени, для экрана звонка.
 */
public class QualityChartView extends View {

    private static final int GRID_LINES = 3;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint();
    private final Paint threshold = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private List<CallQuality.Sample> samples = List.of();
    private CallQualityMetric metric = CallQualityMetric.MOS;
    private boolean compact;

    public QualityChartView(final Context context) {
        this(context, null);
    }

    public QualityChartView(final Context context, @Nullable final AttributeSet attrs) {
        super(context, attrs);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(2));
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary));
        grid.setStrokeWidth(dp(1));
        grid.setColor(
                MaterialColors.getColor(
                        this, com.google.android.material.R.attr.colorOutlineVariant));
        threshold.setStyle(Paint.Style.STROKE);
        threshold.setStrokeWidth(dp(1));
        threshold.setPathEffect(new DashPathEffect(new float[] {dp(4), dp(4)}, 0));
        threshold.setColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError));
        label.setTextSize(
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP, 11, getResources().getDisplayMetrics()));
        label.setColor(
                MaterialColors.getColor(
                        this, com.google.android.material.R.attr.colorOnSurfaceVariant));
    }

    public void setCompact(final boolean compact) {
        this.compact = compact;
        invalidate();
    }

    public void setData(final List<CallQuality.Sample> samples, final CallQualityMetric metric) {
        this.samples = samples;
        this.metric = metric;
        setContentDescription(getContext().getString(metric.label));
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull final Canvas canvas) {
        super.onDraw(canvas);
        if (samples.size() < 2) {
            return;
        }
        final double limit = threshold(metric);
        double min = metric == CallQualityMetric.MOS ? 1 : 0;
        double max = metric == CallQualityMetric.MOS ? 4.5 : Math.max(1, limit * 1.2);
        for (final var sample : samples) {
            final double value = metric.value.applyAsDouble(sample);
            if (value > max) {
                max = value * 1.1;
            }
        }
        final long start = samples.get(0).elapsedMillis();
        final long end = samples.get(samples.size() - 1).elapsedMillis();
        final float textHeight = label.getTextSize();
        final float left = compact ? dp(1) : label.measureText(metric.format(max)) + dp(6);
        final float top = compact ? dp(2) : textHeight / 2;
        final float bottom = compact ? getHeight() - dp(2) : getHeight() - textHeight - dp(6);
        final float right = getWidth() - dp(2);
        final float height = bottom - top;
        final float width = right - left;
        if (compact) {
            canvas.drawLine(left, bottom, right, bottom, grid);
        } else {
            for (int i = 0; i < GRID_LINES; i++) {
                final double value = min + (max - min) * i / (GRID_LINES - 1);
                final float y = (float) (bottom - height * (value - min) / (max - min));
                canvas.drawLine(left, y, right, y, grid);
                canvas.drawText(metric.format(value), 0, y + textHeight / 3, label);
            }
        }
        if (limit > min && limit < max) {
            final float y = (float) (bottom - height * (limit - min) / (max - min));
            canvas.drawLine(left, y, right, y, threshold);
        }
        path.reset();
        boolean drawing = false;
        for (final var sample : samples) {
            final double value = metric.value.applyAsDouble(sample);
            if (value < 0) {
                drawing = false;
                continue;
            }
            final float x =
                    end == start
                            ? left
                            : left + width * (sample.elapsedMillis() - start) / (end - start);
            final float y = (float) (bottom - height * (value - min) / (max - min));
            if (drawing) {
                path.lineTo(x, y);
            } else {
                path.moveTo(x, y);
                drawing = true;
            }
        }
        canvas.drawPath(path, line);
        if (compact) {
            return;
        }
        final float baseline = getHeight() - dp(2);
        canvas.drawText(TimeFrameUtils.formatElapsedTime(start, false), left, baseline, label);
        final String endLabel = TimeFrameUtils.formatElapsedTime(end, false);
        canvas.drawText(endLabel, right - label.measureText(endLabel), baseline, label);
    }

    private static double threshold(final CallQualityMetric metric) {
        return switch (metric) {
            case LOSS_IN, LOSS_OUT -> CallQuality.LOSS_THRESHOLD_PERCENT;
            case RTT -> CallQuality.RTT_THRESHOLD_MILLIS;
            case MOS -> CallQuality.FAIR_MOS;
            default -> -1;
        };
    }

    private float dp(final float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
