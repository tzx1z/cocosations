package eu.siacs.conversations.ui.util;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import com.google.android.material.color.MaterialColors;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.CocosationsCallStagesBinding;
import eu.siacs.conversations.xmpp.jingle.CallDiagnostics;
import eu.siacs.conversations.xmpp.jingle.CallStage;
import eu.siacs.conversations.xmpp.jingle.Media;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Прогресс-бар этапов звонка: пройденные зеленые, текущий пульсирует, этап сбоя красный. */
public class CallStageBar {

    private static final long PULSE_DURATION_MILLIS = 700;

    private final CocosationsCallStagesBinding binding;
    private final View[] segments;
    private final TextView[] labels;

    private CallDiagnostics rendered;
    private int renderedVersion = -1;
    private ObjectAnimator pulse;
    private View pulsing;

    public CallStageBar(final CocosationsCallStagesBinding binding) {
        this.binding = binding;
        this.segments =
                new View[] {
                    binding.segmentServer,
                    binding.segmentFederation,
                    binding.segmentPeer,
                    binding.segmentMedia
                };
        this.labels =
                new TextView[] {
                    binding.labelServer,
                    binding.labelFederation,
                    binding.labelPeer,
                    binding.labelMedia
                };
    }

    public void render(@Nullable final CallDiagnostics diagnostics) {
        // входящий звонок до ответа пользователя еще не устанавливается
        if (diagnostics == null || !diagnostics.isReportWorthy()) {
            hide();
            return;
        }
        // UI опрашивает отчет по таймеру; перерисовываем только при изменениях
        final int version = diagnostics.getVersion();
        if (diagnostics == rendered && version == renderedVersion) {
            return;
        }
        this.rendered = diagnostics;
        this.renderedVersion = version;
        render(diagnostics.snapshot());
    }

    public void render(final CallDiagnostics.Snapshot snapshot) {
        final View root = binding.getRoot();
        final Context context = root.getContext();
        final int done = ContextCompat.getColor(context, R.color.cocosations_status_ok);
        final int failed = MaterialColors.getColor(root, androidx.appcompat.R.attr.colorError);
        final int current = MaterialColors.getColor(root, androidx.appcompat.R.attr.colorPrimary);
        final int pending =
                MaterialColors.getColor(
                        root, com.google.android.material.R.attr.colorSurfaceContainerHighest);
        final int label =
                MaterialColors.getColor(
                        root, com.google.android.material.R.attr.colorOnSurfaceVariant);
        final CallStage stage = snapshot.stage();
        final boolean failure = stage.isFailure();
        // этапы до этапа сбоя тоже пройдены: ошибку вернул уже следующий участник цепочки
        final int passed =
                failure
                        ? Math.max(snapshot.reached().getSegment(), stage.getSegment())
                        : snapshot.reached().getSegment();
        View active = null;
        for (int i = 0; i < CallStage.SEGMENT_COUNT; i++) {
            final int color;
            if (failure && i == stage.getSegment()) {
                color = failed;
            } else if (i < passed) {
                color = done;
            } else if (!failure && !snapshot.ended() && i == stage.getSegment()) {
                color = current;
                active = segments[i];
            } else {
                color = pending;
            }
            segments[i].setBackgroundTintList(ColorStateList.valueOf(color));
            labels[i].setTextColor(failure && i == stage.getSegment() ? failed : label);
        }
        pulse(active);
        binding.detail.setText(describe(context, snapshot));
        binding.detail.setTextColor(failure ? failed : label);
        root.setVisibility(View.VISIBLE);
    }

    public void hide() {
        pulse(null);
        this.rendered = null;
        this.renderedVersion = -1;
        binding.getRoot().setVisibility(View.GONE);
    }

    /** Останавливает анимацию; следующий render() отрисует бар заново. */
    public void release() {
        pulse(null);
        this.renderedVersion = -1;
    }

    private void pulse(@Nullable final View segment) {
        if (segment == pulsing) {
            return;
        }
        if (pulse != null) {
            pulse.cancel();
            pulsing.setAlpha(1f);
            pulse = null;
            pulsing = null;
        }
        if (segment == null) {
            return;
        }
        pulse = ObjectAnimator.ofFloat(segment, View.ALPHA, 1f, 0.3f);
        pulse.setDuration(PULSE_DURATION_MILLIS);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.start();
        pulsing = segment;
    }

    public static String describe(final Context context, final CallDiagnostics.Snapshot snapshot) {
        final CallStage stage = snapshot.stage();
        if (stage.isFailure()) {
            final String reason = reason(context, snapshot.reason());
            return context.getString(
                    R.string.cocosations_call_failed_at,
                    context.getString(segmentLabel(stage.getSegment())),
                    snapshot.condition() == null
                            ? reason
                            : reason + " (" + snapshot.condition() + ")");
        }
        if (snapshot.ended()) {
            final String ended =
                    context.getString(
                            snapshot.termination() == CallDiagnostics.Termination.LOCAL
                                    ? R.string.cocosations_call_ended_local
                                    : R.string.cocosations_call_ended_remote);
            return stage == CallStage.CONNECTED
                    ? ended
                    : context.getString(
                            R.string.cocosations_call_ended_at,
                            ended,
                            context.getString(stageTitle(stage)));
        }
        if (stage == CallStage.CONNECTED && snapshot.route() != null) {
            return context.getString(
                    R.string.cocosations_call_stage_connected_via,
                    context.getString(stageTitle(stage)),
                    route(context, snapshot.route()));
        }
        return context.getString(stageTitle(stage));
    }

    public static String route(final Context context, final CallDiagnostics.Route route) {
        return context.getString(
                switch (route) {
                    case DIRECT -> R.string.cocosations_route_direct;
                    case LOCAL_NETWORK -> R.string.cocosations_route_local_network;
                    case LOCAL_RELAY -> R.string.cocosations_route_local_relay;
                    case REMOTE_RELAY -> R.string.cocosations_route_remote_relay;
                    case BOTH_RELAYS -> R.string.cocosations_route_both_relays;
                });
    }

    public static String media(final Context context, final Set<Media> media) {
        final List<String> names = new ArrayList<>();
        if (media.contains(Media.AUDIO)) {
            names.add(context.getString(R.string.cocosations_media_audio));
        }
        if (media.contains(Media.VIDEO)) {
            names.add(context.getString(R.string.cocosations_media_video));
        }
        return String.join(", ", names);
    }

    @StringRes
    public static int segmentLabel(final int segment) {
        return switch (segment) {
            case 0 -> R.string.cocosations_call_stage_server;
            case 1 -> R.string.cocosations_call_stage_federation;
            case 2 -> R.string.cocosations_call_stage_peer;
            default -> R.string.cocosations_call_stage_media;
        };
    }

    @StringRes
    public static int stageTitle(final CallStage stage) {
        return switch (stage) {
            case CONNECTING_TO_SERVER, FAILED_SERVER ->
                    R.string.cocosations_call_stage_connecting_to_server;
            case RESOLVING_REMOTE_SERVER, FAILED_FEDERATION ->
                    R.string.cocosations_call_stage_resolving_remote_server;
            case WAITING_FOR_PEER, FAILED_DELIVERY ->
                    R.string.cocosations_call_stage_waiting_for_peer;
            case ICE_NEGOTIATION, FAILED_ICE -> R.string.cocosations_call_stage_ice_negotiation;
            case CONNECTED -> R.string.cocosations_call_stage_connected;
        };
    }

    public static String reason(
            final Context context, @Nullable final CallDiagnostics.Reason reason) {
        if (reason == null) {
            return "";
        }
        return context.getString(
                switch (reason) {
                    case NO_NETWORK -> R.string.cocosations_call_reason_no_network;
                    case SERVER_NOT_ACKNOWLEDGED ->
                            R.string.cocosations_call_reason_server_not_acknowledged;
                    case SERVER_CONNECTION_LOST ->
                            R.string.cocosations_call_reason_server_connection_lost;
                    case REMOTE_SERVER_NOT_FOUND ->
                            R.string.cocosations_call_reason_remote_server_not_found;
                    case REMOTE_SERVER_TIMEOUT ->
                            R.string.cocosations_call_reason_remote_server_timeout;
                    case PEER_UNAVAILABLE -> R.string.cocosations_call_reason_peer_unavailable;
                    case PEER_NOT_RESPONDING ->
                            R.string.cocosations_call_reason_peer_not_responding;
                    case DECLINED -> R.string.cocosations_call_reason_declined;
                    case STANZA_ERROR -> R.string.cocosations_call_reason_stanza_error;
                    case ICE_FAILED -> R.string.cocosations_call_reason_ice_failed;
                    case CONNECTION_LOST -> R.string.cocosations_call_reason_connection_lost;
                    case APPLICATION_ERROR -> R.string.cocosations_call_reason_application_error;
                    case SECURITY_ERROR -> R.string.cocosations_call_reason_security_error;
                });
    }
}
