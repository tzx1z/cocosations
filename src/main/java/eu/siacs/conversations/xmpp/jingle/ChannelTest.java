package eu.siacs.conversations.xmpp.jingle;

import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.Nullable;
import com.google.common.util.concurrent.SettableFuture;
import eu.siacs.conversations.Config;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RTCStatsReport;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

/**
 * Проверка канала через свой TURN без собеседника. Две PeerConnection на устройстве соединяются
 * relay-кандидатами, и одна передает другой по DataChannel поток с частотой и размером голосовых
 * пакетов. Трафик идет тем же путем, что у звонка через relay: устройство - TURN - устройство,
 * через те же порты сервера и тот же VPN или прокси.
 */
public final class ChannelTest {

    public static final int PACKETS = 300;
    public static final long PACKET_INTERVAL_MILLIS = 20;
    // с заголовками SCTP и DTLS пакет на проводе близок по размеру к RTP-пакету Opus
    private static final int PACKET_SIZE = 120;
    private static final long SDP_TIMEOUT_SECONDS = 5;
    private static final long CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final long DRAIN_MILLIS = 1000;
    private static final long STATS_TIMEOUT_SECONDS = 2;

    public enum Status {
        OK,
        NO_DATA,
        NO_CONNECTION,
        NO_RELAY,
        NOT_OFFERED,
        SKIPPED
    }

    /**
     * Поток пакетов в одну сторону. Отправитель и получатель на одних часах, поэтому delay - путь
     * устройство - TURN - устройство целиком. Время в миллисекундах.
     */
    public record Stream(
            int sent, int received, @Nullable CallQuality.Stats delay, long jitterMillis) {

        public double loss() {
            return sent == 0 ? 0 : (sent - received) * 100.0 / sent;
        }

        /**
         * Петля проходит путь до TURN в обе стороны, как звонок с собеседником в такой же сети
         * через тот же сервер, поэтому ее задержка считается задержкой звонка в одну сторону.
         */
        public double mos() {
            if (delay == null) {
                return -1;
            }
            return CallQuality.mos(Math.round(delay.average()) * 2, jitterMillis, loss());
        }

        /** Время отправки и получения в наносекундах, -1 - пакет не отправлен или потерян. */
        static Stream of(
                final long[] sentAt, final long[] receivedAt, final int[] order, final int count) {
            int sent = 0;
            int received = 0;
            final double[] delays = new double[sentAt.length];
            for (int i = 0; i < sentAt.length; i++) {
                delays[i] = -1;
                if (sentAt[i] < 0) {
                    continue;
                }
                sent++;
                if (receivedAt[i] >= 0) {
                    received++;
                    delays[i] = (receivedAt[i] - sentAt[i]) / 1e6;
                }
            }
            // RFC 3550, 6.4.1: по соседним пакетам в порядке прихода
            double jitter = 0;
            for (int k = 1; k < count; k++) {
                final int previous = order[k - 1];
                final int current = order[k];
                final long difference =
                        (receivedAt[current] - receivedAt[previous])
                                - (sentAt[current] - sentAt[previous]);
                jitter += (Math.abs(difference) / 1e6 - jitter) / 16;
            }
            return new Stream(sent, received, CallQuality.Stats.of(delays), Math.round(jitter));
        }
    }

    /**
     * reflexive - вторая сторона соединилась через внешний адрес, потому что TURN не пропустил
     * relay - relay. local и remote - выбранная пара кандидатов, network - тип сети локального
     * кандидата по данным WebRTC. Время отсчитывается от начала проверки транспорта.
     */
    public record Result(
            String transport,
            Status status,
            boolean reflexive,
            long allocationMillis,
            long connectMillis,
            @Nullable CallDiagnostics.Candidate local,
            @Nullable CallDiagnostics.Candidate remote,
            @Nullable String network,
            @Nullable Stream stream) {

        static Result of(final String transport, final Status status) {
            return new Result(transport, status, false, -1, -1, null, null, null, null);
        }

        public double mos() {
            return stream == null ? -1 : stream.mos();
        }

        @Nullable
        public CallQuality.Rating rating() {
            return CallQuality.rating(mos());
        }
    }

    private ChannelTest() {}

    /**
     * turn - TURN-серверы одного транспорта, stun - серверы для внешнего адреса второй стороны в
     * режиме reflexive.
     */
    static Result run(
            final PeerConnectionFactory factory,
            final String transport,
            final List<PeerConnection.IceServer> turn,
            final List<PeerConnection.IceServer> stun,
            final boolean reflexive)
            throws InterruptedException {
        final long started = SystemClock.elapsedRealtime();
        final long base = System.nanoTime();
        final var receiver = new Receiver(base);
        final var caller = new Side(started, null);
        final var callee = new Side(started, receiver);
        caller.other = callee;
        callee.other = caller;
        try {
            caller.create(factory, WebRTCWrapper.buildConfiguration(turn, false, true));
            callee.create(
                    factory,
                    reflexive
                            ? WebRTCWrapper.buildConfiguration(
                                    stun.isEmpty() ? turn : stun, false, false)
                            : WebRTCWrapper.buildConfiguration(turn, false, true));
            negotiate(caller, callee);
            final long deadline = started + CONNECT_TIMEOUT_MILLIS;
            final boolean connected =
                    caller.open.await(CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                            && callee.open.await(
                                    Math.max(0, deadline - SystemClock.elapsedRealtime()),
                                    TimeUnit.MILLISECONDS);
            if (!connected) {
                final boolean allocated =
                        caller.relayMillis >= 0 && (reflexive || callee.relayMillis >= 0);
                return new Result(
                        transport,
                        allocated ? Status.NO_CONNECTION : Status.NO_RELAY,
                        reflexive,
                        caller.relayMillis,
                        -1,
                        null,
                        null,
                        null,
                        null);
            }
            final long[] sentAt = send(caller.dataChannel, base);
            Thread.sleep(DRAIN_MILLIS);
            final var stream = receiver.stream(sentAt);
            final var report = stats(caller.peerConnection);
            final var pair = selectedPair(report);
            final var local = pair == null ? null : report.get(pair.get("localCandidateId"));
            final var remote = pair == null ? null : report.get(pair.get("remoteCandidateId"));
            return new Result(
                    transport,
                    stream.received() > 0 ? Status.OK : Status.NO_DATA,
                    reflexive,
                    caller.relayMillis,
                    caller.openMillis,
                    candidate(local, caller.candidates),
                    candidate(remote, callee.candidates),
                    network(local),
                    stream);
        } catch (final ExecutionException | TimeoutException e) {
            Log.w(Config.LOGTAG, "channel test over " + transport + " failed", e);
            return Result.of(transport, Status.NO_CONNECTION);
        } finally {
            // close() выполняется на signaling-потоке и ждет его коллбэки, поэтому после него
            // кандидаты больше не передаются на освобожденный PeerConnection
            caller.close();
            callee.close();
            caller.dispose();
            callee.dispose();
        }
    }

    private static void negotiate(final Side caller, final Side callee)
            throws ExecutionException, InterruptedException, TimeoutException {
        final var offer =
                create(
                        observer ->
                                caller.peerConnection.createOffer(
                                        observer, new MediaConstraints()));
        apply(caller.peerConnection::setLocalDescription, offer);
        apply(callee.peerConnection::setRemoteDescription, offer);
        callee.onRemoteDescription();
        final var answer =
                create(
                        observer ->
                                callee.peerConnection.createAnswer(
                                        observer, new MediaConstraints()));
        apply(callee.peerConnection::setLocalDescription, answer);
        apply(caller.peerConnection::setRemoteDescription, answer);
        caller.onRemoteDescription();
    }

    private static long[] send(final DataChannel channel, final long base)
            throws InterruptedException {
        final long[] sentAt = new long[PACKETS];
        Arrays.fill(sentAt, -1);
        final byte[] payload = new byte[PACKET_SIZE];
        final long interval = TimeUnit.MILLISECONDS.toNanos(PACKET_INTERVAL_MILLIS);
        final long start = System.nanoTime();
        for (int seq = 0; seq < PACKETS; seq++) {
            ByteBuffer.wrap(payload).putInt(0, seq);
            sentAt[seq] = System.nanoTime() - base;
            // send() копирует данные, поэтому массив можно переиспользовать
            channel.send(new DataChannel.Buffer(ByteBuffer.wrap(payload), true));
            final long delay = start + (seq + 1) * interval - System.nanoTime();
            if (delay > 0) {
                TimeUnit.NANOSECONDS.sleep(delay);
            }
        }
        return sentAt;
    }

    private static Map<String, Map<String, Object>> stats(final PeerConnection peerConnection)
            throws InterruptedException {
        final SettableFuture<RTCStatsReport> future = SettableFuture.create();
        peerConnection.getStats(future::set);
        try {
            return JingleRtpConnection.asMap(future.get(STATS_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (final ExecutionException | TimeoutException e) {
            return Collections.emptyMap();
        }
    }

    @Nullable
    private static Map<String, Object> selectedPair(final Map<String, Map<String, Object>> report) {
        for (final var stats : report.values()) {
            if ("transport".equals(stats.get("type"))
                    && stats.get("selectedCandidatePairId") instanceof String id) {
                return report.get(id);
            }
        }
        return null;
    }

    // relatedAddress и url есть не во всех версиях статистики, их дополняет кандидат из SDP
    @Nullable
    private static CallDiagnostics.Candidate candidate(
            @Nullable final Map<String, Object> stats,
            final List<CallDiagnostics.Candidate> gathered) {
        final var candidate = CallDiagnostics.candidateOf(stats);
        if (candidate == null || candidate.relatedAddress() != null) {
            return candidate;
        }
        synchronized (gathered) {
            for (final var c : gathered) {
                if (c.isType(candidate.type())
                        && c.port() == candidate.port()
                        && c.address().equals(candidate.address())) {
                    return c;
                }
            }
        }
        return candidate;
    }

    // на Android при VPN networkAdapterType - сеть под VPN, а сам VPN отмечен полем vpn
    @Nullable
    private static String network(@Nullable final Map<String, Object> stats) {
        if (stats == null) {
            return null;
        }
        final String adapter =
                stats.get("networkAdapterType") instanceof String type
                                && !type.isEmpty()
                                && !"unknown".equals(type)
                        ? type
                        : null;
        final boolean vpn = Boolean.TRUE.equals(stats.get("vpn"));
        if (vpn && adapter != null && !"vpn".equals(adapter)) {
            return "vpn, " + adapter;
        }
        return vpn ? "vpn" : adapter;
    }

    static SessionDescription create(final Consumer<SdpObserver> call)
            throws ExecutionException, InterruptedException, TimeoutException {
        final SettableFuture<SessionDescription> future = SettableFuture.create();
        call.accept(
                new Sdp() {
                    @Override
                    public void onCreateSuccess(final SessionDescription description) {
                        future.set(description);
                    }

                    @Override
                    public void onCreateFailure(final String error) {
                        future.setException(new IllegalStateException(error));
                    }
                });
        return future.get(SDP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    static void apply(
            final BiConsumer<SdpObserver, SessionDescription> call,
            final SessionDescription description)
            throws ExecutionException, InterruptedException, TimeoutException {
        final SettableFuture<Void> future = SettableFuture.create();
        call.accept(
                new Sdp() {
                    @Override
                    public void onSetSuccess() {
                        future.set(null);
                    }

                    @Override
                    public void onSetFailure(final String error) {
                        future.setException(new IllegalStateException(error));
                    }
                },
                description);
        future.get(SDP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static final class Receiver {

        private final long base;
        private final long[] receivedAt = new long[PACKETS];
        private final int[] order = new int[PACKETS];
        private int count;

        private Receiver(final long base) {
            this.base = base;
            Arrays.fill(receivedAt, -1);
        }

        private synchronized void onPacket(final int seq) {
            if (seq < 0 || seq >= PACKETS || receivedAt[seq] >= 0) {
                return;
            }
            receivedAt[seq] = System.nanoTime() - base;
            order[count++] = seq;
        }

        private synchronized Stream stream(final long[] sentAt) {
            return Stream.of(sentAt, receivedAt, order, count);
        }
    }

    private static final class Side implements PeerConnection.Observer, DataChannel.Observer {

        private final long started;
        @Nullable private final Receiver receiver;
        private final List<CallDiagnostics.Candidate> candidates =
                Collections.synchronizedList(new ArrayList<>());
        private final List<IceCandidate> pending = new ArrayList<>();
        private final CountDownLatch open = new CountDownLatch(1);
        private volatile Side other;
        private volatile PeerConnection peerConnection;
        private volatile DataChannel dataChannel;
        private boolean remoteDescription;
        private volatile long relayMillis = -1;
        private volatile long openMillis = -1;

        private Side(final long started, @Nullable final Receiver receiver) {
            this.started = started;
            this.receiver = receiver;
        }

        private void create(
                final PeerConnectionFactory factory,
                final PeerConnection.RTCConfiguration configuration) {
            final var peerConnection = factory.createPeerConnection(configuration, this);
            if (peerConnection == null) {
                throw new IllegalStateException("Unable to create PeerConnection");
            }
            this.peerConnection = peerConnection;
            // канал согласован заранее с одним id у обеих сторон и без повторов, как RTP
            final var init = new DataChannel.Init();
            init.negotiated = true;
            init.id = 0;
            init.ordered = false;
            init.maxRetransmits = 0;
            final var dataChannel = peerConnection.createDataChannel("channel-test", init);
            if (dataChannel == null) {
                throw new IllegalStateException("Unable to create DataChannel");
            }
            this.dataChannel = dataChannel;
            dataChannel.registerObserver(this);
        }

        // кандидаты второй стороны приходят раньше, чем у этой появится remote description
        private void addRemoteCandidate(final IceCandidate candidate) {
            synchronized (pending) {
                if (!remoteDescription) {
                    pending.add(candidate);
                    return;
                }
            }
            peerConnection.addIceCandidate(candidate);
        }

        private void onRemoteDescription() {
            final List<IceCandidate> candidates;
            synchronized (pending) {
                remoteDescription = true;
                candidates = new ArrayList<>(pending);
                pending.clear();
            }
            for (final var candidate : candidates) {
                peerConnection.addIceCandidate(candidate);
            }
        }

        private void close() {
            final var peerConnection = this.peerConnection;
            if (peerConnection != null) {
                peerConnection.close();
            }
        }

        private void dispose() {
            final var dataChannel = this.dataChannel;
            if (dataChannel != null) {
                dataChannel.unregisterObserver();
                dataChannel.dispose();
            }
            final var peerConnection = this.peerConnection;
            if (peerConnection != null) {
                peerConnection.dispose();
            }
        }

        @Override
        public void onIceCandidate(final IceCandidate iceCandidate) {
            final var candidate =
                    CallDiagnostics.Candidate.parse(iceCandidate.sdp, iceCandidate.serverUrl);
            if (candidate != null) {
                candidates.add(candidate);
                if (candidate.isType("relay") && relayMillis < 0) {
                    relayMillis = SystemClock.elapsedRealtime() - started;
                }
            }
            other.addRemoteCandidate(iceCandidate);
        }

        @Override
        public void onStateChange() {
            final var dataChannel = this.dataChannel;
            if (dataChannel != null
                    && dataChannel.state() == DataChannel.State.OPEN
                    && open.getCount() > 0) {
                openMillis = SystemClock.elapsedRealtime() - started;
                open.countDown();
            }
        }

        @Override
        public void onMessage(final DataChannel.Buffer buffer) {
            if (receiver != null && buffer.data.remaining() >= Integer.BYTES) {
                receiver.onPacket(buffer.data.getInt(buffer.data.position()));
            }
        }

        @Override
        public void onBufferedAmountChange(final long previousAmount) {}

        @Override
        public void onSignalingChange(final PeerConnection.SignalingState state) {}

        @Override
        public void onIceConnectionChange(final PeerConnection.IceConnectionState state) {}

        @Override
        public void onIceConnectionReceivingChange(final boolean receiving) {}

        @Override
        public void onIceGatheringChange(final PeerConnection.IceGatheringState state) {}

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

    private abstract static class Sdp implements SdpObserver {

        @Override
        public void onCreateSuccess(final SessionDescription description) {}

        @Override
        public void onSetSuccess() {}

        @Override
        public void onCreateFailure(final String error) {}

        @Override
        public void onSetFailure(final String error) {}
    }
}
