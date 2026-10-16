package eu.siacs.conversations.ui.util;

import android.content.Context;
import android.text.format.DateUtils;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.google.common.base.Splitter;
import eu.siacs.conversations.R;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.jingle.CallDiagnostics;
import eu.siacs.conversations.xmpp.jingle.CallQuality;
import eu.siacs.conversations.xmpp.jingle.CallSignaling;
import eu.siacs.conversations.xmpp.jingle.Media;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Локализованный вывод значений отчета о звонке. */
public final class CallReportFormat {

    private static final long SECONDS_THRESHOLD_MILLIS = 10_000;

    private CallReportFormat() {}

    public static String flowMark(final CallDiagnostics.Flow flow) {
        return switch (flow) {
            case SENT -> "→";
            case RECEIVED -> "←";
            case LOCAL -> "·";
        };
    }

    /** Время события с точностью до миллисекунд в часовом поясе устройства. */
    public static String clock(final long epochMillis) {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
                .format(new Date(epochMillis));
    }

    public static String elapsed(final Context context, final long elapsedMillis) {
        return String.format(
                Locale.getDefault(),
                context.getString(R.string.cocosations_call_report_elapsed),
                elapsedMillis / 1000.0);
    }

    public static String delay(final Context context, final long millis) {
        if (millis < SECONDS_THRESHOLD_MILLIS) {
            return context.getString(R.string.cocosations_call_report_milliseconds, millis);
        }
        return context.getString(R.string.cocosations_call_report_seconds, millis / 1000.0);
    }

    public static String event(final Context context, final CallDiagnostics.Event event) {
        final String label = context.getString(eventLabel(event.type()));
        final String detail = eventDetail(context, event);
        return detail == null ? label : label + " " + detail;
    }

    @Nullable
    private static String eventDetail(final Context context, final CallDiagnostics.Event event) {
        final String detail = event.detail();
        if (detail == null) {
            return null;
        }
        return switch (event.type()) {
            case FEDERATION_CONFIRMED -> {
                try {
                    yield context.getString(
                            federationEvidence(CallDiagnostics.FederationEvidence.valueOf(detail)));
                } catch (final IllegalArgumentException e) {
                    yield detail;
                }
            }
            case MEDIA -> {
                final Set<Media> media = new HashSet<>();
                for (final String name : Splitter.on(',').omitEmptyStrings().split(detail)) {
                    try {
                        media.add(Media.valueOf(name));
                    } catch (final IllegalArgumentException e) {
                        // неизвестный тип медиа не показываем
                    }
                }
                yield CallStageBar.media(context, media);
            }
            case QUALITY_DEGRADED, QUALITY_RESTORED -> qualityDetail(context, detail);
            default -> "(" + detail + ")";
        };
    }

    // формат деталей задает CallQuality.describe(): потери вх.;потери исх.;RTT
    private static String qualityDetail(final Context context, final String detail) {
        final List<String> parts = Splitter.on(';').splitToList(detail);
        if (parts.size() != 3) {
            return "(" + detail + ")";
        }
        try {
            final double lossIn = Double.parseDouble(parts.get(0));
            final double lossOut = Double.parseDouble(parts.get(1));
            final long rtt = Long.parseLong(parts.get(2));
            return "("
                    + context.getString(
                            R.string.cocosations_event_quality_detail,
                            percent(context, lossIn),
                            percent(context, lossOut),
                            rtt < 0
                                    ? "-"
                                    : context.getString(
                                            R.string.cocosations_call_report_milliseconds, rtt))
                    + ")";
        } catch (final NumberFormatException e) {
            return "(" + detail + ")";
        }
    }

    @StringRes
    public static int eventLabel(final CallDiagnostics.EventType type) {
        return switch (type) {
            case PROPOSE_SENT -> R.string.cocosations_event_propose_sent;
            case PROPOSE_RECEIVED -> R.string.cocosations_event_propose_received;
            case PROPOSE_ACKNOWLEDGED -> R.string.cocosations_event_propose_acknowledged;
            case PROPOSE_BOUNCED -> R.string.cocosations_event_propose_bounced;
            case PEER_DEVICE_FOUND -> R.string.cocosations_event_peer_device_found;
            case RINGING_SENT -> R.string.cocosations_event_ringing_sent;
            case RINGING_RECEIVED -> R.string.cocosations_event_ringing_received;
            case ACCEPTED_LOCALLY -> R.string.cocosations_event_accepted_locally;
            case PROCEED_SENT -> R.string.cocosations_event_proceed_sent;
            case PROCEED_RECEIVED -> R.string.cocosations_event_proceed_received;
            case PROCEED_BOUNCED -> R.string.cocosations_event_proceed_bounced;
            case REJECT_SENT -> R.string.cocosations_event_reject_sent;
            case REJECT_RECEIVED -> R.string.cocosations_event_reject_received;
            case RETRACT_SENT -> R.string.cocosations_event_retract_sent;
            case RETRACT_RECEIVED -> R.string.cocosations_event_retract_received;
            case SESSION_INITIATE_SENT -> R.string.cocosations_event_session_initiate_sent;
            case SESSION_INITIATE_RECEIVED -> R.string.cocosations_event_session_initiate_received;
            case SESSION_ACCEPT_SENT -> R.string.cocosations_event_session_accept_sent;
            case SESSION_ACCEPT_RECEIVED -> R.string.cocosations_event_session_accept_received;
            case SESSION_TERMINATE_SENT -> R.string.cocosations_event_session_terminate_sent;
            case SESSION_TERMINATE_RECEIVED ->
                    R.string.cocosations_event_session_terminate_received;
            case IQ_RESULT -> R.string.cocosations_event_iq_result;
            case IQ_ERROR -> R.string.cocosations_event_iq_error;
            case IQ_TIMEOUT -> R.string.cocosations_event_iq_timeout;
            case SERVER_PING -> R.string.cocosations_event_server_ping;
            case FEDERATION_CONFIRMED -> R.string.cocosations_event_federation_confirmed;
            case ICE_SERVERS_DISCOVERED -> R.string.cocosations_event_ice_servers_discovered;
            case PEER_SOFTWARE -> R.string.cocosations_event_peer_software;
            case MEDIA -> R.string.cocosations_event_media;
            case ICE_GATHERING -> R.string.cocosations_event_ice_gathering;
            case ICE_CANDIDATE_ERROR -> R.string.cocosations_event_ice_candidate_error;
            case ICE_CONNECTION -> R.string.cocosations_event_ice_connection;
            case PEER_CONNECTION -> R.string.cocosations_event_peer_connection;
            case SELECTED_PAIR -> R.string.cocosations_event_selected_pair;
            case ICE_RESTART -> R.string.cocosations_event_ice_restart;
            case QUALITY_DEGRADED -> R.string.cocosations_event_quality_degraded;
            case QUALITY_RESTORED -> R.string.cocosations_event_quality_restored;
            case ENDED -> R.string.cocosations_event_ended;
        };
    }

    @StringRes
    public static int step(final CallSignaling.Step step) {
        return switch (step) {
            case OWN_SERVER -> R.string.cocosations_step_own_server;
            case REMOTE_DELIVERY -> R.string.cocosations_step_remote_delivery;
            case PEER_ANSWER -> R.string.cocosations_step_peer_answer;
            case LOCAL_ANSWER -> R.string.cocosations_step_local_answer;
            case REMOTE_SETUP -> R.string.cocosations_step_remote_setup;
            case LOCAL_SETUP -> R.string.cocosations_step_local_setup;
            case PEER_ACK -> R.string.cocosations_step_peer_ack;
            case PEER_SETUP -> R.string.cocosations_step_peer_setup;
            case MEDIA -> R.string.cocosations_step_media;
        };
    }

    @StringRes
    public static int natType(final CallDiagnostics.NatType natType) {
        return switch (natType) {
            case UNKNOWN -> R.string.cocosations_nat_unknown;
            case NONE -> R.string.cocosations_nat_none;
            case CONE -> R.string.cocosations_nat_cone;
            case SYMMETRIC -> R.string.cocosations_nat_symmetric;
            case UNDETERMINED -> R.string.cocosations_nat_undetermined;
            case UDP_BLOCKED -> R.string.cocosations_nat_udp_blocked;
        };
    }

    @StringRes
    public static int suggestion(final CallDiagnostics.Suggestion suggestion) {
        return switch (suggestion) {
            case NONE -> R.string.cocosations_call_suggestion_none;
            case CHECK_NETWORK -> R.string.cocosations_call_suggestion_check_network;
            case CHECK_SERVER_CONNECTION ->
                    R.string.cocosations_call_suggestion_check_server_connection;
            case CHECK_REMOTE_ADDRESS -> R.string.cocosations_call_suggestion_check_remote_address;
            case REMOTE_SERVER_UNREACHABLE ->
                    R.string.cocosations_call_suggestion_remote_server_unreachable;
            case PEER_OFFLINE -> R.string.cocosations_call_suggestion_peer_offline;
            case PEER_DECLINED -> R.string.cocosations_call_suggestion_peer_declined;
            case CONFIGURE_TURN -> R.string.cocosations_call_suggestion_configure_turn;
            case CHECK_TURN -> R.string.cocosations_call_suggestion_check_turn;
            case SYMMETRIC_NAT -> R.string.cocosations_call_suggestion_symmetric_nat;
            case PEER_NETWORK -> R.string.cocosations_call_suggestion_peer_network;
            case UNSTABLE_NETWORK -> R.string.cocosations_call_suggestion_unstable_network;
            case SECURITY -> R.string.cocosations_call_suggestion_security;
            case RETRY -> R.string.cocosations_call_suggestion_retry;
        };
    }

    @StringRes
    public static int federationEvidence(final CallDiagnostics.FederationEvidence evidence) {
        return switch (evidence) {
            case PRESENCE -> R.string.cocosations_federation_evidence_presence;
            case PING -> R.string.cocosations_federation_evidence_ping;
            case DEVICE_FOUND -> R.string.cocosations_federation_evidence_device_found;
            case PROCEED -> R.string.cocosations_federation_evidence_proceed;
            case REJECT -> R.string.cocosations_federation_evidence_reject;
            case INCOMING_CALL -> R.string.cocosations_federation_evidence_incoming_call;
        };
    }

    public static String feature(final Context context, final String namespace) {
        return switch (namespace) {
            case Namespace.JINGLE_MESSAGE -> "JMI (XEP-0353)";
            case Namespace.JINGLE_APPS_RTP -> "Jingle RTP (XEP-0167)";
            case Namespace.JINGLE_FEATURE_AUDIO ->
                    context.getString(R.string.cocosations_feature_audio);
            case Namespace.JINGLE_FEATURE_VIDEO ->
                    context.getString(R.string.cocosations_feature_video);
            case Namespace.JINGLE_TRANSPORT_ICE_UDP -> "ICE-UDP (XEP-0176)";
            case Namespace.JINGLE_APPS_DTLS -> "DTLS-SRTP (XEP-0320)";
            case Namespace.SDP_OFFER_ANSWER -> "SDP offer/answer (RFC 3264)";
            case Namespace.OMEMO_DTLS_SRTP_VERIFICATION ->
                    context.getString(R.string.cocosations_feature_omemo_verification);
            default -> namespace;
        };
    }

    public static String endReason(final Context context, final CallDiagnostics.EndReason reason) {
        final String condition =
                reason.text() == null
                        ? reason.condition()
                        : reason.condition() + " (" + reason.text() + ")";
        return context.getString(
                reason.remote()
                        ? R.string.cocosations_call_report_end_reason_remote
                        : R.string.cocosations_call_report_end_reason_local,
                condition);
    }

    public static String peerSoftware(final Context context, final CallDiagnostics.Peer peer) {
        if (peer.softwareError() != null) {
            return context.getString(
                    R.string.cocosations_call_report_peer_software_error, peer.softwareError());
        }
        if (peer.software() == null) {
            return context.getString(R.string.cocosations_call_report_peer_software_pending);
        }
        final var builder = new StringBuilder(peer.software());
        if (peer.softwareVersion() != null) {
            builder.append(' ').append(peer.softwareVersion());
        }
        if (peer.os() != null) {
            builder.append(" (").append(peer.os()).append(')');
        }
        return builder.toString();
    }

    public static String peerIdentity(final Context context, final CallDiagnostics.Peer peer) {
        if (!peer.capsKnown()) {
            return context.getString(R.string.cocosations_call_report_peer_caps_unknown);
        }
        if (peer.identityName() == null) {
            return peer.identityType() == null ? "-" : peer.identityType();
        }
        return peer.identityType() == null
                ? peer.identityName()
                : peer.identityName() + " (" + peer.identityType() + ")";
    }

    public static String externalServices(
            final Context context, final CallDiagnostics.IceServerDiscovery discovery) {
        return switch (discovery.status()) {
            case OK ->
                    context.getString(
                            R.string.cocosations_external_services_ok,
                            discovery.services(),
                            discovery.durationMillis());
            case EMPTY ->
                    context.getString(
                            R.string.cocosations_external_services_empty,
                            discovery.durationMillis());
            case ERROR ->
                    context.getString(
                            R.string.cocosations_external_services_error,
                            discovery.error(),
                            discovery.durationMillis());
            case NOT_SUPPORTED ->
                    context.getString(R.string.cocosations_external_services_not_supported);
            case DISABLED -> context.getString(R.string.cocosations_external_services_disabled);
        };
    }

    public static String credentialsExpiry(final Context context, final long expiresAt) {
        final String date =
                DateUtils.formatDateTime(
                        context,
                        expiresAt,
                        DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME);
        return expiresAt < System.currentTimeMillis()
                ? context.getString(R.string.cocosations_call_report_credentials_expired, date)
                : date;
    }

    @StringRes
    public static int rating(final CallQuality.Rating rating) {
        return switch (rating) {
            case GOOD -> R.string.cocosations_quality_good;
            case FAIR -> R.string.cocosations_quality_fair;
            case POOR -> R.string.cocosations_quality_poor;
        };
    }

    public static String rating(final Context context, final CallQuality.Report quality) {
        return context.getString(
                R.string.cocosations_call_report_mos,
                context.getString(rating(quality.rating())),
                quality.mos());
    }

    @StringRes
    public static int problem(final CallQuality.Problem problem) {
        return switch (problem) {
            case INCOMING -> R.string.cocosations_problem_incoming;
            case OUTGOING -> R.string.cocosations_problem_outgoing;
            default -> R.string.cocosations_problem_both;
        };
    }

    public static String codec(final Context context, final CallQuality.Codec codec) {
        final var builder =
                new StringBuilder(
                        context.getString(
                                R.string.cocosations_call_report_codec_value,
                                codec.name(),
                                codec.clockRate(),
                                codec.channels()));
        if (codec.fec()) {
            builder.append(", FEC");
        }
        if (codec.dtx()) {
            builder.append(", DTX");
        }
        if (codec.fmtp() != null) {
            builder.append(" (").append(codec.fmtp()).append(')');
        }
        return builder.toString();
    }

    public static String percent(final Context context, final double value) {
        return value < 0 ? "-" : context.getString(R.string.cocosations_call_report_percent, value);
    }

    public static String mos(final double mos) {
        return mos < 0 ? "-" : String.format(Locale.getDefault(), "%.1f", mos);
    }
}
