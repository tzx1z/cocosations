package eu.siacs.conversations.xmpp.jingle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.collect.Ordering;
import com.google.common.net.InetAddresses;
import eu.siacs.conversations.xml.Namespace;
import im.conversations.android.xmpp.IqErrorException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * Хронология установки звонка: этапы, причина сбоя и данные ICE для отчета. Пишется из сигнального
 * и медиа-слоя, UI читает неизменяемый {@link Snapshot}.
 */
public class CallDiagnostics {

    private static final int MAX_EVENTS = 200;

    /** Фичи собеседника из disco, которые влияют на звонок. */
    public static final List<String> PEER_FEATURES =
            List.of(
                    Namespace.JINGLE_MESSAGE,
                    Namespace.JINGLE_APPS_RTP,
                    Namespace.JINGLE_FEATURE_AUDIO,
                    Namespace.JINGLE_FEATURE_VIDEO,
                    Namespace.JINGLE_TRANSPORT_ICE_UDP,
                    Namespace.JINGLE_APPS_DTLS,
                    Namespace.SDP_OFFER_ANSWER,
                    Namespace.OMEMO_DTLS_SRTP_VERIFICATION);

    public enum Direction {
        OUTGOING,
        INCOMING
    }

    public enum Reason {
        NO_NETWORK,
        SERVER_NOT_ACKNOWLEDGED,
        SERVER_CONNECTION_LOST,
        REMOTE_SERVER_NOT_FOUND,
        REMOTE_SERVER_TIMEOUT,
        PEER_UNAVAILABLE,
        PEER_NOT_RESPONDING,
        DECLINED,
        STANZA_ERROR,
        ICE_FAILED,
        CONNECTION_LOST,
        APPLICATION_ERROR,
        SECURITY_ERROR
    }

    public enum NatType {
        UNKNOWN,
        NONE,
        CONE,
        SYMMETRIC,
        UNDETERMINED,
        UDP_BLOCKED
    }

    public enum Suggestion {
        NONE,
        CHECK_NETWORK,
        CHECK_SERVER_CONNECTION,
        CHECK_REMOTE_ADDRESS,
        REMOTE_SERVER_UNREACHABLE,
        PEER_OFFLINE,
        PEER_DECLINED,
        CONFIGURE_TURN,
        CHECK_TURN,
        SYMMETRIC_NAT,
        PEER_NETWORK,
        UNSTABLE_NETWORK,
        SECURITY,
        RETRY
    }

    public enum Termination {
        LOCAL,
        REMOTE,
        FAILURE
    }

    public enum FederationEvidence {
        PRESENCE,
        PING,
        DEVICE_FOUND,
        PROCEED,
        REJECT,
        INCOMING_CALL
    }

    public enum Route {
        DIRECT,
        LOCAL_NETWORK,
        LOCAL_RELAY,
        REMOTE_RELAY,
        BOTH_RELAYS
    }

    /** STUN/TURN URI из RFC 7064 и RFC 7065. */
    public record IceServerUrl(String url, String scheme, String host, int port, String transport) {

        public static IceServerUrl parse(final String url) {
            if (Strings.isNullOrEmpty(url)) {
                return null;
            }
            final int colon = url.indexOf(':');
            if (colon <= 0) {
                return null;
            }
            final String scheme = url.substring(0, colon).toLowerCase(Locale.ROOT);
            if (!List.of("stun", "stuns", "turn", "turns").contains(scheme)) {
                return null;
            }
            String rest = url.substring(colon + 1);
            String transport = null;
            final int query = rest.indexOf('?');
            if (query >= 0) {
                for (final String parameter : Splitter.on('&').split(rest.substring(query + 1))) {
                    if (parameter.toLowerCase(Locale.ROOT).startsWith("transport=")) {
                        transport = parameter.substring(10).toLowerCase(Locale.ROOT);
                    }
                }
                rest = rest.substring(0, query);
            }
            final boolean secure = scheme.endsWith("s");
            final String host;
            int port = secure ? 5349 : 3478;
            if (rest.startsWith("[")) {
                final int end = rest.indexOf(']');
                if (end < 0) {
                    return null;
                }
                host = rest.substring(1, end);
                if (rest.length() > end + 2 && rest.charAt(end + 1) == ':') {
                    port = Candidate.parsePort(rest.substring(end + 2));
                }
            } else {
                final int separator = rest.lastIndexOf(':');
                if (separator > 0) {
                    host = rest.substring(0, separator);
                    port = Candidate.parsePort(rest.substring(separator + 1));
                } else {
                    host = rest;
                }
            }
            if (host.isEmpty() || port <= 0) {
                return null;
            }
            // turns: всегда поверх TLS, транспорт по умолчанию для stun: и turn: - UDP
            final String effectiveTransport =
                    secure ? "tls" : transport == null ? "udp" : transport;
            return new IceServerUrl(url, scheme, host, port, effectiveTransport);
        }

        public boolean isTurn() {
            return scheme.startsWith("turn");
        }

        public boolean isUdp() {
            return "udp".equals(transport);
        }

        public String endpoint() {
            return host.toLowerCase(Locale.ROOT) + ":" + port;
        }
    }

    public record Transition(CallStage stage, long elapsedMillis) {}

    public enum Flow {
        SENT,
        RECEIVED,
        LOCAL
    }

    /** События хронологии; детали технические (адреса, состояния, условия ошибок) без перевода. */
    public enum EventType {
        PROPOSE_SENT(Flow.SENT),
        PROPOSE_RECEIVED(Flow.RECEIVED),
        PROPOSE_ACKNOWLEDGED(Flow.RECEIVED),
        PROPOSE_BOUNCED(Flow.RECEIVED),
        PEER_DEVICE_FOUND(Flow.RECEIVED),
        RINGING_SENT(Flow.SENT),
        RINGING_RECEIVED(Flow.RECEIVED),
        ACCEPTED_LOCALLY(Flow.LOCAL),
        PROCEED_SENT(Flow.SENT),
        PROCEED_RECEIVED(Flow.RECEIVED),
        PROCEED_BOUNCED(Flow.RECEIVED),
        REJECT_SENT(Flow.SENT),
        REJECT_RECEIVED(Flow.RECEIVED),
        RETRACT_SENT(Flow.SENT),
        RETRACT_RECEIVED(Flow.RECEIVED),
        SESSION_INITIATE_SENT(Flow.SENT),
        SESSION_INITIATE_RECEIVED(Flow.RECEIVED),
        SESSION_ACCEPT_SENT(Flow.SENT),
        SESSION_ACCEPT_RECEIVED(Flow.RECEIVED),
        SESSION_TERMINATE_SENT(Flow.SENT),
        SESSION_TERMINATE_RECEIVED(Flow.RECEIVED),
        IQ_RESULT(Flow.RECEIVED),
        IQ_ERROR(Flow.RECEIVED),
        IQ_TIMEOUT(Flow.LOCAL),
        SERVER_PING(Flow.RECEIVED),
        FEDERATION_CONFIRMED(Flow.LOCAL),
        ICE_SERVERS_DISCOVERED(Flow.RECEIVED),
        PEER_SOFTWARE(Flow.RECEIVED),
        MEDIA(Flow.LOCAL),
        ICE_GATHERING(Flow.LOCAL),
        ICE_CANDIDATE_ERROR(Flow.LOCAL),
        ICE_CONNECTION(Flow.LOCAL),
        PEER_CONNECTION(Flow.LOCAL),
        SELECTED_PAIR(Flow.LOCAL),
        ICE_RESTART(Flow.LOCAL),
        QUALITY_DEGRADED(Flow.LOCAL),
        QUALITY_RESTORED(Flow.LOCAL),
        ENDED(Flow.LOCAL);

        public final Flow flow;

        EventType(final Flow flow) {
            this.flow = flow;
        }
    }

    public record Event(long elapsedMillis, EventType type, @Nullable String detail) {}

    /** Причина завершения из Jingle reason или JMI; remote - причину прислал собеседник. */
    public record EndReason(String condition, @Nullable String text, boolean remote) {}

    /** Устройство собеседника: caps (XEP-0115) из кеша disco и версия ПО (XEP-0092). */
    public record Peer(
            String address,
            boolean capsKnown,
            @Nullable String identityName,
            @Nullable String identityType,
            List<String> features,
            @Nullable String software,
            @Nullable String softwareVersion,
            @Nullable String os,
            @Nullable String softwareError) {

        public boolean hasFeature(final String feature) {
            return features.contains(feature);
        }

        Peer withSoftware(
                @Nullable final String software,
                @Nullable final String version,
                @Nullable final String os,
                @Nullable final String error) {
            return new Peer(
                    address,
                    capsKnown,
                    identityName,
                    identityType,
                    features,
                    software,
                    version,
                    os,
                    error);
        }
    }

    /** Получение STUN/TURN по XEP-0215. expiresAt - срок учетных данных TURN, -1 если не задан. */
    public record IceServerDiscovery(
            IceServerDiscovery.Status status,
            long durationMillis,
            int services,
            long expiresAt,
            @Nullable String error) {

        public enum Status {
            OK,
            EMPTY,
            ERROR,
            NOT_SUPPORTED,
            DISABLED
        }

        /** Ближайший срок из атрибутов expires (xs:dateTime) или -1. */
        public static long expiresAt(final Collection<String> values) {
            long earliest = -1;
            for (final String value : values) {
                if (Strings.isNullOrEmpty(value)) {
                    continue;
                }
                try {
                    final long expires = OffsetDateTime.parse(value).toInstant().toEpochMilli();
                    if (earliest < 0 || expires < earliest) {
                        earliest = expires;
                    }
                } catch (final DateTimeParseException e) {
                    // некорректный срок не мешает звонку, в отчете его просто нет
                }
            }
            return earliest;
        }
    }

    /** Ошибка ICE-сервера из onIceCandidateError; одинаковые ошибки считаются вместе. */
    public record IceCandidateError(String url, int code, String text, int count) {}

    /** serverUrl - STUN/TURN-сервер, через который получен локальный кандидат. */
    public record Candidate(
            String type,
            String protocol,
            String address,
            int port,
            String relatedAddress,
            int relatedPort,
            String serverUrl) {

        // candidate:<foundation> <component> <protocol> <priority> <address> <port> typ <type>
        // [raddr <address> rport <port>] [<ключ> <значение>]...
        public static Candidate parse(final String sdp) {
            if (Strings.isNullOrEmpty(sdp)) {
                return null;
            }
            final String line = sdp.startsWith("a=") ? sdp.substring(2) : sdp;
            final List<String> parts = Splitter.on(' ').omitEmptyStrings().splitToList(line.trim());
            if (parts.size() < 8
                    || !parts.get(0).startsWith("candidate:")
                    || !"typ".equals(parts.get(6))) {
                return null;
            }
            final int port = parsePort(parts.get(5));
            if (port < 0) {
                return null;
            }
            String relatedAddress = null;
            int relatedPort = -1;
            for (int i = 8; i + 1 < parts.size(); i += 2) {
                switch (parts.get(i)) {
                    case "raddr" -> relatedAddress = parts.get(i + 1);
                    case "rport" -> relatedPort = parsePort(parts.get(i + 1));
                    default -> {}
                }
            }
            return new Candidate(
                    parts.get(7).toLowerCase(Locale.ROOT),
                    parts.get(2).toLowerCase(Locale.ROOT),
                    parts.get(4),
                    port,
                    relatedAddress,
                    relatedPort,
                    null);
        }

        public static Candidate parse(final String sdp, final String serverUrl) {
            final var candidate = parse(sdp);
            if (candidate == null || Strings.isNullOrEmpty(serverUrl)) {
                return candidate;
            }
            return new Candidate(
                    candidate.type,
                    candidate.protocol,
                    candidate.address,
                    candidate.port,
                    candidate.relatedAddress,
                    candidate.relatedPort,
                    serverUrl);
        }

        public boolean isType(final String type) {
            return type.equals(this.type);
        }

        @Override
        @NonNull
        public String toString() {
            final var builder = new StringBuilder();
            builder.append(type).append(' ').append(protocol).append(' ');
            builder.append(endpoint(address, port));
            if (relatedAddress != null) {
                builder.append(" (").append(endpoint(relatedAddress, relatedPort)).append(')');
            }
            if (serverUrl != null) {
                builder.append(" via ").append(serverUrl);
            }
            return builder.toString();
        }

        private static String endpoint(final String address, final int port) {
            return address.indexOf(':') >= 0 ? "[" + address + "]:" + port : address + ":" + port;
        }

        static int parsePort(final String value) {
            try {
                final int port = Integer.parseInt(value);
                return port >= 0 && port <= 65535 ? port : -1;
            } catch (final NumberFormatException e) {
                return -1;
            }
        }
    }

    /** Проверка пары кандидатов по данным getStats(); rttMillis отрицательный, если неизвестен. */
    public record PairStats(
            Candidate local,
            Candidate remote,
            String state,
            boolean nominated,
            boolean selected,
            long rttMillis,
            long requestsSent,
            long responsesReceived) {}

    public record Snapshot(
            Direction direction,
            String with,
            @Nullable String sessionId,
            Set<Media> media,
            boolean federated,
            FederationEvidence federationEvidence,
            long startedAt,
            CallStage stage,
            CallStage reached,
            Reason reason,
            String condition,
            boolean ended,
            Termination termination,
            long durationMillis,
            long connectedMillis,
            List<Transition> transitions,
            List<Event> events,
            @Nullable EndReason endReason,
            @Nullable Peer peer,
            @Nullable IceServerDiscovery iceServerDiscovery,
            List<IceCandidateError> iceCandidateErrors,
            List<Candidate> localCandidates,
            List<Candidate> remoteCandidates,
            String iceConnectionState,
            String peerConnectionState,
            String iceGatheringState,
            List<IceServerUrl> iceServers,
            int stunServers,
            int turnServers,
            boolean forceRelay,
            Candidate selectedLocal,
            Candidate selectedRemote,
            Route route,
            List<PairStats> pairStats,
            @Nullable CallQuality.Report quality,
            boolean carrierGradeNat,
            NatType natType,
            Suggestion suggestion,
            int version) {

        public boolean hasLocalRelay() {
            return Iterables.any(localCandidates, c -> c.isType("relay"));
        }

        public long selectedRttMillis() {
            for (final var pair : pairStats) {
                if (pair.selected()) {
                    return pair.rttMillis();
                }
            }
            return -1;
        }
    }

    private final Direction direction;
    private final String with;
    private final boolean federated;
    private final long startedAt = System.currentTimeMillis();
    private final LongSupplier nanoClock;
    private final long startedNanos;

    private CallStage stage = CallStage.CONNECTING_TO_SERVER;
    private CallStage reached = CallStage.CONNECTING_TO_SERVER;
    private Reason reason;
    private String condition;
    private FederationEvidence federationEvidence;
    private Set<Media> media = ImmutableSet.of();
    private String sessionId;
    private EndReason endReason;
    private Peer peer;
    private IceServerDiscovery iceServerDiscovery;
    private final Map<String, IceCandidateError> iceCandidateErrors = new LinkedHashMap<>();
    private boolean accepted = false;
    private boolean localHangup = false;
    private boolean ended = false;
    private long endedAt = -1;
    private long connectedAt = -1;
    private long connectedDuration = 0;

    private final List<Transition> transitions = new ArrayList<>();
    private final List<Event> events = new ArrayList<>();
    private final Set<Candidate> localCandidates = new LinkedHashSet<>();
    private final Set<Candidate> remoteCandidates = new LinkedHashSet<>();
    private String iceConnectionState;
    private String peerConnectionState;
    private String iceGatheringState;
    private List<IceServerUrl> iceServers = ImmutableList.of();
    private int stunServers = 0;
    private int turnServers = 0;
    private boolean forceRelay = false;
    private Candidate selectedLocal;
    private Candidate selectedRemote;
    private List<PairStats> pairStats = ImmutableList.of();
    private final CallQuality quality = new CallQuality();
    private OnEnded onEnded;
    private int version = 0;

    public CallDiagnostics(final Direction direction, final String with, final boolean federated) {
        this(direction, with, federated, System::nanoTime);
    }

    CallDiagnostics(
            final Direction direction,
            final String with,
            final boolean federated,
            final LongSupplier nanoClock) {
        this.direction = direction;
        this.with = with;
        this.federated = federated;
        this.nanoClock = nanoClock;
        this.startedNanos = nanoClock.getAsLong();
        this.transitions.add(new Transition(CallStage.CONNECTING_TO_SERVER, 0));
    }

    public synchronized void advance(final CallStage target) {
        Preconditions.checkArgument(!target.isFailure(), "Failures have to be reported via fail()");
        if (ended || stage.isFailure()) {
            return;
        }
        // собеседник на нашем сервере или федерация уже подтверждена: S2S-этап проходим сразу
        final CallStage next =
                target == CallStage.RESOLVING_REMOTE_SERVER
                                && (!federated || federationEvidence != null)
                        ? CallStage.WAITING_FOR_PEER
                        : target;
        if (next.compareTo(stage) <= 0) {
            return;
        }
        final long now = elapsed();
        for (final CallStage s : CallStage.values()) {
            if (s.compareTo(stage) > 0 && s.compareTo(next) <= 0) {
                transitions.add(new Transition(s, now));
            }
        }
        this.stage = next;
        this.reached = next;
        if (next == CallStage.CONNECTED) {
            this.connectedAt = now;
        }
        version++;
    }

    /**
     * Ответ с сервера собеседника доказывает, что S2S работает; учитывается первое доказательство.
     */
    public synchronized void confirmFederation(final FederationEvidence evidence) {
        if (federationEvidence != null) {
            return;
        }
        this.federationEvidence = evidence;
        if (federated) {
            event(EventType.FEDERATION_CONFIRMED, evidence.name());
        }
        if (stage == CallStage.RESOLVING_REMOTE_SERVER) {
            advance(CallStage.WAITING_FOR_PEER);
        }
    }

    public synchronized void fail(
            final CallStage failure, final Reason reason, final String condition) {
        Preconditions.checkArgument(failure.isFailure(), "Stage has to be a failure");
        // первая зафиксированная причина считается корневой, последующие ошибки - ее следствие
        if (ended || stage.isFailure()) {
            return;
        }
        stopConnectedTimer();
        this.stage = failure;
        this.reason = reason;
        this.condition = condition;
        this.transitions.add(new Transition(failure, elapsed()));
        version++;
    }

    public synchronized void fail(final Reason reason, final String condition) {
        fail(failureStageOf(reason), reason, condition);
    }

    public synchronized void failWithCondition(final String condition) {
        fail(reasonOfCondition(condition), condition);
    }

    /** Истекло время ожидания ответа: виноват этап, на котором звонок остановился. */
    public synchronized void timeout() {
        switch (stage) {
            case CONNECTING_TO_SERVER ->
                    fail(CallStage.FAILED_SERVER, Reason.SERVER_NOT_ACKNOWLEDGED, null);
            case RESOLVING_REMOTE_SERVER ->
                    fail(CallStage.FAILED_FEDERATION, Reason.REMOTE_SERVER_TIMEOUT, null);
            default -> fail(stage.asFailure(), Reason.PEER_NOT_RESPONDING, null);
        }
    }

    public void end() {
        final OnEnded listener;
        final Snapshot snapshot;
        final boolean reportWorthy;
        synchronized (this) {
            if (ended) {
                return;
            }
            stopConnectedTimer();
            this.ended = true;
            this.endedAt = elapsed();
            event(EventType.ENDED);
            listener = onEnded;
            snapshot = listener == null ? null : snapshot();
            reportWorthy = isReportWorthy();
        }
        // слушатель пишет отчет на диск; вызываем его вне блокировки
        if (listener != null) {
            listener.onEnded(snapshot, reportWorthy);
        }
    }

    public interface OnEnded {
        /** reportWorthy false - входящий звонок без ответа, отчет по нему не сохраняется. */
        void onEnded(Snapshot snapshot, boolean reportWorthy);
    }

    /** Вызывается один раз по завершении звонка. */
    public synchronized void setOnEnded(final OnEnded onEnded) {
        this.onEnded = onEnded;
    }

    /** Session id Jingle; по нему отчет находится в журнале и в логах обеих сторон. */
    public synchronized void setSessionId(final String sessionId) {
        this.sessionId = sessionId;
    }

    public String getWith() {
        return with;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public synchronized void setLocalHangup() {
        this.localHangup = true;
    }

    public synchronized void setAccepted() {
        this.accepted = true;
    }

    /** Входящий звонок, который пользователь не принял, разбирать нечего. */
    public synchronized boolean isReportWorthy() {
        return direction == Direction.OUTGOING || accepted;
    }

    public synchronized boolean isEnded() {
        return ended;
    }

    public synchronized CallStage getStage() {
        return stage;
    }

    public synchronized int getVersion() {
        return version;
    }

    public void event(final EventType type) {
        event(type, null);
    }

    public synchronized void event(final EventType type, @Nullable final String detail) {
        if (events.size() >= MAX_EVENTS) {
            events.remove(0);
        }
        events.add(new Event(elapsed(), type, detail));
        version++;
    }

    public synchronized void setMedia(final Collection<Media> media) {
        final Set<Media> current = ImmutableSet.copyOf(media);
        if (!current.equals(this.media)) {
            this.media = current;
            event(
                    EventType.MEDIA,
                    Joiner.on(',').join(Collections2.transform(current, Enum::name)));
        }
    }

    /** Учитывается первая причина: дальше обе стороны лишь подтверждают завершение. */
    public synchronized void setEndReason(
            final String condition, @Nullable final String text, final boolean remote) {
        if (endReason == null) {
            this.endReason = new EndReason(condition, Strings.emptyToNull(text), remote);
            version++;
        }
    }

    public synchronized void setPeer(final Peer peer) {
        this.peer = peer;
        version++;
    }

    public synchronized void setPeerSoftware(
            @Nullable final String software,
            @Nullable final String version,
            @Nullable final String os,
            @Nullable final String error) {
        if (peer == null) {
            return;
        }
        this.peer = peer.withSoftware(software, version, os, error);
        event(
                EventType.PEER_SOFTWARE,
                error != null
                        ? error
                        : Joiner.on(' ')
                                .skipNulls()
                                .join(software, version, os == null ? null : "(" + os + ")"));
    }

    public synchronized void setIceServerDiscovery(final IceServerDiscovery discovery) {
        this.iceServerDiscovery = discovery;
        event(
                EventType.ICE_SERVERS_DISCOVERED,
                discovery.status() == IceServerDiscovery.Status.ERROR
                        ? discovery.error()
                        : String.valueOf(discovery.services()));
    }

    public synchronized void addIceCandidateError(
            final String url, final int code, final String text) {
        final String key = url + " " + code;
        final var existing = iceCandidateErrors.get(key);
        if (existing == null) {
            event(
                    EventType.ICE_CANDIDATE_ERROR,
                    url + ": " + code + " " + Strings.nullToEmpty(text));
        }
        iceCandidateErrors.put(
                key,
                new IceCandidateError(
                        url,
                        code,
                        Strings.nullToEmpty(text),
                        existing == null ? 1 : existing.count() + 1));
        version++;
    }

    public synchronized void setIceServers(
            final Collection<String> urls, final boolean forceRelay) {
        final var builder = new ImmutableList.Builder<IceServerUrl>();
        int stunServers = 0;
        int turnServers = 0;
        for (final String url : urls) {
            final var iceServer = IceServerUrl.parse(url);
            if (iceServer == null) {
                continue;
            }
            builder.add(iceServer);
            if (iceServer.isTurn()) {
                turnServers++;
            } else {
                stunServers++;
            }
        }
        this.iceServers = builder.build();
        this.stunServers = stunServers;
        this.turnServers = turnServers;
        this.forceRelay = forceRelay;
        version++;
    }

    public synchronized void setIceConnectionState(final String state) {
        if (!Objects.equals(state, iceConnectionState)) {
            this.iceConnectionState = state;
            event(EventType.ICE_CONNECTION, state);
        }
    }

    public synchronized void setPeerConnectionState(final String state) {
        if (!Objects.equals(state, peerConnectionState)) {
            this.peerConnectionState = state;
            event(EventType.PEER_CONNECTION, state);
        }
    }

    public synchronized void setIceGatheringState(final String state) {
        if (!Objects.equals(state, iceGatheringState)) {
            this.iceGatheringState = state;
            event(EventType.ICE_GATHERING, state);
        }
    }

    public synchronized void addLocalCandidate(final String sdp, final String serverUrl) {
        final Candidate candidate = Candidate.parse(sdp, serverUrl);
        if (candidate != null && localCandidates.add(candidate)) {
            version++;
        }
    }

    public synchronized void addRemoteCandidate(final Candidate candidate) {
        if (candidate != null && remoteCandidates.add(candidate)) {
            version++;
        }
    }

    public synchronized void setSelectedCandidatePair(
            final String localSdp, final String remoteSdp) {
        this.selectedLocal = Candidate.parse(localSdp);
        this.selectedRemote = Candidate.parse(remoteSdp);
        event(EventType.SELECTED_PAIR, selectedLocal + " -> " + selectedRemote);
    }

    public synchronized void setPairStats(final List<PairStats> pairStats) {
        this.pairStats = ImmutableList.copyOf(pairStats);
        version++;
    }

    /**
     * Отчет getStats(), приведенный к id -> поля. record - плановый опрос, из которого строится
     * временной ряд качества.
     */
    public void onStats(final Map<String, Map<String, Object>> report, final boolean record) {
        final var pairs = parsePairStats(report);
        final var counters = CallQuality.Counters.parse(report);
        synchronized (this) {
            if (ended) {
                return;
            }
            this.pairStats = ImmutableList.copyOf(pairs);
            final boolean wasDegraded = quality.isDegraded();
            final var sample = quality.update(counters, elapsed(), record);
            if (sample != null && sample.isDegraded() != wasDegraded) {
                event(
                        sample.isDegraded()
                                ? EventType.QUALITY_DEGRADED
                                : EventType.QUALITY_RESTORED,
                        CallQuality.describe(sample));
            }
            version++;
        }
    }

    /** Показатели за последний интервал опроса для индикатора на экране звонка. */
    @Nullable
    public synchronized CallQuality.Sample getLiveQuality() {
        return quality.live();
    }

    /** Показатели за последнюю минуту для графика на экране звонка. */
    public synchronized List<CallQuality.Sample> getRecentQuality() {
        return quality.recent();
    }

    @Nullable
    public synchronized Route getRoute() {
        return routeOf(selectedLocal, selectedRemote);
    }

    public synchronized NatType getNatType() {
        return detectNatType(localCandidates, stunServers, forceRelay);
    }

    public synchronized boolean hasLocalRelay() {
        return Iterables.any(localCandidates, c -> c.isType("relay"));
    }

    public synchronized Suggestion generateSuggestion() {
        if (!stage.isFailure() || reason == null) {
            return Suggestion.NONE;
        }
        return switch (reason) {
            case NO_NETWORK -> Suggestion.CHECK_NETWORK;
            case SERVER_NOT_ACKNOWLEDGED, SERVER_CONNECTION_LOST ->
                    Suggestion.CHECK_SERVER_CONNECTION;
            case REMOTE_SERVER_NOT_FOUND -> Suggestion.CHECK_REMOTE_ADDRESS;
            case REMOTE_SERVER_TIMEOUT -> Suggestion.REMOTE_SERVER_UNREACHABLE;
            case PEER_UNAVAILABLE, PEER_NOT_RESPONDING ->
                    stage == CallStage.FAILED_ICE
                            ? Suggestion.UNSTABLE_NETWORK
                            : Suggestion.PEER_OFFLINE;
            case DECLINED -> Suggestion.PEER_DECLINED;
            case ICE_FAILED -> iceSuggestion();
            case CONNECTION_LOST -> Suggestion.UNSTABLE_NETWORK;
            case SECURITY_ERROR -> Suggestion.SECURITY;
            case STANZA_ERROR, APPLICATION_ERROR -> Suggestion.RETRY;
        };
    }

    private Suggestion iceSuggestion() {
        if (turnServers == 0) {
            return getNatType() == NatType.SYMMETRIC
                    ? Suggestion.SYMMETRIC_NAT
                    : Suggestion.CONFIGURE_TURN;
        }
        if (!hasLocalRelay()) {
            return Suggestion.CHECK_TURN;
        }
        // relay-кандидат у нас есть, значит соединение не прошло со стороны собеседника
        return Suggestion.PEER_NETWORK;
    }

    public synchronized Snapshot snapshot() {
        final long now = elapsed();
        final Termination termination;
        if (!ended) {
            termination = null;
        } else if (stage.isFailure()) {
            termination = Termination.FAILURE;
        } else {
            termination = localHangup ? Termination.LOCAL : Termination.REMOTE;
        }
        return new Snapshot(
                direction,
                with,
                sessionId,
                media,
                federated,
                federationEvidence,
                startedAt,
                stage,
                reached,
                reason,
                condition,
                ended,
                termination,
                ended ? endedAt : now,
                connectedAt >= 0 ? connectedDuration + (now - connectedAt) : connectedDuration,
                ImmutableList.copyOf(transitions),
                ImmutableList.copyOf(events),
                endReason,
                peer,
                iceServerDiscovery,
                ImmutableList.copyOf(iceCandidateErrors.values()),
                ImmutableList.copyOf(localCandidates),
                ImmutableList.copyOf(remoteCandidates),
                iceConnectionState,
                peerConnectionState,
                iceGatheringState,
                iceServers,
                stunServers,
                turnServers,
                forceRelay,
                selectedLocal,
                selectedRemote,
                routeOf(selectedLocal, selectedRemote),
                pairStats,
                quality.report(),
                Iterables.any(localCandidates, c -> isCarrierGradeNat(c.address())),
                getNatType(),
                generateSuggestion(),
                version);
    }

    private void stopConnectedTimer() {
        if (connectedAt >= 0) {
            connectedDuration += elapsed() - connectedAt;
            connectedAt = -1;
        }
    }

    private long elapsed() {
        return (nanoClock.getAsLong() - startedNanos) / 1_000_000L;
    }

    private CallStage failureStageOf(final Reason reason) {
        return switch (reason) {
            case NO_NETWORK, SERVER_NOT_ACKNOWLEDGED, SERVER_CONNECTION_LOST ->
                    CallStage.FAILED_SERVER;
            case REMOTE_SERVER_NOT_FOUND, REMOTE_SERVER_TIMEOUT -> CallStage.FAILED_FEDERATION;
            case PEER_UNAVAILABLE, DECLINED -> CallStage.FAILED_DELIVERY;
            case ICE_FAILED, CONNECTION_LOST -> CallStage.FAILED_ICE;
            default -> stage.asFailure();
        };
    }

    /** Условие ошибки XMPP, timeout или имя исключения для отчета. */
    public static String errorName(final Throwable throwable) {
        if (throwable instanceof IqErrorException iqErrorException) {
            final var condition = iqErrorException.getErrorCondition();
            return condition == null ? "error" : condition.getName();
        }
        if (throwable instanceof TimeoutException) {
            return "timeout";
        }
        return throwable.getClass().getSimpleName();
    }

    public static Reason reasonOfCondition(final String condition) {
        if (condition == null) {
            return Reason.STANZA_ERROR;
        }
        return switch (condition) {
            case "remote-server-not-found" -> Reason.REMOTE_SERVER_NOT_FOUND;
            case "remote-server-timeout" -> Reason.REMOTE_SERVER_TIMEOUT;
            case "service-unavailable", "recipient-unavailable", "item-not-found", "gone" ->
                    Reason.PEER_UNAVAILABLE;
            default -> Reason.STANZA_ERROR;
        };
    }

    static NatType detectNatType(
            final Collection<Candidate> candidates,
            final int stunServers,
            final boolean forceRelay) {
        if (forceRelay || candidates.isEmpty()) {
            return NatType.UNKNOWN;
        }
        boolean publicHost = false;
        boolean relay = false;
        final List<Candidate> srflx = new ArrayList<>();
        for (final Candidate candidate : candidates) {
            if (candidate.isType("relay")) {
                relay = true;
            }
            if (asIpv4(candidate.address()) == null) {
                continue;
            }
            if (candidate.isType("host") && isPublicIpv4(candidate.address())) {
                publicHost = true;
            } else if (candidate.isType("srflx")) {
                srflx.add(candidate);
            }
        }
        if (publicHost) {
            return NatType.NONE;
        }
        if (srflx.isEmpty()) {
            if (stunServers == 0) {
                return NatType.UNKNOWN;
            }
            return relay ? NatType.UNDETERMINED : NatType.UDP_BLOCKED;
        }
        // разные внешние порты для одного локального сокета (по разным STUN-серверам)
        // означают address-dependent mapping, то есть симметричный NAT
        final Multimap<String, String> mappings = HashMultimap.create();
        for (final Candidate candidate : srflx) {
            final String base = candidate.relatedAddress();
            if (base == null || "0.0.0.0".equals(base) || candidate.relatedPort() <= 0) {
                continue;
            }
            mappings.put(
                    base + ":" + candidate.relatedPort(),
                    candidate.address() + ":" + candidate.port());
        }
        for (final String base : mappings.keySet()) {
            if (mappings.get(base).size() > 1) {
                return NatType.SYMMETRIC;
            }
        }
        // второе наблюдение дает UDP-TURN: в raddr relay-кандидата лежит внешний адрес, который
        // видит TURN-сервер. WebRTC отправляет STUN и UDP-TURN из одного сокета, поэтому при
        // независимом от адресата отображении разные серверы видят один и тот же внешний адрес
        final Multimap<String, String> observers = HashMultimap.create();
        for (final Candidate candidate : candidates) {
            final var server = IceServerUrl.parse(candidate.serverUrl());
            if (server == null || !server.isUdp()) {
                continue;
            }
            final String mapped;
            if (candidate.isType("srflx") && asIpv4(candidate.address()) != null) {
                mapped = candidate.address() + ":" + candidate.port();
            } else if (candidate.isType("relay") && asIpv4(candidate.relatedAddress()) != null) {
                mapped = candidate.relatedAddress() + ":" + candidate.relatedPort();
            } else {
                continue;
            }
            observers.put(mapped, server.endpoint());
        }
        if (ImmutableSet.copyOf(observers.values()).size() < 2) {
            return NatType.UNDETERMINED;
        }
        for (final String mapped : observers.keySet()) {
            if (observers.get(mapped).size() > 1) {
                return NatType.CONE;
            }
        }
        return NatType.SYMMETRIC;
    }

    public static Route routeOf(final Candidate local, final Candidate remote) {
        if (local == null || remote == null) {
            return null;
        }
        final boolean localRelay = local.isType("relay");
        final boolean remoteRelay = remote.isType("relay");
        if (localRelay && remoteRelay) {
            return Route.BOTH_RELAYS;
        } else if (localRelay) {
            return Route.LOCAL_RELAY;
        } else if (remoteRelay) {
            return Route.REMOTE_RELAY;
        } else if (local.isType("host")
                && remote.isType("host")
                && isPrivateAddress(local.address())
                && isPrivateAddress(remote.address())) {
            return Route.LOCAL_NETWORK;
        } else {
            return Route.DIRECT;
        }
    }

    private static final List<String> PAIR_STATE_ORDER =
            List.of("succeeded", "in-progress", "waiting", "frozen", "failed");

    /**
     * Пары кандидатов из RTCStatsReport, приведенного к id -> поля, где поле "type" - тип записи.
     * Выбранная пара идет первой, затем по состоянию проверки.
     */
    public static List<PairStats> parsePairStats(final Map<String, Map<String, Object>> report) {
        final Set<String> selected = new LinkedHashSet<>();
        for (final var stats : report.values()) {
            if ("transport".equals(stats.get("type"))
                    && stats.get("selectedCandidatePairId") instanceof String id) {
                selected.add(id);
            }
        }
        final List<PairStats> pairs = new ArrayList<>();
        for (final var entry : report.entrySet()) {
            final var stats = entry.getValue();
            if (!"candidate-pair".equals(stats.get("type"))) {
                continue;
            }
            final Candidate local = candidateOf(report.get(stats.get("localCandidateId")));
            final Candidate remote = candidateOf(report.get(stats.get("remoteCandidateId")));
            if (local == null || remote == null) {
                continue;
            }
            final long responses = longOf(stats.get("responsesReceived"));
            final long rtt;
            if (stats.get("currentRoundTripTime") instanceof Number current) {
                rtt = Math.round(current.doubleValue() * 1000);
            } else if (stats.get("totalRoundTripTime") instanceof Number total && responses > 0) {
                rtt = Math.round(total.doubleValue() * 1000 / responses);
            } else {
                rtt = -1;
            }
            pairs.add(
                    new PairStats(
                            local,
                            remote,
                            stats.get("state") instanceof String state ? state : "unknown",
                            Boolean.TRUE.equals(stats.get("nominated")),
                            selected.contains(entry.getKey()),
                            rtt,
                            longOf(stats.get("requestsSent")),
                            responses));
        }
        final Comparator<PairStats> order =
                Comparator.<PairStats, Boolean>comparing(p -> !p.selected())
                        .thenComparing(
                                p -> {
                                    final int index = PAIR_STATE_ORDER.indexOf(p.state());
                                    return index < 0 ? PAIR_STATE_ORDER.size() : index;
                                })
                        .thenComparing(PairStats::requestsSent, Ordering.natural().reverse());
        pairs.sort(order);
        return pairs;
    }

    static Candidate candidateOf(final Map<String, Object> stats) {
        if (stats == null) {
            return null;
        }
        final Object address =
                stats.containsKey("address") ? stats.get("address") : stats.get("ip");
        if (!(address instanceof String host)
                || !(stats.get("candidateType") instanceof String type)) {
            return null;
        }
        final Object relatedAddress = stats.get("relatedAddress");
        return new Candidate(
                type,
                stats.get("protocol") instanceof String protocol ? protocol : "",
                host,
                (int) longOf(stats.get("port")),
                relatedAddress instanceof String related && !related.isEmpty() ? related : null,
                stats.containsKey("relatedPort") ? (int) longOf(stats.get("relatedPort")) : -1,
                stats.get("url") instanceof String url && !url.isEmpty() ? url : null);
    }

    private static long longOf(final Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static boolean isPrivateAddress(final String address) {
        if (address == null || !InetAddresses.isInetAddress(address)) {
            return false;
        }
        final InetAddress inetAddress = InetAddresses.forString(address);
        if (inetAddress instanceof Inet6Address) {
            // fc00::/7 (ULA) и fe80::/10
            return inetAddress.isLinkLocalAddress() || (inetAddress.getAddress()[0] & 0xfe) == 0xfc;
        }
        return !isPublicIpv4(address);
    }

    private static Inet4Address asIpv4(final String address) {
        if (address == null || !InetAddresses.isInetAddress(address)) {
            return null;
        }
        final InetAddress inetAddress = InetAddresses.forString(address);
        return inetAddress instanceof Inet4Address inet4Address ? inet4Address : null;
    }

    private static boolean isPublicIpv4(final String address) {
        final Inet4Address inetAddress = asIpv4(address);
        return inetAddress != null
                && !inetAddress.isSiteLocalAddress()
                && !inetAddress.isLoopbackAddress()
                && !inetAddress.isLinkLocalAddress()
                && !inetAddress.isAnyLocalAddress()
                && !inetAddress.isMulticastAddress()
                && !isCarrierGradeNat(address);
    }

    // 100.64.0.0/10 (RFC 6598)
    static boolean isCarrierGradeNat(final String address) {
        final Inet4Address inetAddress = asIpv4(address);
        if (inetAddress == null) {
            return false;
        }
        final byte[] bytes = inetAddress.getAddress();
        return (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64;
    }
}
