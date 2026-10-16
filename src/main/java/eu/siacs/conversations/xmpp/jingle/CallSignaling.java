package eu.siacs.conversations.xmpp.jingle;

import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Задержки между шагами установки звонка по хронологии: где теряется время. */
public final class CallSignaling {

    public enum Step {
        OWN_SERVER,
        REMOTE_DELIVERY,
        PEER_ANSWER,
        LOCAL_ANSWER,
        REMOTE_SETUP,
        LOCAL_SETUP,
        PEER_ACK,
        PEER_SETUP,
        MEDIA
    }

    public record Delay(Step step, long millis) {}

    private CallSignaling() {}

    public static List<Delay> delays(final CallDiagnostics.Snapshot snapshot) {
        final Map<CallDiagnostics.EventType, Long> first =
                new EnumMap<>(CallDiagnostics.EventType.class);
        final Map<String, Long> iqResults = new HashMap<>();
        for (final var event : snapshot.events()) {
            first.putIfAbsent(event.type(), event.elapsedMillis());
            if (event.type() == CallDiagnostics.EventType.IQ_RESULT && event.detail() != null) {
                iqResults.putIfAbsent(event.detail(), event.elapsedMillis());
            }
        }
        Long connected = null;
        for (final var transition : snapshot.transitions()) {
            if (transition.stage() == CallStage.CONNECTED) {
                connected = transition.elapsedMillis();
            }
        }
        final List<Delay> delays = new ArrayList<>();
        if (snapshot.direction() == CallDiagnostics.Direction.OUTGOING) {
            final Long propose = first.get(CallDiagnostics.EventType.PROPOSE_SENT);
            final Long acknowledged = first.get(CallDiagnostics.EventType.PROPOSE_ACKNOWLEDGED);
            final Long delivered =
                    earliest(
                            first.get(CallDiagnostics.EventType.RINGING_RECEIVED),
                            first.get(CallDiagnostics.EventType.PEER_DEVICE_FOUND));
            final Long proceed = first.get(CallDiagnostics.EventType.PROCEED_RECEIVED);
            final Long initiate = first.get(CallDiagnostics.EventType.SESSION_INITIATE_SENT);
            final Long accept = first.get(CallDiagnostics.EventType.SESSION_ACCEPT_RECEIVED);
            final Long sentOrAcknowledged = acknowledged != null ? acknowledged : propose;
            add(delays, Step.OWN_SERVER, propose, acknowledged);
            add(delays, Step.REMOTE_DELIVERY, sentOrAcknowledged, delivered);
            add(
                    delays,
                    Step.PEER_ANSWER,
                    delivered != null ? delivered : sentOrAcknowledged,
                    proceed);
            add(delays, Step.LOCAL_SETUP, proceed, initiate);
            add(delays, Step.PEER_ACK, initiate, iqResults.get("session-initiate"));
            add(delays, Step.PEER_SETUP, initiate, accept);
            add(delays, Step.MEDIA, accept, connected);
        } else {
            final Long initiate = first.get(CallDiagnostics.EventType.SESSION_INITIATE_RECEIVED);
            final Long offered =
                    earliest(first.get(CallDiagnostics.EventType.PROPOSE_RECEIVED), initiate);
            final Long accepted = first.get(CallDiagnostics.EventType.ACCEPTED_LOCALLY);
            final Long proceed = first.get(CallDiagnostics.EventType.PROCEED_SENT);
            final Long accept = first.get(CallDiagnostics.EventType.SESSION_ACCEPT_SENT);
            add(delays, Step.LOCAL_ANSWER, offered, accepted);
            add(delays, Step.REMOTE_SETUP, proceed, initiate);
            // без JMI session-initiate приходит до ответа пользователя
            add(delays, Step.LOCAL_SETUP, latest(accepted, initiate), accept);
            add(delays, Step.PEER_ACK, accept, iqResults.get("session-accept"));
            add(delays, Step.MEDIA, accept, connected);
        }
        return delays;
    }

    private static void add(
            final List<Delay> delays,
            final Step step,
            @Nullable final Long from,
            @Nullable final Long to) {
        if (from != null && to != null && to >= from) {
            delays.add(new Delay(step, to - from));
        }
    }

    @Nullable
    private static Long earliest(@Nullable final Long a, @Nullable final Long b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : Math.min(a, b);
    }

    @Nullable
    private static Long latest(@Nullable final Long a, @Nullable final Long b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : Math.max(a, b);
    }
}
