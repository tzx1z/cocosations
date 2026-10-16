package eu.siacs.conversations.ui.util;

import android.content.Context;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import eu.siacs.conversations.BuildConfig;
import eu.siacs.conversations.R;
import eu.siacs.conversations.utils.TimeFrameUtils;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.jingle.CallDiagnostics;
import eu.siacs.conversations.xmpp.jingle.CallQuality;
import eu.siacs.conversations.xmpp.jingle.CallSignaling;
import eu.siacs.conversations.xmpp.jingle.CallStage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Отчет о звонке, разбитый на разделы: из него строятся экран и текст для копирования. */
public final class CallReport {

    private static final int MAX_PAIRS = 20;

    public enum Status {
        NEUTRAL,
        OK,
        WARNING,
        ERROR
    }

    public sealed interface Row permits Field, Text, Table, Chart {}

    public record Field(String label, String value, Status status) implements Row {}

    /** Строка моноширинным шрифтом: кандидаты, пары, хронология. */
    public record Text(String text) implements Row {}

    public record Table(List<String> header, List<List<String>> rows) implements Row {}

    /** График временного ряда; в текстовом отчете его заменяет таблица показателей. */
    public record Chart(List<CallQuality.Sample> samples) implements Row {}

    /** brief - раздел виден в кратком режиме; collapsed - свернут при первом показе. */
    public record Section(
            String key,
            String title,
            Status status,
            boolean brief,
            boolean collapsed,
            List<Row> rows) {}

    private final String header;
    private final String recommendation;
    private final List<Section> sections;

    private CallReport(
            final String header, final String recommendation, final List<Section> sections) {
        this.header = header;
        this.recommendation = recommendation;
        this.sections = sections;
    }

    public List<Section> sections() {
        return sections;
    }

    public String toText() {
        final var builder = new StringBuilder(header).append('\n');
        builder.append(recommendation).append('\n');
        for (final var section : sections) {
            builder.append('\n').append(section.title()).append('\n');
            for (final var row : section.rows()) {
                switch (row) {
                    case Field field ->
                            builder.append("  ")
                                    .append(field.label())
                                    .append(": ")
                                    .append(field.value())
                                    .append('\n');
                    case Text text -> builder.append("  ").append(text.text()).append('\n');
                    case Table table -> appendTable(builder, table);
                    case Chart ignored -> {}
                }
            }
        }
        return builder.toString();
    }

    private static void appendTable(final StringBuilder builder, final Table table) {
        final int columns = table.header().size();
        final int[] widths = new int[columns];
        final List<List<String>> all = new ArrayList<>();
        all.add(table.header());
        all.addAll(table.rows());
        for (final var row : all) {
            for (int i = 0; i < columns && i < row.size(); i++) {
                widths[i] = Math.max(widths[i], row.get(i).length());
            }
        }
        for (final var row : all) {
            builder.append("  ");
            for (int i = 0; i < columns && i < row.size(); i++) {
                final String cell = row.get(i);
                if (i == 0) {
                    builder.append(Strings.padEnd(cell, widths[i], ' '));
                } else {
                    builder.append("  ").append(Strings.padStart(cell, widths[i], ' '));
                }
            }
            builder.append('\n');
        }
    }

    public static Status status(@Nullable final CallQuality.Rating rating) {
        if (rating == null) {
            return Status.NEUTRAL;
        }
        return switch (rating) {
            case GOOD -> Status.OK;
            case FAIR -> Status.WARNING;
            case POOR -> Status.ERROR;
        };
    }

    public static CallReport of(final Context context, final CallDiagnostics.Snapshot snapshot) {
        final var builder = new Builder(context);
        summary(builder, context, snapshot);
        stages(builder, context, snapshot);
        signaling(builder, context, snapshot);
        if (snapshot.peer() != null) {
            peer(builder, context, snapshot);
        }
        if (snapshot.quality() != null) {
            quality(builder, context, snapshot.quality());
        }
        ice(builder, context, snapshot);
        candidates(
                builder,
                context,
                "local_candidates",
                R.string.cocosations_call_report_local_candidates,
                snapshot.localCandidates());
        candidates(
                builder,
                context,
                "remote_candidates",
                R.string.cocosations_call_report_remote_candidates,
                snapshot.remoteCandidates());
        pairs(builder, context, snapshot.pairStats());
        timeline(builder, context, snapshot);
        return new CallReport(
                BuildConfig.APP_NAME + " " + BuildConfig.VERSION_NAME,
                context.getString(R.string.cocosations_call_report_recommendation)
                        + ": "
                        + context.getString(CallReportFormat.suggestion(snapshot.suggestion())),
                builder.build());
    }

    private static Status resultStatus(final CallDiagnostics.Snapshot snapshot) {
        if (snapshot.stage().isFailure()) {
            return Status.ERROR;
        }
        return snapshot.connectedMillis() > 0 ? Status.OK : Status.NEUTRAL;
    }

    private static void summary(
            final Builder builder, final Context context, final CallDiagnostics.Snapshot snapshot) {
        builder.section("summary", R.string.cocosations_call_report_summary, true, false);
        builder.field(R.string.cocosations_call_report_contact, snapshot.with());
        builder.field(
                R.string.cocosations_call_report_direction,
                context.getString(
                        snapshot.direction() == CallDiagnostics.Direction.OUTGOING
                                ? R.string.cocosations_call_report_outgoing
                                : R.string.cocosations_call_report_incoming));
        if (!snapshot.media().isEmpty()) {
            builder.field(
                    R.string.cocosations_call_report_media,
                    CallStageBar.media(context, snapshot.media()));
        }
        builder.field(
                R.string.cocosations_call_report_started,
                DateUtils.formatDateTime(
                        context,
                        snapshot.startedAt(),
                        DateUtils.FORMAT_SHOW_DATE
                                | DateUtils.FORMAT_SHOW_YEAR
                                | DateUtils.FORMAT_SHOW_TIME));
        String duration = TimeFrameUtils.formatElapsedTime(snapshot.durationMillis(), false);
        if (snapshot.connectedMillis() > 0) {
            duration +=
                    ", "
                            + context.getString(
                                    R.string.cocosations_call_report_connected_for,
                                    TimeFrameUtils.formatElapsedTime(
                                            snapshot.connectedMillis(), false));
        }
        builder.field(R.string.cocosations_call_report_duration, duration);
        final Status result = resultStatus(snapshot);
        builder.field(R.string.cocosations_call_report_result, result(context, snapshot), result);
        builder.status(result);
        if (snapshot.stage().isFailure()) {
            builder.field(
                    R.string.cocosations_call_report_failed_stage,
                    context.getString(CallStageBar.segmentLabel(snapshot.stage().getSegment()))
                            + " - "
                            + CallStageBar.reason(context, snapshot.reason()),
                    Status.ERROR);
            if (snapshot.condition() != null) {
                builder.field(R.string.cocosations_call_report_error, snapshot.condition());
            }
        }
        if (snapshot.endReason() != null) {
            builder.field(
                    R.string.cocosations_call_report_end_reason,
                    CallReportFormat.endReason(context, snapshot.endReason()));
        }
        String federation =
                context.getString(
                        snapshot.federated()
                                ? R.string.cocosations_call_report_federation_used
                                : R.string.cocosations_call_report_federation_not_needed);
        if (snapshot.federated() && snapshot.federationEvidence() != null) {
            federation =
                    context.getString(
                            R.string.cocosations_call_report_federation_confirmed,
                            federation,
                            context.getString(
                                    CallReportFormat.federationEvidence(
                                            snapshot.federationEvidence())));
        }
        builder.field(R.string.cocosations_call_report_federation, federation);
        if (snapshot.route() != null) {
            builder.field(
                    R.string.cocosations_call_report_route,
                    CallStageBar.route(context, snapshot.route()));
        }
        if (snapshot.selectedRttMillis() >= 0) {
            builder.field(
                    R.string.cocosations_call_report_rtt,
                    context.getString(
                            R.string.cocosations_call_report_milliseconds,
                            snapshot.selectedRttMillis()),
                    snapshot.selectedRttMillis() > CallQuality.RTT_THRESHOLD_MILLIS
                            ? Status.WARNING
                            : Status.NEUTRAL);
        }
        final var quality = snapshot.quality();
        if (quality != null && quality.rating() != null) {
            builder.field(
                    R.string.cocosations_call_report_quality_rating,
                    CallReportFormat.rating(context, quality),
                    status(quality.rating()));
            if (quality.problem() != CallQuality.Problem.NONE) {
                builder.field(
                        R.string.cocosations_call_report_problem,
                        context.getString(CallReportFormat.problem(quality.problem())),
                        Status.WARNING);
            }
        }
        if (quality != null) {
            builder.field(R.string.cocosations_call_report_traffic, traffic(context, quality));
        }
    }

    private static void stages(
            final Builder builder, final Context context, final CallDiagnostics.Snapshot snapshot) {
        builder.section("stages", R.string.cocosations_call_report_stages, false, false);
        for (final var transition : snapshot.transitions()) {
            final CallStage stage = transition.stage();
            final String name =
                    stage.isFailure()
                            ? context.getString(CallStageBar.segmentLabel(stage.getSegment()))
                                    + ": "
                                    + context.getString(R.string.cocosations_call_report_failure)
                            : context.getString(CallStageBar.stageTitle(stage));
            builder.timed(context, snapshot.startedAt(), transition.elapsedMillis(), name);
            if (stage.isFailure()) {
                builder.status(Status.ERROR);
            }
        }
    }

    private static void signaling(
            final Builder builder, final Context context, final CallDiagnostics.Snapshot snapshot) {
        builder.section("signaling", R.string.cocosations_call_report_signaling, false, false);
        final var delays = CallSignaling.delays(snapshot);
        if (delays.isEmpty()) {
            builder.text(context.getString(R.string.cocosations_call_report_no_signaling));
        }
        for (final var delay : delays) {
            builder.field(
                    context.getString(CallReportFormat.step(delay.step())),
                    CallReportFormat.delay(context, delay.millis()),
                    Status.NEUTRAL);
        }
    }

    private static void peer(
            final Builder builder, final Context context, final CallDiagnostics.Snapshot snapshot) {
        final var peer = snapshot.peer();
        builder.section("peer", R.string.cocosations_call_report_peer, false, false);
        builder.field(R.string.cocosations_call_report_peer_address, peer.address());
        builder.field(
                R.string.cocosations_call_report_peer_identity,
                CallReportFormat.peerIdentity(context, peer));
        builder.field(
                R.string.cocosations_call_report_peer_software,
                CallReportFormat.peerSoftware(context, peer));
        if (peer.capsKnown()) {
            for (final String feature : CallDiagnostics.PEER_FEATURES) {
                builder.field(
                        CallReportFormat.feature(context, feature),
                        context.getString(
                                peer.hasFeature(feature)
                                        ? R.string.cocosations_call_report_yes
                                        : R.string.cocosations_call_report_no),
                        Status.NEUTRAL);
            }
            builder.field(
                    R.string.cocosations_call_report_trickle,
                    peer.hasFeature(Namespace.SDP_OFFER_ANSWER)
                            ? context.getString(R.string.cocosations_call_report_trickle_no)
                            : context.getString(R.string.cocosations_call_report_yes));
        }
        builder.field(
                R.string.cocosations_call_report_ice_restarts,
                String.valueOf(
                        snapshot.events().stream()
                                .filter(e -> e.type() == CallDiagnostics.EventType.ICE_RESTART)
                                .count()));
    }

    private static void quality(
            final Builder builder, final Context context, final CallQuality.Report quality) {
        builder.section("quality", R.string.cocosations_call_report_quality, false, false);
        builder.status(status(quality.rating()));
        if (quality.rating() != null) {
            builder.field(
                    R.string.cocosations_call_report_mos_directions,
                    context.getString(
                            R.string.cocosations_call_report_mos_directions_value,
                            CallReportFormat.mos(quality.mosIn()),
                            CallReportFormat.mos(quality.mosOut())));
        }
        if (quality.codecIn() != null) {
            builder.field(
                    R.string.cocosations_call_report_codec_in,
                    CallReportFormat.codec(context, quality.codecIn()));
        }
        if (quality.codecOut() != null) {
            builder.field(
                    R.string.cocosations_call_report_codec_out,
                    CallReportFormat.codec(context, quality.codecOut()));
        }
        final var totals = quality.totals();
        builder.field(
                R.string.cocosations_call_report_incoming_stream,
                context.getString(
                        R.string.cocosations_call_report_packets_received,
                        totals.packetsReceived(),
                        totals.packetsLost(),
                        CallReportFormat.percent(context, totals.lossIn())),
                lossStatus(totals.lossIn()));
        builder.field(
                R.string.cocosations_call_report_outgoing_stream,
                context.getString(
                        R.string.cocosations_call_report_packets_sent,
                        totals.packetsSent(),
                        totals.remotePacketsLost(),
                        CallReportFormat.percent(context, totals.lossOut())),
                lossStatus(totals.lossOut()));
        if (totals.concealed() >= 0) {
            builder.field(
                    R.string.cocosations_call_report_concealed,
                    CallReportFormat.percent(context, totals.concealed()));
        }
        if (totals.jitterBufferMillis() >= 0) {
            builder.field(
                    R.string.cocosations_call_report_jitter_buffer,
                    context.getString(
                            R.string.cocosations_call_report_milliseconds,
                            totals.jitterBufferMillis()));
        }
        builder.field(R.string.cocosations_call_report_traffic, traffic(context, quality));
        if (quality.samples().size() > 1) {
            builder.row(new Chart(quality.samples()));
        }
        final List<List<String>> rows = new ArrayList<>();
        for (final var metric : CallQualityMetric.values()) {
            final var stats = quality.stats(metric.value);
            if (stats != null) {
                rows.add(
                        List.of(
                                context.getString(metric.label),
                                metric.format(stats.min()),
                                metric.format(stats.average()),
                                metric.format(stats.max()),
                                metric.format(stats.p95())));
            }
        }
        if (rows.isEmpty()) {
            builder.text(context.getString(R.string.cocosations_call_report_no_quality_data));
        } else {
            builder.row(
                    new Table(
                            List.of(
                                    context.getString(R.string.cocosations_call_report_metric),
                                    context.getString(R.string.cocosations_call_report_min),
                                    context.getString(R.string.cocosations_call_report_avg),
                                    context.getString(R.string.cocosations_call_report_max),
                                    "p95"),
                            rows));
        }
    }

    private static Status lossStatus(final double loss) {
        return loss > CallQuality.LOSS_THRESHOLD_PERCENT ? Status.WARNING : Status.NEUTRAL;
    }

    private static String traffic(final Context context, final CallQuality.Report quality) {
        return context.getString(
                R.string.cocosations_call_report_traffic_value,
                Formatter.formatShortFileSize(context, quality.totals().bytesSent()),
                Formatter.formatShortFileSize(context, quality.totals().bytesReceived()));
    }

    private static void ice(
            final Builder builder, final Context context, final CallDiagnostics.Snapshot snapshot) {
        builder.section("ice", R.string.cocosations_call_report_ice, false, false);
        if (snapshot.stage() == CallStage.FAILED_ICE) {
            builder.status(Status.ERROR);
        }
        builder.field(R.string.cocosations_call_report_ice_state, snapshot.iceConnectionState());
        builder.field(
                R.string.cocosations_call_report_peer_connection, snapshot.peerConnectionState());
        builder.field(R.string.cocosations_call_report_ice_gathering, snapshot.iceGatheringState());
        final var discovery = snapshot.iceServerDiscovery();
        if (discovery != null) {
            builder.field(
                    R.string.cocosations_call_report_external_services,
                    CallReportFormat.externalServices(context, discovery),
                    switch (discovery.status()) {
                        case OK -> Status.NEUTRAL;
                        case ERROR -> Status.ERROR;
                        default -> Status.WARNING;
                    });
            if (discovery.expiresAt() > 0) {
                builder.field(
                        R.string.cocosations_call_report_credentials,
                        CallReportFormat.credentialsExpiry(context, discovery.expiresAt()),
                        discovery.expiresAt() < snapshot.startedAt()
                                ? Status.WARNING
                                : Status.NEUTRAL);
            }
        }
        String iceServers =
                context.getString(
                        R.string.cocosations_call_report_ice_servers_value,
                        snapshot.stunServers(),
                        snapshot.turnServers());
        if (snapshot.forceRelay()) {
            iceServers += ", " + context.getString(R.string.cocosations_call_report_relay_only);
        }
        builder.field(R.string.cocosations_call_report_ice_servers, iceServers);
        for (final var iceServer : snapshot.iceServers()) {
            builder.text(iceServer.url());
        }
        if (!snapshot.iceCandidateErrors().isEmpty()) {
            builder.field(
                    R.string.cocosations_call_report_ice_errors,
                    String.valueOf(snapshot.iceCandidateErrors().size()),
                    Status.WARNING);
            for (final var error : snapshot.iceCandidateErrors()) {
                builder.text(
                        error.url()
                                + ": "
                                + error.code()
                                + " "
                                + error.text()
                                + (error.count() > 1 ? " (x" + error.count() + ")" : ""));
            }
        }
        builder.field(
                R.string.cocosations_call_report_turn,
                context.getString(
                        snapshot.hasLocalRelay()
                                ? R.string.cocosations_call_report_yes
                                : R.string.cocosations_call_report_no));
        String nat = context.getString(CallReportFormat.natType(snapshot.natType()));
        if (snapshot.carrierGradeNat()) {
            nat += ", " + context.getString(R.string.cocosations_call_report_cgnat);
        }
        builder.field(R.string.cocosations_call_report_nat, nat);
        if (snapshot.selectedLocal() != null || snapshot.selectedRemote() != null) {
            builder.field(
                    R.string.cocosations_call_report_selected_pair,
                    snapshot.selectedLocal() + " -> " + snapshot.selectedRemote());
        }
    }

    private static void candidates(
            final Builder builder,
            final Context context,
            final String key,
            @StringRes final int title,
            final List<CallDiagnostics.Candidate> candidates) {
        int host = 0;
        int srflx = 0;
        int prflx = 0;
        int relay = 0;
        for (final var candidate : candidates) {
            switch (candidate.type()) {
                case "host" -> host++;
                case "srflx" -> srflx++;
                case "prflx" -> prflx++;
                case "relay" -> relay++;
                default -> {}
            }
        }
        builder.section(
                key,
                String.format(
                        Locale.ROOT,
                        "%s (host %d, srflx %d, prflx %d, relay %d)",
                        context.getString(title),
                        host,
                        srflx,
                        prflx,
                        relay),
                false,
                true);
        if (candidates.isEmpty()) {
            builder.text(context.getString(R.string.cocosations_call_report_none));
        }
        for (final var candidate : candidates) {
            builder.text(candidate.toString());
        }
    }

    // при непрерывном сборе кандидатов пар бывают десятки; в отчет идут самые информативные
    private static void pairs(
            final Builder builder,
            final Context context,
            final List<CallDiagnostics.PairStats> pairs) {
        builder.section("pairs", R.string.cocosations_call_report_pairs, false, true);
        if (pairs.isEmpty()) {
            builder.text(context.getString(R.string.cocosations_call_report_none));
            return;
        }
        for (final var pair : pairs.subList(0, Math.min(pairs.size(), MAX_PAIRS))) {
            final var details = new StringBuilder(pair.selected() ? "* " : "");
            details.append(pair.local()).append(" -> ").append(pair.remote());
            details.append("\n    ").append(pair.state());
            if (pair.nominated()) {
                details.append(", nominated");
            }
            if (pair.rttMillis() >= 0) {
                details.append(", rtt ").append(pair.rttMillis()).append(" ms");
            }
            details.append(", requests ")
                    .append(pair.requestsSent())
                    .append(" / responses ")
                    .append(pair.responsesReceived());
            builder.text(details.toString());
        }
        if (pairs.size() > MAX_PAIRS) {
            final int more = pairs.size() - MAX_PAIRS;
            builder.text(
                    context.getResources()
                            .getQuantityString(
                                    R.plurals.cocosations_call_report_more_pairs, more, more));
        }
    }

    private static void timeline(
            final Builder builder, final Context context, final CallDiagnostics.Snapshot snapshot) {
        builder.section("timeline", R.string.cocosations_call_report_timeline, false, true);
        for (final var event : snapshot.events()) {
            if (event.type() == CallDiagnostics.EventType.QUALITY_DEGRADED
                    || event.type() == CallDiagnostics.EventType.ICE_CANDIDATE_ERROR) {
                builder.status(Status.WARNING);
            }
            builder.timed(
                    context,
                    snapshot.startedAt(),
                    event.elapsedMillis(),
                    CallReportFormat.flowMark(event.type().flow)
                            + " "
                            + CallReportFormat.event(context, event));
        }
    }

    public static String result(final Context context, final CallDiagnostics.Snapshot snapshot) {
        if (snapshot.stage().isFailure()) {
            return context.getString(R.string.cocosations_call_report_failure);
        }
        if (!snapshot.ended()) {
            return context.getString(R.string.cocosations_call_report_in_progress);
        }
        return CallStageBar.describe(context, snapshot);
    }

    public static class Builder {

        private final Context context;
        private final List<Section> sections = new ArrayList<>();
        private String key;
        private String title;
        private Status status;
        private boolean brief;
        private boolean collapsed;
        private List<Row> rows;

        public Builder(final Context context) {
            this.context = context;
        }

        public void section(
                final String key,
                @StringRes final int title,
                final boolean brief,
                final boolean collapsed) {
            section(key, context.getString(title), brief, collapsed);
        }

        public void section(
                final String key,
                final String title,
                final boolean brief,
                final boolean collapsed) {
            flush();
            this.key = key;
            this.title = title;
            this.status = Status.NEUTRAL;
            this.brief = brief;
            this.collapsed = collapsed;
            this.rows = new ArrayList<>();
        }

        /** Статус раздела - худший из отмеченных. */
        public void status(final Status status) {
            if (status.compareTo(this.status) > 0) {
                this.status = status;
            }
        }

        public void row(final Row row) {
            rows.add(row);
        }

        public void field(@StringRes final int label, @Nullable final String value) {
            field(context.getString(label), value, Status.NEUTRAL);
        }

        public void field(
                @StringRes final int label, @Nullable final String value, final Status status) {
            field(context.getString(label), value, status);
        }

        public void field(final String label, @Nullable final String value, final Status status) {
            rows.add(new Field(label, value == null ? "-" : value, status));
        }

        public void text(final String text) {
            rows.add(new Text(text));
        }

        private void timed(
                final Context context,
                final long startedAt,
                final long elapsedMillis,
                final String text) {
            text(
                    CallReportFormat.elapsed(context, elapsedMillis)
                            + "  "
                            + CallReportFormat.clock(startedAt + elapsedMillis)
                            + "  "
                            + text);
        }

        private List<Section> build() {
            flush();
            return ImmutableList.copyOf(sections);
        }

        public CallReport build(final String header, final String recommendation) {
            return new CallReport(header, recommendation, build());
        }

        private void flush() {
            if (rows != null) {
                sections.add(
                        new Section(
                                key, title, status, brief, collapsed, ImmutableList.copyOf(rows)));
                rows = null;
            }
        }
    }
}
