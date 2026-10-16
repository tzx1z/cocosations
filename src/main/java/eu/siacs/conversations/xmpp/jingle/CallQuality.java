package eu.siacs.conversations.xmpp.jingle;

import androidx.annotation.Nullable;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Качество звука по getStats(): кодек, потери, jitter, битрейт, упрощенный MOS по E-model (ITU-T
 * G.107) и временной ряд. Неизвестные значения отрицательные.
 */
public class CallQuality {

    static final int MAX_SAMPLES = 720;
    public static final double LOSS_THRESHOLD_PERCENT = 5;
    public static final long RTT_THRESHOLD_MILLIS = 300;
    private static final double GOOD_MOS = 4.0;
    public static final double FAIR_MOS = 3.6;
    // разница MOS меньше этой считается одинаковым качеством в обе стороны
    private static final double DIRECTION_MOS_DELTA = 0.2;
    private static final long MIN_LIVE_INTERVAL_MILLIS = 500;
    // окно графика на экране звонка
    private static final long RECENT_WINDOW_MILLIS = 60_000;

    public enum Rating {
        GOOD,
        FAIR,
        POOR
    }

    public enum Problem {
        NONE,
        INCOMING,
        OUTGOING,
        BOTH
    }

    public record Codec(String mimeType, long clockRate, long channels, String fmtp) {

        public String name() {
            final int slash = mimeType.indexOf('/');
            return slash >= 0 ? mimeType.substring(slash + 1) : mimeType;
        }

        public boolean fec() {
            return hasParameter("useinbandfec", "1");
        }

        public boolean dtx() {
            return hasParameter("usedtx", "1");
        }

        private boolean hasParameter(final String name, final String value) {
            if (fmtp == null) {
                return false;
            }
            for (final String parameter : Splitter.on(';').trimResults().split(fmtp)) {
                if (parameter.equalsIgnoreCase(name + "=" + value)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Счетчики аудио и транспорта с начала звонка из одного отчета getStats(); время в секундах.
     */
    public record Counters(
            long packetsReceived,
            long packetsLost,
            long bytesReceived,
            double jitter,
            double jitterBufferDelay,
            long jitterBufferEmitted,
            long totalSamples,
            long concealedSamples,
            long packetsSent,
            long bytesSent,
            long remotePacketsLost,
            double remoteJitter,
            double remoteFractionLost,
            double remoteRtt,
            long transportBytesSent,
            long transportBytesReceived,
            double pairRtt,
            Codec codecIn,
            Codec codecOut) {

        /** Отчет приведен к id -> поля, где поле "type" - тип записи RTCStats. */
        public static Counters parse(final Map<String, Map<String, Object>> report) {
            long packetsReceived = 0;
            long packetsLost = 0;
            long bytesReceived = 0;
            double jitter = -1;
            double jitterBufferDelay = 0;
            long jitterBufferEmitted = 0;
            long totalSamples = 0;
            long concealedSamples = 0;
            long packetsSent = 0;
            long bytesSent = 0;
            long remotePacketsLost = 0;
            double remoteJitter = -1;
            double remoteFractionLost = -1;
            double remoteRtt = -1;
            long transportBytesSent = 0;
            long transportBytesReceived = 0;
            double pairRtt = -1;
            Codec codecIn = null;
            Codec codecOut = null;
            for (final var stats : report.values()) {
                final Object type = stats.get("type");
                if ("transport".equals(type)) {
                    transportBytesSent += longOf(stats.get("bytesSent"));
                    transportBytesReceived += longOf(stats.get("bytesReceived"));
                    if (stats.get("selectedCandidatePairId") instanceof String id
                            && report.get(id) != null
                            && report.get(id).get("currentRoundTripTime") instanceof Number rtt) {
                        pairRtt = rtt.doubleValue();
                    }
                    continue;
                }
                if (!isAudio(stats)) {
                    continue;
                }
                if ("inbound-rtp".equals(type)) {
                    packetsReceived += longOf(stats.get("packetsReceived"));
                    // packetsLost бывает отрицательным из-за дубликатов пакетов
                    packetsLost += Math.max(0, longOf(stats.get("packetsLost")));
                    bytesReceived += longOf(stats.get("bytesReceived"));
                    jitter = Math.max(jitter, doubleOf(stats.get("jitter")));
                    jitterBufferDelay += Math.max(0, doubleOf(stats.get("jitterBufferDelay")));
                    jitterBufferEmitted += longOf(stats.get("jitterBufferEmittedCount"));
                    totalSamples += longOf(stats.get("totalSamplesReceived"));
                    concealedSamples += longOf(stats.get("concealedSamples"));
                    if (codecIn == null) {
                        codecIn = codecOf(report.get(stats.get("codecId")));
                    }
                } else if ("outbound-rtp".equals(type)) {
                    packetsSent += longOf(stats.get("packetsSent"));
                    bytesSent += longOf(stats.get("bytesSent"));
                    if (codecOut == null) {
                        codecOut = codecOf(report.get(stats.get("codecId")));
                    }
                } else if ("remote-inbound-rtp".equals(type)) {
                    remotePacketsLost += Math.max(0, longOf(stats.get("packetsLost")));
                    remoteJitter = Math.max(remoteJitter, doubleOf(stats.get("jitter")));
                    remoteFractionLost =
                            Math.max(remoteFractionLost, doubleOf(stats.get("fractionLost")));
                    remoteRtt = Math.max(remoteRtt, doubleOf(stats.get("roundTripTime")));
                }
            }
            return new Counters(
                    packetsReceived,
                    packetsLost,
                    bytesReceived,
                    jitter,
                    jitterBufferDelay,
                    jitterBufferEmitted,
                    totalSamples,
                    concealedSamples,
                    packetsSent,
                    bytesSent,
                    remotePacketsLost,
                    remoteJitter,
                    remoteFractionLost,
                    remoteRtt,
                    transportBytesSent,
                    transportBytesReceived,
                    pairRtt,
                    codecIn,
                    codecOut);
        }

        boolean hasMedia() {
            return packetsReceived > 0 || packetsSent > 0;
        }

        // RTT пары ICE измеряется всегда, RTCP-оценка появляется только после отчетов собеседника
        long rttMillis() {
            if (pairRtt >= 0) {
                return Math.round(pairRtt * 1000);
            }
            return remoteRtt >= 0 ? Math.round(remoteRtt * 1000) : -1;
        }

        private static boolean isAudio(final Map<String, Object> stats) {
            return "audio".equals(stats.get("kind")) || "audio".equals(stats.get("mediaType"));
        }

        @Nullable
        private static Codec codecOf(@Nullable final Map<String, Object> stats) {
            if (stats == null || !(stats.get("mimeType") instanceof String mimeType)) {
                return null;
            }
            return new Codec(
                    mimeType,
                    longOf(stats.get("clockRate")),
                    longOf(stats.get("channels")),
                    stats.get("sdpFmtpLine") instanceof String fmtp ? fmtp : null);
        }
    }

    /** Точка временного ряда: показатели за интервал между двумя опросами getStats(). */
    public record Sample(
            long elapsedMillis,
            long rttMillis,
            double lossIn,
            double lossOut,
            long jitterInMillis,
            long jitterOutMillis,
            long bitrateIn,
            long bitrateOut,
            double concealed,
            double mosIn,
            double mosOut) {

        @Nullable
        static Sample between(
                final Counters from,
                final Counters to,
                final long intervalMillis,
                final long elapsedMillis) {
            final long received = to.packetsReceived - from.packetsReceived;
            final long sent = to.packetsSent - from.packetsSent;
            if (intervalMillis <= 0 || (received <= 0 && sent <= 0)) {
                return null;
            }
            final long lost = Math.max(0, to.packetsLost - from.packetsLost);
            final double lossIn = received > 0 ? 100.0 * lost / (received + lost) : -1;
            final double lossOut = to.remoteFractionLost >= 0 ? 100 * to.remoteFractionLost : -1;
            final long rtt = to.rttMillis();
            final long jitterIn = to.jitter >= 0 ? Math.round(to.jitter * 1000) : -1;
            final long jitterOut = to.remoteJitter >= 0 ? Math.round(to.remoteJitter * 1000) : -1;
            final long samples = to.totalSamples - from.totalSamples;
            final long concealedSamples = to.concealedSamples - from.concealedSamples;
            return new Sample(
                    elapsedMillis,
                    rtt,
                    round(lossIn),
                    round(lossOut),
                    jitterIn,
                    jitterOut,
                    bitrate(to.bytesReceived - from.bytesReceived, intervalMillis),
                    bitrate(to.bytesSent - from.bytesSent, intervalMillis),
                    samples > 0 ? round(100.0 * Math.max(0, concealedSamples) / samples) : -1,
                    lossIn >= 0 ? round(CallQuality.mos(rtt, jitterIn, lossIn)) : -1,
                    lossOut >= 0 ? round(CallQuality.mos(rtt, jitterOut, lossOut)) : -1);
        }

        /** Худшее из направлений: собеседников двое, и плохо слышать может любой. */
        public double mos() {
            if (mosIn >= 0 && mosOut >= 0) {
                return Math.min(mosIn, mosOut);
            }
            return Math.max(mosIn, mosOut);
        }

        public boolean isDegraded() {
            return lossIn > LOSS_THRESHOLD_PERCENT
                    || lossOut > LOSS_THRESHOLD_PERCENT
                    || rttMillis > RTT_THRESHOLD_MILLIS;
        }

        // сотых долей хватает для отчета, а JSON остается компактным
        private static double round(final double value) {
            return value < 0 ? -1 : Math.round(value * 100) / 100.0;
        }

        // байты за миллисекунды в битах дают кбит/с
        private static long bitrate(final long bytes, final long intervalMillis) {
            return bytes < 0 ? -1 : bytes * 8 / intervalMillis;
        }
    }

    public record Totals(
            long packetsReceived,
            long packetsLost,
            long packetsSent,
            long remotePacketsLost,
            long bytesSent,
            long bytesReceived,
            long totalSamples,
            long concealedSamples,
            long jitterBufferDelayMillis,
            long jitterBufferEmitted) {

        static Totals of(final Counters counters) {
            return new Totals(
                    counters.packetsReceived,
                    counters.packetsLost,
                    counters.packetsSent,
                    counters.remotePacketsLost,
                    counters.transportBytesSent,
                    counters.transportBytesReceived,
                    counters.totalSamples,
                    counters.concealedSamples,
                    Math.round(counters.jitterBufferDelay * 1000),
                    counters.jitterBufferEmitted);
        }

        public double lossIn() {
            final long expected = packetsReceived + packetsLost;
            return expected > 0 ? 100.0 * packetsLost / expected : -1;
        }

        public double lossOut() {
            return packetsSent > 0 ? 100.0 * remotePacketsLost / packetsSent : -1;
        }

        public double concealed() {
            return totalSamples > 0 ? 100.0 * concealedSamples / totalSamples : -1;
        }

        /** Средняя задержка звука в jitter-буфере. */
        public long jitterBufferMillis() {
            return jitterBufferEmitted > 0 ? jitterBufferDelayMillis / jitterBufferEmitted : -1;
        }
    }

    public record Stats(int count, double min, double average, double max, double p95) {

        /** null, если значений нет; отрицательные значения считаются неизвестными. */
        @Nullable
        public static Stats of(final double[] values) {
            final double[] known = Arrays.stream(values).filter(v -> v >= 0).sorted().toArray();
            if (known.length == 0) {
                return null;
            }
            final double average = Arrays.stream(known).average().orElse(0);
            // p95 по методу ближайшего ранга
            final int rank = (int) Math.ceil(0.95 * known.length) - 1;
            return new Stats(
                    known.length,
                    known[0],
                    average,
                    known[known.length - 1],
                    known[Math.max(0, rank)]);
        }
    }

    public record Report(
            @Nullable Codec codecIn,
            @Nullable Codec codecOut,
            Totals totals,
            List<Sample> samples,
            @Nullable Sample live) {

        @Nullable
        public Stats stats(final ToDoubleFunction<Sample> metric) {
            return Stats.of(samples.stream().mapToDouble(metric).toArray());
        }

        public double mosIn() {
            return averageOrLive(Sample::mosIn);
        }

        public double mosOut() {
            return averageOrLive(Sample::mosOut);
        }

        public double mos() {
            final double in = mosIn();
            final double out = mosOut();
            return in >= 0 && out >= 0 ? Math.min(in, out) : Math.max(in, out);
        }

        @Nullable
        public Rating rating() {
            return CallQuality.rating(mos());
        }

        /** Направление, в котором слышно хуже; NONE при хорошем качестве или без данных. */
        public Problem problem() {
            final double in = mosIn();
            final double out = mosOut();
            final boolean badIn = in >= 0 && in < GOOD_MOS;
            final boolean badOut = out >= 0 && out < GOOD_MOS;
            if (badIn && badOut) {
                if (Math.abs(in - out) < DIRECTION_MOS_DELTA) {
                    return Problem.BOTH;
                }
                return in < out ? Problem.INCOMING : Problem.OUTGOING;
            } else if (badIn) {
                return Problem.INCOMING;
            } else if (badOut) {
                return Problem.OUTGOING;
            }
            return Problem.NONE;
        }

        private double averageOrLive(final ToDoubleFunction<Sample> metric) {
            final var stats = stats(metric);
            if (stats != null) {
                return stats.average();
            }
            return live == null ? -1 : metric.applyAsDouble(live);
        }
    }

    private final List<Sample> samples = new ArrayList<>();
    private Counters lastPolled;
    private long lastPolledAt;
    private Counters lastRecorded;
    private long lastRecordedAt;
    private Sample live;
    private final ArrayDeque<Sample> recent = new ArrayDeque<>();
    private int stride = 1;
    private long ticks = 0;
    private boolean degraded = false;

    /**
     * Учитывает очередной отчет. record - плановый опрос для временного ряда, остальные опросы
     * обновляют только текущие показатели. Возвращает добавленную точку ряда.
     */
    @Nullable
    Sample update(final Counters counters, final long elapsedMillis, final boolean record) {
        if (lastPolled != null && elapsedMillis - lastPolledAt >= MIN_LIVE_INTERVAL_MILLIS) {
            final var sample =
                    Sample.between(
                            lastPolled, counters, elapsedMillis - lastPolledAt, elapsedMillis);
            if (sample != null) {
                this.live = sample;
                recent.addLast(sample);
                while (elapsedMillis - recent.getFirst().elapsedMillis() > RECENT_WINDOW_MILLIS) {
                    recent.removeFirst();
                }
            }
        }
        if (lastPolled == null || elapsedMillis - lastPolledAt >= MIN_LIVE_INTERVAL_MILLIS) {
            this.lastPolled = counters;
            this.lastPolledAt = elapsedMillis;
        }
        if (!record) {
            return null;
        }
        if (lastRecorded == null) {
            this.lastRecorded = counters;
            this.lastRecordedAt = elapsedMillis;
            return null;
        }
        // длинный звонок прореживается: ряд хранит не больше MAX_SAMPLES точек
        if (++ticks % stride != 0) {
            return null;
        }
        final var sample =
                Sample.between(
                        lastRecorded, counters, elapsedMillis - lastRecordedAt, elapsedMillis);
        this.lastRecorded = counters;
        this.lastRecordedAt = elapsedMillis;
        if (sample == null) {
            return null;
        }
        samples.add(sample);
        if (samples.size() >= MAX_SAMPLES) {
            final List<Sample> kept = new ArrayList<>();
            for (int i = 0; i < samples.size(); i += 2) {
                kept.add(samples.get(i));
            }
            samples.clear();
            samples.addAll(kept);
            stride *= 2;
        }
        this.degraded = sample.isDegraded();
        return sample;
    }

    boolean isDegraded() {
        return degraded;
    }

    @Nullable
    Sample live() {
        return live;
    }

    /** Текущие показатели за последнюю минуту, от старых к новым. */
    List<Sample> recent() {
        return ImmutableList.copyOf(recent);
    }

    @Nullable
    Report report() {
        if (lastPolled == null || !lastPolled.hasMedia()) {
            return null;
        }
        return new Report(
                lastPolled.codecIn,
                lastPolled.codecOut,
                Totals.of(lastPolled),
                ImmutableList.copyOf(samples),
                live);
    }

    /** Упрощенная E-model: задержка в одну сторону, jitter-буфер и потери снижают R-фактор. */
    public static double mos(final long rttMillis, final long jitterMillis, final double loss) {
        final double latency = Math.max(0, rttMillis) / 2.0 + Math.max(0, jitterMillis) * 2.0 + 10;
        double r = latency < 160 ? 93.2 - latency / 40 : 93.2 - (latency - 120) / 10;
        r -= 2.5 * Math.max(0, loss);
        r = Math.max(0, Math.min(100, r));
        final double mos = 1 + 0.035 * r + 0.000007 * r * (r - 60) * (100 - r);
        return Math.max(1, Math.min(4.5, mos));
    }

    @Nullable
    public static Rating rating(final double mos) {
        if (mos < 0) {
            return null;
        } else if (mos >= GOOD_MOS) {
            return Rating.GOOD;
        } else if (mos >= FAIR_MOS) {
            return Rating.FAIR;
        }
        return Rating.POOR;
    }

    /** Технические параметры для хронологии событий: потери в процентах и RTT в миллисекундах. */
    static String describe(final Sample sample) {
        return String.format(
                Locale.ROOT, "%.1f;%.1f;%d", sample.lossIn(), sample.lossOut(), sample.rttMillis());
    }

    private static long longOf(final Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static double doubleOf(final Object value) {
        return value instanceof Number number ? number.doubleValue() : -1;
    }
}
