package eu.siacs.conversations.xmpp.jingle;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.manager.ExternalServiceDiscoveryManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.IceCandidateErrorEvent;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpTransceiver;
import org.webrtc.audio.JavaAudioDeviceModule;

/**
 * Проверка канала для звонков без звонка: STUN/TURN по XEP-0215, сбор кандидатов отдельным
 * PeerConnection, тип NAT, выделение TURN и поток данных через TURN по каждому транспорту.
 */
public final class NetworkCheck {

    private static final long DISCOVERY_TIMEOUT_SECONDS = 10;
    private static final long GATHERING_TIMEOUT_SECONDS = 15;
    private static final int MAX_HISTORY = 10;
    private static final List<String> TRANSPORTS = List.of("udp", "tcp", "tls");

    private static final ListeningExecutorService EXECUTOR =
            MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());

    public enum ServerStatus {
        OK,
        ERROR,
        NO_RESPONSE
    }

    public enum TransportStatus {
        OK,
        FAILED,
        NOT_OFFERED
    }

    public enum Verdict {
        OK,
        NO_ICE_SERVERS,
        NO_TURN,
        TURN_FAILED,
        UDP_BLOCKED,
        SYMMETRIC_NAT
    }

    public enum ChannelVerdict {
        GOOD,
        FAIR,
        POOR,
        NO_TRAFFIC
    }

    public enum Stage {
        DISCOVERY,
        GATHERING,
        CHANNEL
    }

    /** transport - проверяемый транспорт на этапе CHANNEL, partial - результат на этот момент. */
    public record Progress(Stage stage, @Nullable String transport, @Nullable Result partial) {}

    public interface Listener {
        void onProgress(String account, Progress progress);
    }

    /** candidate - srflx для STUN или relay для TURN, полученный от этого сервера. */
    public record Server(
            CallDiagnostics.IceServerUrl url,
            ServerStatus status,
            @Nullable CallDiagnostics.Candidate candidate,
            List<CallDiagnostics.IceCandidateError> errors) {}

    /**
     * network - активная сеть устройства по ConnectivityManager, channels - в порядке TRANSPORTS.
     */
    public record Result(
            long startedAt,
            CallDiagnostics.IceServerDiscovery discovery,
            List<CallDiagnostics.Candidate> candidates,
            List<Server> servers,
            long gatheringMillis,
            boolean gatheringComplete,
            @Nullable String network,
            List<ChannelTest.Result> channels) {

        public CallDiagnostics.NatType natType() {
            final int stunServers =
                    (int) servers.stream().filter(server -> !server.url().isTurn()).count();
            return CallDiagnostics.detectNatType(candidates, stunServers, false);
        }

        public boolean carrierGradeNat() {
            return Iterables.any(candidates, c -> CallDiagnostics.isCarrierGradeNat(c.address()));
        }

        public boolean hasHostCandidate(final boolean ipv6) {
            return Iterables.any(
                    candidates, c -> c.isType("host") && (c.address().indexOf(':') >= 0) == ipv6);
        }

        public TransportStatus turn(final String transport) {
            boolean offered = false;
            for (final var server : servers) {
                if (server.url().isTurn() && transport.equals(server.url().transport())) {
                    offered = true;
                    if (server.status() == ServerStatus.OK) {
                        return TransportStatus.OK;
                    }
                }
            }
            return offered ? TransportStatus.FAILED : TransportStatus.NOT_OFFERED;
        }

        public Verdict verdict() {
            if (servers.isEmpty()) {
                return Verdict.NO_ICE_SERVERS;
            }
            final boolean turnOffered = Iterables.any(servers, s -> s.url().isTurn());
            final boolean turnWorks =
                    Iterables.any(servers, s -> s.url().isTurn() && s.status() == ServerStatus.OK);
            final boolean srflx = Iterables.any(candidates, c -> c.isType("srflx"));
            // наружу выходит только TCP или TLS: UDP-медиа пойдет через TURN
            if (!srflx && turnWorks && turn("udp") != TransportStatus.OK) {
                return Verdict.UDP_BLOCKED;
            }
            if (!turnOffered) {
                return natType() == CallDiagnostics.NatType.SYMMETRIC
                        ? Verdict.SYMMETRIC_NAT
                        : Verdict.NO_TURN;
            }
            return turnWorks ? Verdict.OK : Verdict.TURN_FAILED;
        }

        /** Адреса, под которыми устройство видят STUN и TURN: при VPN или прокси - их выход. */
        public List<String> externalAddresses() {
            final Set<String> addresses = new LinkedHashSet<>();
            for (final var candidate : candidates) {
                if (candidate.isType("srflx")) {
                    addresses.add(candidate.address());
                } else if (candidate.isType("relay") && candidate.relatedAddress() != null) {
                    addresses.add(candidate.relatedAddress());
                }
            }
            return ImmutableList.copyOf(addresses);
        }

        @Nullable
        public ChannelTest.Result channel(final String transport) {
            return Iterables.find(channels, c -> c.transport().equals(transport), null);
        }

        /**
         * Канал, который выберет звонок через relay: ICE предпочитает relay по UDP, затем TCP,
         * затем TLS, независимо от качества.
         */
        @Nullable
        public ChannelTest.Result callChannel() {
            for (final String transport : TRANSPORTS) {
                final var channel = channel(transport);
                if (channel != null && channel.status() == ChannelTest.Status.OK) {
                    return channel;
                }
            }
            return null;
        }

        /** Рабочий канал с оценкой лучше, чем у канала звонка, если такой есть. */
        @Nullable
        public ChannelTest.Result betterChannel() {
            final var call = callChannel();
            if (call == null || call.rating() == null) {
                return null;
            }
            ChannelTest.Result better = null;
            for (final var channel : channels) {
                if (channel.status() != ChannelTest.Status.OK
                        || channel.rating() == null
                        || channel.rating().compareTo(call.rating()) >= 0) {
                    continue;
                }
                if (better == null || channel.mos() > better.mos()) {
                    better = channel;
                }
            }
            return better;
        }

        /** null - канал не проверялся: TURN нет или не выдал адрес. */
        @Nullable
        public ChannelVerdict channelVerdict() {
            final var call = callChannel();
            if (call != null) {
                final var rating = call.rating();
                if (rating == CallQuality.Rating.GOOD) {
                    return ChannelVerdict.GOOD;
                }
                return rating == CallQuality.Rating.FAIR
                        ? ChannelVerdict.FAIR
                        : ChannelVerdict.POOR;
            }
            final boolean tested =
                    Iterables.any(
                            channels,
                            c ->
                                    c.status() == ChannelTest.Status.NO_CONNECTION
                                            || c.status() == ChannelTest.Status.NO_DATA);
            return tested ? ChannelVerdict.NO_TRAFFIC : null;
        }

        /** TURN не пересылает данные между своими relay-адресами. */
        public boolean relayToRelayBlocked() {
            return Iterables.any(channels, ChannelTest.Result::reflexive);
        }

        private Result withChannels(final List<ChannelTest.Result> channels) {
            return new Result(
                    startedAt,
                    discovery,
                    candidates,
                    servers,
                    gatheringMillis,
                    gatheringComplete,
                    network,
                    ImmutableList.copyOf(channels));
        }

        public static List<String> transports() {
            return TRANSPORTS;
        }
    }

    private NetworkCheck() {}

    // проверка переживает пересоздание экрана, а результат виден при повторном открытии
    private static final Map<String, ListenableFuture<Result>> CHECKS = new HashMap<>();
    private static final Map<String, List<Result>> HISTORY = new HashMap<>();
    // прогресс и слушатели меняются только на главном потоке
    private static final Map<String, Progress> PROGRESS = new HashMap<>();
    private static final Set<Listener> LISTENERS = new HashSet<>();

    /**
     * Запускает проверку для аккаунта или возвращает уже идущую. busy - начался звонок: оставшиеся
     * транспорты не проверяются.
     */
    @MainThread
    public static synchronized ListenableFuture<Result> start(
            final Context context,
            final String account,
            final XmppConnection connection,
            final BooleanSupplier busy) {
        final var existing = CHECKS.get(account);
        if (existing != null && !existing.isDone()) {
            return existing;
        }
        final Context application = context.getApplicationContext();
        final Executor main = ContextCompat.getMainExecutor(application);
        final Consumer<Progress> publish =
                progress ->
                        main.execute(
                                () -> {
                                    PROGRESS.put(account, progress);
                                    for (final var listener : List.copyOf(LISTENERS)) {
                                        listener.onProgress(account, progress);
                                    }
                                });
        publish.accept(new Progress(Stage.DISCOVERY, null, null));
        final var future =
                EXECUTOR.submit(
                        () -> {
                            final var result = check(application, connection, busy, publish);
                            remember(account, result);
                            return result;
                        });
        future.addListener(() -> PROGRESS.remove(account), main);
        CHECKS.put(account, future);
        return future;
    }

    /** Идущая или последняя завершенная проверка аккаунта за время работы процесса. */
    @Nullable
    public static synchronized ListenableFuture<Result> last(final String account) {
        return CHECKS.get(account);
    }

    /** Завершенные проверки аккаунта за время работы процесса, от старых к новым. */
    public static synchronized List<Result> history(final String account) {
        final var history = HISTORY.get(account);
        return history == null ? List.of() : ImmutableList.copyOf(history);
    }

    private static synchronized void remember(final String account, final Result result) {
        final var history = HISTORY.computeIfAbsent(account, a -> new ArrayList<>());
        history.add(result);
        if (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }

    @MainThread
    @Nullable
    public static Progress progress(final String account) {
        return PROGRESS.get(account);
    }

    @MainThread
    public static void addListener(final Listener listener) {
        LISTENERS.add(listener);
    }

    @MainThread
    public static void removeListener(final Listener listener) {
        LISTENERS.remove(listener);
    }

    private static Result check(
            final Context context,
            final XmppConnection connection,
            final BooleanSupplier busy,
            final Consumer<Progress> publish)
            throws InterruptedException, ExecutionException, TimeoutException {
        final long startedAt = System.currentTimeMillis();
        final String network = network(context);
        final var manager = connection.getManager(ExternalServiceDiscoveryManager.class);
        Collection<PeerConnection.IceServer> iceServers = Collections.emptyList();
        CallDiagnostics.IceServerDiscovery discovery;
        if (Config.DISABLE_PROXY_LOOKUP || !manager.hasFeature()) {
            discovery =
                    new CallDiagnostics.IceServerDiscovery(
                            Config.DISABLE_PROXY_LOOKUP
                                    ? CallDiagnostics.IceServerDiscovery.Status.DISABLED
                                    : CallDiagnostics.IceServerDiscovery.Status.NOT_SUPPORTED,
                            0,
                            0,
                            -1,
                            null);
        } else {
            final long started = SystemClock.elapsedRealtime();
            try {
                final var services =
                        manager.getServices().get(DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                iceServers = services.getIceServers();
                discovery =
                        new CallDiagnostics.IceServerDiscovery(
                                iceServers.isEmpty()
                                        ? CallDiagnostics.IceServerDiscovery.Status.EMPTY
                                        : CallDiagnostics.IceServerDiscovery.Status.OK,
                                SystemClock.elapsedRealtime() - started,
                                iceServers.size(),
                                CallDiagnostics.IceServerDiscovery.expiresAt(
                                        Collections2.transform(
                                                services.getServices(),
                                                service -> service.getAttribute("expires"))),
                                null);
            } catch (final ExecutionException | TimeoutException e) {
                discovery =
                        new CallDiagnostics.IceServerDiscovery(
                                CallDiagnostics.IceServerDiscovery.Status.ERROR,
                                SystemClock.elapsedRealtime() - started,
                                0,
                                -1,
                                CallDiagnostics.errorName(
                                        e instanceof ExecutionException && e.getCause() != null
                                                ? e.getCause()
                                                : e));
            }
        }
        publish.accept(new Progress(Stage.GATHERING, null, null));
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                        .setFieldTrials("WebRTC-BindUsingInterfaceName/Enabled/")
                        .createInitializationOptions());
        // медиа в проверке нет, модуль нужен только для явного освобождения вместе с фабрикой
        final var audioDeviceModule =
                JavaAudioDeviceModule.builder(context).createAudioDeviceModule();
        final PeerConnectionFactory factory =
                PeerConnectionFactory.builder()
                        .setAudioDeviceModule(audioDeviceModule)
                        .createPeerConnectionFactory();
        try {
            final var gathering = gather(factory, iceServers);
            final List<Server> servers = new ArrayList<>();
            for (final var iceServer : iceServers) {
                for (final String url : iceServer.urls) {
                    final var parsed = CallDiagnostics.IceServerUrl.parse(url);
                    if (parsed != null) {
                        servers.add(server(parsed, gathering));
                    }
                }
            }
            final var result =
                    new Result(
                            startedAt,
                            discovery,
                            ImmutableList.copyOf(gathering.candidates),
                            ImmutableList.copyOf(servers),
                            gathering.millis,
                            gathering.complete,
                            network,
                            List.of());
            final var stun = select(iceServers, url -> !url.isTurn());
            final List<ChannelTest.Result> channels = new ArrayList<>();
            boolean relayToRelay = true;
            for (final String transport : TRANSPORTS) {
                final var status = result.turn(transport);
                if (status == TransportStatus.NOT_OFFERED) {
                    channels.add(ChannelTest.Result.of(transport, ChannelTest.Status.NOT_OFFERED));
                    continue;
                }
                // TURN не выдал адрес при сборе кандидатов: поток через него не пойдет
                if (status == TransportStatus.FAILED) {
                    channels.add(ChannelTest.Result.of(transport, ChannelTest.Status.NO_RELAY));
                    continue;
                }
                if (busy.getAsBoolean()) {
                    channels.add(ChannelTest.Result.of(transport, ChannelTest.Status.SKIPPED));
                    continue;
                }
                publish.accept(
                        new Progress(Stage.CHANNEL, transport, result.withChannels(channels)));
                final var turn =
                        select(
                                iceServers,
                                url -> url.isTurn() && transport.equals(url.transport()));
                var channel = ChannelTest.run(factory, transport, turn, stun, !relayToRelay);
                // relay - relay не соединился при выделенных адресах: проверяется, пропускает ли
                // сервер данные на внешний адрес; если да, дело в пересылке между relay-адресами
                if (relayToRelay && channel.status() == ChannelTest.Status.NO_CONNECTION) {
                    final var reflexive = ChannelTest.run(factory, transport, turn, stun, true);
                    if (reflexive.status() == ChannelTest.Status.OK) {
                        Log.d(Config.LOGTAG, "TURN does not relay between its own allocations");
                        relayToRelay = false;
                        channel = reflexive;
                    }
                }
                channels.add(channel);
            }
            return result.withChannels(channels);
        } finally {
            factory.dispose();
            audioDeviceModule.release();
        }
    }

    /** Отдельный IceServer на каждый подходящий URL с учетными данными исходного. */
    private static List<PeerConnection.IceServer> select(
            final Collection<PeerConnection.IceServer> iceServers,
            final Predicate<CallDiagnostics.IceServerUrl> filter) {
        final List<PeerConnection.IceServer> selected = new ArrayList<>();
        for (final var iceServer : iceServers) {
            for (final String url : iceServer.urls) {
                final var parsed = CallDiagnostics.IceServerUrl.parse(url);
                if (parsed == null || !filter.test(parsed)) {
                    continue;
                }
                final var builder =
                        PeerConnection.IceServer.builder(url)
                                .setTlsCertPolicy(iceServer.tlsCertPolicy)
                                .setHostname(iceServer.hostname);
                if (iceServer.username != null && iceServer.password != null) {
                    builder.setUsername(iceServer.username).setPassword(iceServer.password);
                }
                selected.add(builder.createIceServer());
            }
        }
        return selected;
    }

    @Nullable
    private static String network(final Context context) {
        try {
            final var manager = context.getSystemService(ConnectivityManager.class);
            final var capabilities =
                    manager == null
                            ? null
                            : manager.getNetworkCapabilities(manager.getActiveNetwork());
            if (capabilities == null) {
                return null;
            }
            final List<String> transports = new ArrayList<>();
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                transports.add("vpn");
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                transports.add("wifi");
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                transports.add("cellular");
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                transports.add("ethernet");
            }
            return transports.isEmpty() ? null : String.join(", ", transports);
        } catch (final RuntimeException e) {
            Log.d(Config.LOGTAG, "unable to read network capabilities", e);
            return null;
        }
    }

    private static Server server(
            final CallDiagnostics.IceServerUrl url, final Gathering gathering) {
        final String type = url.isTurn() ? "relay" : "srflx";
        CallDiagnostics.Candidate candidate = null;
        for (final var c : gathering.candidates) {
            if (c.isType(type) && sameServer(url, c.serverUrl())) {
                candidate = c;
                break;
            }
        }
        final List<CallDiagnostics.IceCandidateError> errors = new ArrayList<>();
        for (final var error : gathering.errors.values()) {
            if (sameServer(url, error.url())) {
                errors.add(error);
            }
        }
        final ServerStatus status;
        if (candidate != null) {
            status = ServerStatus.OK;
        } else if (!errors.isEmpty()) {
            status = ServerStatus.ERROR;
        } else {
            status = ServerStatus.NO_RESPONSE;
        }
        return new Server(url, status, candidate, ImmutableList.copyOf(errors));
    }

    // WebRTC собирает адрес сервера заново (без скобок IPv6, со своим порядком параметров),
    // поэтому строки сравниваются по разобранным частям
    static boolean sameServer(
            final CallDiagnostics.IceServerUrl url, @Nullable final String reported) {
        final var other = CallDiagnostics.IceServerUrl.parse(reported);
        return other != null
                && url.isTurn() == other.isTurn()
                && url.endpoint().equals(other.endpoint())
                && Objects.equals(url.transport(), other.transport());
    }

    private static final class Gathering {
        private final List<CallDiagnostics.Candidate> candidates =
                Collections.synchronizedList(new ArrayList<>());
        private final Map<String, CallDiagnostics.IceCandidateError> errors =
                Collections.synchronizedMap(new LinkedHashMap<>());
        private final CountDownLatch done = new CountDownLatch(1);
        private long millis;
        private boolean complete;
    }

    private static Gathering gather(
            final PeerConnectionFactory factory,
            final Collection<PeerConnection.IceServer> iceServers)
            throws InterruptedException, ExecutionException, TimeoutException {
        final var gathering = new Gathering();
        // trickle=false: кандидаты собираются один раз, как для offer со всеми кандидатами
        final PeerConnection peerConnection =
                factory.createPeerConnection(
                        WebRTCWrapper.buildConfiguration(iceServers, false, false),
                        new Observer(gathering));
        if (peerConnection == null) {
            throw new IllegalStateException("Unable to create PeerConnection");
        }
        try {
            // медиа нужно только для m-line в offer; прием без своего трека не включает микрофон
            peerConnection.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    new RtpTransceiver.RtpTransceiverInit(
                            RtpTransceiver.RtpTransceiverDirection.RECV_ONLY));
            final var offer =
                    ChannelTest.create(
                            observer ->
                                    peerConnection.createOffer(observer, new MediaConstraints()));
            final long started = SystemClock.elapsedRealtime();
            ChannelTest.apply(peerConnection::setLocalDescription, offer);
            gathering.complete = gathering.done.await(GATHERING_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            gathering.millis = SystemClock.elapsedRealtime() - started;
        } finally {
            peerConnection.dispose();
        }
        return gathering;
    }

    private static class Observer implements PeerConnection.Observer {

        private final Gathering gathering;

        private Observer(final Gathering gathering) {
            this.gathering = gathering;
        }

        @Override
        public void onIceCandidate(final IceCandidate iceCandidate) {
            final var candidate =
                    CallDiagnostics.Candidate.parse(iceCandidate.sdp, iceCandidate.serverUrl);
            if (candidate != null) {
                gathering.candidates.add(candidate);
            }
        }

        @Override
        public void onIceCandidateError(final IceCandidateErrorEvent event) {
            final String key = event.url + " " + event.errorCode;
            synchronized (gathering.errors) {
                final var existing = gathering.errors.get(key);
                gathering.errors.put(
                        key,
                        new CallDiagnostics.IceCandidateError(
                                event.url,
                                event.errorCode,
                                event.errorText == null ? "" : event.errorText,
                                existing == null ? 1 : existing.count() + 1));
            }
        }

        @Override
        public void onIceGatheringChange(final PeerConnection.IceGatheringState state) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                gathering.done.countDown();
            }
        }

        @Override
        public void onSignalingChange(final PeerConnection.SignalingState state) {}

        @Override
        public void onIceConnectionChange(final PeerConnection.IceConnectionState state) {}

        @Override
        public void onIceConnectionReceivingChange(final boolean receiving) {}

        @Override
        public void onIceCandidatesRemoved(final IceCandidate[] candidates) {}

        @Override
        public void onAddStream(final MediaStream stream) {}

        @Override
        public void onRemoveStream(final MediaStream stream) {}

        @Override
        public void onDataChannel(final DataChannel channel) {}

        @Override
        public void onRenegotiationNeeded() {}
    }
}
