package eu.siacs.conversations.ui.util;

import androidx.annotation.StringRes;
import eu.siacs.conversations.R;
import eu.siacs.conversations.xmpp.jingle.CallQuality;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

/** Показатели временного ряда качества для таблицы и графика отчета. */
public enum CallQualityMetric {
    MOS(R.string.cocosations_metric_mos, CallQuality.Sample::mos, 1),
    RTT(R.string.cocosations_metric_rtt, CallQuality.Sample::rttMillis, 0),
    LOSS_IN(R.string.cocosations_metric_loss_in, CallQuality.Sample::lossIn, 1),
    LOSS_OUT(R.string.cocosations_metric_loss_out, CallQuality.Sample::lossOut, 1),
    JITTER_IN(R.string.cocosations_metric_jitter_in, CallQuality.Sample::jitterInMillis, 0),
    JITTER_OUT(R.string.cocosations_metric_jitter_out, CallQuality.Sample::jitterOutMillis, 0),
    BITRATE_IN(R.string.cocosations_metric_bitrate_in, CallQuality.Sample::bitrateIn, 0),
    BITRATE_OUT(R.string.cocosations_metric_bitrate_out, CallQuality.Sample::bitrateOut, 0),
    CONCEALED(R.string.cocosations_metric_concealed, CallQuality.Sample::concealed, 1);

    @StringRes public final int label;
    public final ToDoubleFunction<CallQuality.Sample> value;
    private final int decimals;

    CallQualityMetric(
            @StringRes final int label,
            final ToDoubleFunction<CallQuality.Sample> value,
            final int decimals) {
        this.label = label;
        this.value = value;
        this.decimals = decimals;
    }

    public String format(final double value) {
        return String.format(Locale.getDefault(), "%." + decimals + "f", value);
    }
}
