package eu.siacs.conversations.xmpp.jingle;

import android.content.Context;
import android.util.Log;
import androidx.annotation.Nullable;
import com.google.common.base.Enums;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.siacs.conversations.Config;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * Журнал отчетов о звонках в файлах приватной папки приложения: файл на звонок, имя {@code
 * <начало>_<хеш контакта>_<хеш session id>.json}. Схема БД не меняется, поэтому миграции upstream
 * не затрагиваются.
 */
public class CallReportStore {

    private static final int FORMAT = 4;
    private static final String DIRECTORY = "cocosations_call_journal";
    // до журнала хранился один отчет на контакт в другом формате
    private static final String LEGACY_DIRECTORY = "cocosations_call_reports";
    private static final String EXPORT_DIRECTORY = "cocosations_share";
    private static final String REPORT_SUFFIX = ".json";
    private static final String LOG_SUFFIX = ".log";
    private static final int HASH_LENGTH = 16;

    // запись, чтение и ротация идут через один поток: файл не читается во время записи
    private static final ListeningExecutorService EXECUTOR =
            MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());

    private final File directory;
    @Nullable private final File legacyDirectory;

    public CallReportStore(final File directory) {
        this(directory, null);
    }

    private CallReportStore(final File directory, @Nullable final File legacyDirectory) {
        this.directory = directory;
        this.legacyDirectory = legacyDirectory;
    }

    public static CallReportStore of(final Context context) {
        return new CallReportStore(
                new File(context.getFilesDir(), DIRECTORY),
                new File(context.getFilesDir(), LEGACY_DIRECTORY));
    }

    /** Отчет в журнале; startedAt и хеши берутся из имени файла без чтения содержимого. */
    public record Entry(
            String account, long startedAt, String withHash, String sessionHash, File file) {

        public File log() {
            final String name = file.getName();
            return new File(
                    file.getParentFile(),
                    name.substring(0, name.length() - REPORT_SUFFIX.length()) + LOG_SUFFIX);
        }

        @Nullable
        static Entry of(final String account, final File file) {
            final String name = file.getName();
            if (!name.endsWith(REPORT_SUFFIX)) {
                return null;
            }
            final String[] parts =
                    name.substring(0, name.length() - REPORT_SUFFIX.length()).split("_");
            if (parts.length != 3) {
                return null;
            }
            try {
                return new Entry(account, Long.parseLong(parts[0]), parts[1], parts[2], file);
            } catch (final NumberFormatException e) {
                return null;
            }
        }
    }

    public record Item(String account, CallDiagnostics.Snapshot snapshot, boolean hasLog) {}

    /** capture - лог звонка; он склеивается в файл рядом с отчетом. */
    public void saveAsync(
            final String account,
            final CallDiagnostics.Snapshot snapshot,
            final int limit,
            @Nullable final CallLogCapture capture) {
        EXECUTOR.execute(
                () -> {
                    File log = null;
                    try {
                        final var entry = save(account, snapshot);
                        log = entry.log();
                        rotate(limit);
                    } catch (final IOException e) {
                        Log.w(Config.LOGTAG, "unable to store call report", e);
                    }
                    if (capture != null) {
                        capture.finish(log);
                    }
                });
    }

    public ListenableFuture<CallDiagnostics.Snapshot> loadLatestAsync(
            final String account, final String with) {
        return EXECUTOR.submit(() -> loadLatest(account, with));
    }

    public ListenableFuture<CallDiagnostics.Snapshot> loadSessionAsync(
            final String account, final String sessionId) {
        return EXECUTOR.submit(() -> loadSession(account, sessionId));
    }

    /** Все отчеты всех аккаунтов, новые первыми. */
    public ListenableFuture<List<Item>> listAsync() {
        return EXECUTOR.submit(this::list);
    }

    public ListenableFuture<Void> clearAsync() {
        return EXECUTOR.submit(
                () -> {
                    for (final var entry : entries()) {
                        delete(entry);
                    }
                    return null;
                });
    }

    /** Есть ли отчет о звонке; читает только список файлов, поэтому годится для UI-потока. */
    public boolean hasSession(final String account, final String sessionId) {
        return findSession(account, sessionId) != null;
    }

    /** В отчетах IP-адреса собеседников, они не должны переживать удаление аккаунта. */
    public void deleteAccountAsync(final String account) {
        EXECUTOR.execute(() -> deleteAccount(account));
    }

    void deleteAccount(final String account) {
        deleteRecursively(new File(directory, account));
    }

    Entry save(final String account, final CallDiagnostics.Snapshot snapshot) throws IOException {
        dropLegacy();
        final File parent = new File(directory, account);
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Unable to create " + parent);
        }
        final String name =
                snapshot.startedAt()
                        + "_"
                        + hash(snapshot.with().toLowerCase(Locale.ROOT))
                        + "_"
                        + hash(Strings.nullToEmpty(snapshot.sessionId()))
                        + REPORT_SUFFIX;
        final File file = new File(parent, name);
        // временный файл и rename: оборванная запись не оставляет поврежденный отчет
        final File temporary = new File(parent, name + ".tmp");
        Files.asCharSink(temporary, StandardCharsets.UTF_8).write(toJson(snapshot));
        if (!temporary.renameTo(file)) {
            throw new IOException("Unable to rename " + temporary);
        }
        return Entry.of(account, file);
    }

    /** Оставляет limit самых новых отчетов по всем аккаунтам вместе с их логами. */
    void rotate(final int limit) {
        final List<Entry> entries = entries();
        for (int i = Math.max(0, limit); i < entries.size(); i++) {
            delete(entries.get(i));
        }
    }

    @Nullable
    CallDiagnostics.Snapshot loadLatest(final String account, final String with) {
        final String withHash = hash(with.toLowerCase(Locale.ROOT));
        for (final var entry : entries(account)) {
            if (entry.withHash().equals(withHash)) {
                return read(entry.file());
            }
        }
        return null;
    }

    @Nullable
    CallDiagnostics.Snapshot loadSession(final String account, final String sessionId) {
        final var entry = findSession(account, sessionId);
        return entry == null ? null : read(entry.file());
    }

    @Nullable
    public File logOf(final String account, final String sessionId) {
        final var entry = findSession(account, sessionId);
        return entry == null || !entry.log().isFile() ? null : entry.log();
    }

    List<Item> list() {
        dropLegacy();
        final ImmutableList.Builder<Item> items = new ImmutableList.Builder<>();
        for (final var entry : entries()) {
            final var snapshot = read(entry.file());
            if (snapshot != null) {
                items.add(new Item(entry.account(), snapshot, entry.log().isFile()));
            }
        }
        return items.build();
    }

    @Nullable
    private Entry findSession(final String account, final String sessionId) {
        final String sessionHash = hash(sessionId);
        for (final var entry : entries(account)) {
            if (entry.sessionHash().equals(sessionHash)) {
                return entry;
            }
        }
        return null;
    }

    private List<Entry> entries() {
        final List<Entry> entries = new ArrayList<>();
        final File[] accounts = directory.listFiles(File::isDirectory);
        if (accounts != null) {
            for (final File account : accounts) {
                entries.addAll(entries(account.getName()));
            }
        }
        entries.sort(Comparator.comparingLong(Entry::startedAt).reversed());
        return entries;
    }

    private List<Entry> entries(final String account) {
        final List<Entry> entries = new ArrayList<>();
        final File[] files = new File(directory, account).listFiles();
        if (files != null) {
            for (final File file : files) {
                final var entry = Entry.of(account, file);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }
        entries.sort(Comparator.comparingLong(Entry::startedAt).reversed());
        return entries;
    }

    private static void delete(final Entry entry) {
        for (final File file : List.of(entry.file(), entry.log())) {
            if (file.exists() && !file.delete()) {
                Log.w(Config.LOGTAG, "unable to delete " + file);
            }
        }
    }

    private void dropLegacy() {
        if (legacyDirectory != null && legacyDirectory.exists()) {
            deleteRecursively(legacyDirectory);
        }
    }

    private static void deleteRecursively(final File file) {
        final File[] children = file.listFiles();
        if (children != null) {
            for (final File child : children) {
                deleteRecursively(child);
            }
        }
        if (file.exists() && !file.delete()) {
            Log.w(Config.LOGTAG, "unable to delete " + file);
        }
    }

    @Nullable
    private static CallDiagnostics.Snapshot read(final File file) {
        try {
            return fromJson(Files.asCharSource(file, StandardCharsets.UTF_8).read());
        } catch (final IOException e) {
            Log.w(Config.LOGTAG, "unable to read call report", e);
            return null;
        }
    }

    private static String hash(final String value) {
        return Hashing.sha256()
                .hashString(value, StandardCharsets.UTF_8)
                .toString()
                .substring(0, HASH_LENGTH);
    }

    /** Копия лога звонка во временной папке для отправки через FileProvider. */
    public static ListenableFuture<File> exportLogAsync(
            final Context context, final String account, final String sessionId) {
        final File directory = new File(context.getCacheDir(), EXPORT_DIRECTORY);
        final var store = of(context);
        return EXECUTOR.submit(
                () -> {
                    final var entry = store.findSession(account, sessionId);
                    if (entry == null || !entry.log().isFile()) {
                        throw new IOException("No call log for this call");
                    }
                    clearExports(directory);
                    final File file =
                            new File(
                                    directory,
                                    "call-"
                                            + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
                                                    .format(new Date(entry.startedAt()))
                                            + ".log");
                    Files.copy(entry.log(), file);
                    return file;
                });
    }

    private static void clearExports(final File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create " + directory);
        }
        // папка нужна только на время отправки, предыдущие выгрузки больше не используются
        final File[] previous = directory.listFiles();
        if (previous != null) {
            for (final File file : previous) {
                if (!file.delete()) {
                    Log.w(Config.LOGTAG, "unable to delete " + file);
                }
            }
        }
    }

    /** Отчет в читаемом JSON во временной папке для отправки через FileProvider. */
    public static ListenableFuture<File> exportAsync(
            final Context context, final CallDiagnostics.Snapshot snapshot) {
        final File directory = new File(context.getCacheDir(), EXPORT_DIRECTORY);
        return EXECUTOR.submit(() -> export(directory, snapshot));
    }

    static File export(final File directory, final CallDiagnostics.Snapshot snapshot)
            throws IOException {
        clearExports(directory);
        final String name =
                "call-"
                        + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
                                .format(new Date(snapshot.startedAt()))
                        + ".json";
        final File file = new File(directory, name);
        final String pretty =
                new GsonBuilder()
                        .setPrettyPrinting()
                        .serializeNulls()
                        .create()
                        .toJson(JsonParser.parseString(toJson(snapshot)));
        Files.asCharSink(file, StandardCharsets.UTF_8).write(pretty);
        return file;
    }

    static String toJson(final CallDiagnostics.Snapshot snapshot) {
        final var json = new JsonObject();
        json.addProperty("format", FORMAT);
        json.addProperty("sessionId", snapshot.sessionId());
        json.addProperty("direction", snapshot.direction().name());
        json.addProperty("with", snapshot.with());
        final var media = new JsonArray();
        for (final Media m : snapshot.media()) {
            media.add(m.name());
        }
        json.add("media", media);
        json.addProperty("federated", snapshot.federated());
        addEnum(json, "federationEvidence", snapshot.federationEvidence());
        json.addProperty("startedAt", snapshot.startedAt());
        json.addProperty("stage", snapshot.stage().name());
        json.addProperty("reached", snapshot.reached().name());
        addEnum(json, "reason", snapshot.reason());
        json.addProperty("condition", snapshot.condition());
        json.addProperty("ended", snapshot.ended());
        addEnum(json, "termination", snapshot.termination());
        json.addProperty("durationMillis", snapshot.durationMillis());
        json.addProperty("connectedMillis", snapshot.connectedMillis());
        final var transitions = new JsonArray();
        for (final var transition : snapshot.transitions()) {
            final var entry = new JsonObject();
            entry.addProperty("stage", transition.stage().name());
            entry.addProperty("at", transition.elapsedMillis());
            transitions.add(entry);
        }
        json.add("transitions", transitions);
        final var events = new JsonArray();
        for (final var event : snapshot.events()) {
            final var entry = new JsonObject();
            entry.addProperty("at", event.elapsedMillis());
            entry.addProperty("type", event.type().name());
            entry.addProperty("detail", event.detail());
            events.add(entry);
        }
        json.add("events", events);
        json.add("endReason", endReason(snapshot.endReason()));
        json.add("peer", peer(snapshot.peer()));
        json.add("iceServerDiscovery", iceServerDiscovery(snapshot.iceServerDiscovery()));
        final var iceCandidateErrors = new JsonArray();
        for (final var error : snapshot.iceCandidateErrors()) {
            final var entry = new JsonObject();
            entry.addProperty("url", error.url());
            entry.addProperty("code", error.code());
            entry.addProperty("text", error.text());
            entry.addProperty("count", error.count());
            iceCandidateErrors.add(entry);
        }
        json.add("iceCandidateErrors", iceCandidateErrors);
        json.add("localCandidates", candidates(snapshot.localCandidates()));
        json.add("remoteCandidates", candidates(snapshot.remoteCandidates()));
        json.addProperty("iceConnectionState", snapshot.iceConnectionState());
        json.addProperty("peerConnectionState", snapshot.peerConnectionState());
        json.addProperty("iceGatheringState", snapshot.iceGatheringState());
        final var iceServers = new JsonArray();
        for (final var iceServer : snapshot.iceServers()) {
            iceServers.add(iceServer.url());
        }
        json.add("iceServers", iceServers);
        json.addProperty("stunServers", snapshot.stunServers());
        json.addProperty("turnServers", snapshot.turnServers());
        json.addProperty("forceRelay", snapshot.forceRelay());
        json.add("selectedLocal", candidate(snapshot.selectedLocal()));
        json.add("selectedRemote", candidate(snapshot.selectedRemote()));
        final var pairs = new JsonArray();
        for (final var pair : snapshot.pairStats()) {
            final var entry = new JsonObject();
            entry.add("local", candidate(pair.local()));
            entry.add("remote", candidate(pair.remote()));
            entry.addProperty("state", pair.state());
            entry.addProperty("nominated", pair.nominated());
            entry.addProperty("selected", pair.selected());
            entry.addProperty("rtt", pair.rttMillis());
            entry.addProperty("requests", pair.requestsSent());
            entry.addProperty("responses", pair.responsesReceived());
            pairs.add(entry);
        }
        json.add("pairStats", pairs);
        json.add("quality", quality(snapshot.quality()));
        json.addProperty("carrierGradeNat", snapshot.carrierGradeNat());
        json.addProperty("natType", snapshot.natType().name());
        json.addProperty("suggestion", snapshot.suggestion().name());
        json.addProperty("version", snapshot.version());
        return json.toString();
    }

    @Nullable
    static CallDiagnostics.Snapshot fromJson(final String text) {
        try {
            final var json = JsonParser.parseString(text).getAsJsonObject();
            if (json.get("format").getAsInt() != FORMAT) {
                return null;
            }
            final var media = new ImmutableSet.Builder<Media>();
            for (final var m : json.getAsJsonArray("media")) {
                media.add(Media.valueOf(m.getAsString()));
            }
            final var transitions = new ImmutableList.Builder<CallDiagnostics.Transition>();
            for (final var element : json.getAsJsonArray("transitions")) {
                final var entry = element.getAsJsonObject();
                transitions.add(
                        new CallDiagnostics.Transition(
                                CallStage.valueOf(entry.get("stage").getAsString()),
                                entry.get("at").getAsLong()));
            }
            final var events = new ImmutableList.Builder<CallDiagnostics.Event>();
            for (final var element : json.getAsJsonArray("events")) {
                final var entry = element.getAsJsonObject();
                final var type =
                        Enums.getIfPresent(
                                CallDiagnostics.EventType.class, entry.get("type").getAsString());
                // событие из более новой версии приложения пропускаем, остальной отчет читаем
                if (type.isPresent()) {
                    events.add(
                            new CallDiagnostics.Event(
                                    entry.get("at").getAsLong(),
                                    type.get(),
                                    string(entry.get("detail"))));
                }
            }
            final var iceCandidateErrors =
                    new ImmutableList.Builder<CallDiagnostics.IceCandidateError>();
            for (final var element : json.getAsJsonArray("iceCandidateErrors")) {
                final var entry = element.getAsJsonObject();
                iceCandidateErrors.add(
                        new CallDiagnostics.IceCandidateError(
                                entry.get("url").getAsString(),
                                entry.get("code").getAsInt(),
                                entry.get("text").getAsString(),
                                entry.get("count").getAsInt()));
            }
            final var iceServers = new ImmutableList.Builder<CallDiagnostics.IceServerUrl>();
            for (final var element : json.getAsJsonArray("iceServers")) {
                final var iceServer = CallDiagnostics.IceServerUrl.parse(element.getAsString());
                if (iceServer != null) {
                    iceServers.add(iceServer);
                }
            }
            final var pairs = new ImmutableList.Builder<CallDiagnostics.PairStats>();
            for (final var element : json.getAsJsonArray("pairStats")) {
                final var entry = element.getAsJsonObject();
                pairs.add(
                        new CallDiagnostics.PairStats(
                                candidateOf(entry.get("local")),
                                candidateOf(entry.get("remote")),
                                entry.get("state").getAsString(),
                                entry.get("nominated").getAsBoolean(),
                                entry.get("selected").getAsBoolean(),
                                entry.get("rtt").getAsLong(),
                                entry.get("requests").getAsLong(),
                                entry.get("responses").getAsLong()));
            }
            final var selectedLocal = candidateOf(json.get("selectedLocal"));
            final var selectedRemote = candidateOf(json.get("selectedRemote"));
            return new CallDiagnostics.Snapshot(
                    CallDiagnostics.Direction.valueOf(json.get("direction").getAsString()),
                    json.get("with").getAsString(),
                    string(json.get("sessionId")),
                    media.build(),
                    json.get("federated").getAsBoolean(),
                    enumOf(
                            CallDiagnostics.FederationEvidence.class,
                            json.get("federationEvidence")),
                    json.get("startedAt").getAsLong(),
                    CallStage.valueOf(json.get("stage").getAsString()),
                    CallStage.valueOf(json.get("reached").getAsString()),
                    enumOf(CallDiagnostics.Reason.class, json.get("reason")),
                    string(json.get("condition")),
                    json.get("ended").getAsBoolean(),
                    enumOf(CallDiagnostics.Termination.class, json.get("termination")),
                    json.get("durationMillis").getAsLong(),
                    json.get("connectedMillis").getAsLong(),
                    transitions.build(),
                    events.build(),
                    endReasonOf(json.get("endReason")),
                    peerOf(json.get("peer")),
                    iceServerDiscoveryOf(json.get("iceServerDiscovery")),
                    iceCandidateErrors.build(),
                    candidateList(json.getAsJsonArray("localCandidates")),
                    candidateList(json.getAsJsonArray("remoteCandidates")),
                    string(json.get("iceConnectionState")),
                    string(json.get("peerConnectionState")),
                    string(json.get("iceGatheringState")),
                    iceServers.build(),
                    json.get("stunServers").getAsInt(),
                    json.get("turnServers").getAsInt(),
                    json.get("forceRelay").getAsBoolean(),
                    selectedLocal,
                    selectedRemote,
                    CallDiagnostics.routeOf(selectedLocal, selectedRemote),
                    pairs.build(),
                    qualityOf(json.get("quality")),
                    json.get("carrierGradeNat").getAsBoolean(),
                    CallDiagnostics.NatType.valueOf(json.get("natType").getAsString()),
                    CallDiagnostics.Suggestion.valueOf(json.get("suggestion").getAsString()),
                    json.get("version").getAsInt());
        } catch (final RuntimeException e) {
            // поврежденный файл или отчет от несовместимой версии приложения
            Log.w(Config.LOGTAG, "unable to parse call report", e);
            return null;
        }
    }

    private static JsonArray candidates(final List<CallDiagnostics.Candidate> candidates) {
        final var array = new JsonArray();
        for (final var candidate : candidates) {
            array.add(candidate(candidate));
        }
        return array;
    }

    private static List<CallDiagnostics.Candidate> candidateList(final JsonArray array) {
        final var builder = new ImmutableList.Builder<CallDiagnostics.Candidate>();
        for (final var element : array) {
            builder.add(candidateOf(element));
        }
        return builder.build();
    }

    private static JsonElement candidate(@Nullable final CallDiagnostics.Candidate candidate) {
        if (candidate == null) {
            return null;
        }
        final var json = new JsonObject();
        json.addProperty("type", candidate.type());
        json.addProperty("protocol", candidate.protocol());
        json.addProperty("address", candidate.address());
        json.addProperty("port", candidate.port());
        json.addProperty("relatedAddress", candidate.relatedAddress());
        json.addProperty("relatedPort", candidate.relatedPort());
        json.addProperty("serverUrl", candidate.serverUrl());
        return json;
    }

    @Nullable
    private static CallDiagnostics.Candidate candidateOf(@Nullable final JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        final var json = element.getAsJsonObject();
        return new CallDiagnostics.Candidate(
                json.get("type").getAsString(),
                json.get("protocol").getAsString(),
                json.get("address").getAsString(),
                json.get("port").getAsInt(),
                string(json.get("relatedAddress")),
                json.get("relatedPort").getAsInt(),
                string(json.get("serverUrl")));
    }

    @Nullable
    private static JsonElement endReason(@Nullable final CallDiagnostics.EndReason endReason) {
        if (endReason == null) {
            return null;
        }
        final var json = new JsonObject();
        json.addProperty("condition", endReason.condition());
        json.addProperty("text", endReason.text());
        json.addProperty("remote", endReason.remote());
        return json;
    }

    @Nullable
    private static CallDiagnostics.EndReason endReasonOf(@Nullable final JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        final var json = element.getAsJsonObject();
        return new CallDiagnostics.EndReason(
                json.get("condition").getAsString(),
                string(json.get("text")),
                json.get("remote").getAsBoolean());
    }

    @Nullable
    private static JsonElement peer(@Nullable final CallDiagnostics.Peer peer) {
        if (peer == null) {
            return null;
        }
        final var json = new JsonObject();
        json.addProperty("address", peer.address());
        json.addProperty("capsKnown", peer.capsKnown());
        json.addProperty("identityName", peer.identityName());
        json.addProperty("identityType", peer.identityType());
        final var features = new JsonArray();
        for (final String feature : peer.features()) {
            features.add(feature);
        }
        json.add("features", features);
        json.addProperty("software", peer.software());
        json.addProperty("softwareVersion", peer.softwareVersion());
        json.addProperty("os", peer.os());
        json.addProperty("softwareError", peer.softwareError());
        return json;
    }

    @Nullable
    private static CallDiagnostics.Peer peerOf(@Nullable final JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        final var json = element.getAsJsonObject();
        final var features = new ImmutableList.Builder<String>();
        for (final var feature : json.getAsJsonArray("features")) {
            features.add(feature.getAsString());
        }
        return new CallDiagnostics.Peer(
                json.get("address").getAsString(),
                json.get("capsKnown").getAsBoolean(),
                string(json.get("identityName")),
                string(json.get("identityType")),
                features.build(),
                string(json.get("software")),
                string(json.get("softwareVersion")),
                string(json.get("os")),
                string(json.get("softwareError")));
    }

    @Nullable
    private static JsonElement iceServerDiscovery(
            @Nullable final CallDiagnostics.IceServerDiscovery discovery) {
        if (discovery == null) {
            return null;
        }
        final var json = new JsonObject();
        json.addProperty("status", discovery.status().name());
        json.addProperty("durationMillis", discovery.durationMillis());
        json.addProperty("services", discovery.services());
        json.addProperty("expiresAt", discovery.expiresAt());
        json.addProperty("error", discovery.error());
        return json;
    }

    @Nullable
    private static CallDiagnostics.IceServerDiscovery iceServerDiscoveryOf(
            @Nullable final JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        final var json = element.getAsJsonObject();
        return new CallDiagnostics.IceServerDiscovery(
                CallDiagnostics.IceServerDiscovery.Status.valueOf(json.get("status").getAsString()),
                json.get("durationMillis").getAsLong(),
                json.get("services").getAsInt(),
                json.get("expiresAt").getAsLong(),
                string(json.get("error")));
    }

    @Nullable
    private static JsonElement quality(@Nullable final CallQuality.Report report) {
        if (report == null) {
            return null;
        }
        final var json = new JsonObject();
        json.add("codecIn", codec(report.codecIn()));
        json.add("codecOut", codec(report.codecOut()));
        final var totals = report.totals();
        final var totalsJson = new JsonObject();
        totalsJson.addProperty("packetsReceived", totals.packetsReceived());
        totalsJson.addProperty("packetsLost", totals.packetsLost());
        totalsJson.addProperty("packetsSent", totals.packetsSent());
        totalsJson.addProperty("remotePacketsLost", totals.remotePacketsLost());
        totalsJson.addProperty("bytesSent", totals.bytesSent());
        totalsJson.addProperty("bytesReceived", totals.bytesReceived());
        totalsJson.addProperty("totalSamples", totals.totalSamples());
        totalsJson.addProperty("concealedSamples", totals.concealedSamples());
        totalsJson.addProperty("jitterBufferDelayMillis", totals.jitterBufferDelayMillis());
        totalsJson.addProperty("jitterBufferEmitted", totals.jitterBufferEmitted());
        json.add("totals", totalsJson);
        final var samples = new JsonArray();
        for (final var sample : report.samples()) {
            samples.add(sample(sample));
        }
        json.add("samples", samples);
        json.add("live", sample(report.live()));
        return json;
    }

    @Nullable
    private static CallQuality.Report qualityOf(@Nullable final JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        final var json = element.getAsJsonObject();
        final var totals = json.getAsJsonObject("totals");
        final var samples = new ImmutableList.Builder<CallQuality.Sample>();
        for (final var sample : json.getAsJsonArray("samples")) {
            samples.add(sampleOf(sample));
        }
        return new CallQuality.Report(
                codecOf(json.get("codecIn")),
                codecOf(json.get("codecOut")),
                new CallQuality.Totals(
                        totals.get("packetsReceived").getAsLong(),
                        totals.get("packetsLost").getAsLong(),
                        totals.get("packetsSent").getAsLong(),
                        totals.get("remotePacketsLost").getAsLong(),
                        totals.get("bytesSent").getAsLong(),
                        totals.get("bytesReceived").getAsLong(),
                        totals.get("totalSamples").getAsLong(),
                        totals.get("concealedSamples").getAsLong(),
                        totals.get("jitterBufferDelayMillis").getAsLong(),
                        totals.get("jitterBufferEmitted").getAsLong()),
                samples.build(),
                sampleOf(json.get("live")));
    }

    @Nullable
    private static JsonElement codec(@Nullable final CallQuality.Codec codec) {
        if (codec == null) {
            return null;
        }
        final var json = new JsonObject();
        json.addProperty("mimeType", codec.mimeType());
        json.addProperty("clockRate", codec.clockRate());
        json.addProperty("channels", codec.channels());
        json.addProperty("fmtp", codec.fmtp());
        return json;
    }

    @Nullable
    private static CallQuality.Codec codecOf(@Nullable final JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        final var json = element.getAsJsonObject();
        return new CallQuality.Codec(
                json.get("mimeType").getAsString(),
                json.get("clockRate").getAsLong(),
                json.get("channels").getAsLong(),
                string(json.get("fmtp")));
    }

    // отрицательное значение - показатель неизвестен
    @Nullable
    private static JsonElement sample(@Nullable final CallQuality.Sample sample) {
        if (sample == null) {
            return null;
        }
        final var json = new JsonObject();
        json.addProperty("at", sample.elapsedMillis());
        json.addProperty("rtt", sample.rttMillis());
        json.addProperty("lossIn", sample.lossIn());
        json.addProperty("lossOut", sample.lossOut());
        json.addProperty("jitterIn", sample.jitterInMillis());
        json.addProperty("jitterOut", sample.jitterOutMillis());
        json.addProperty("bitrateIn", sample.bitrateIn());
        json.addProperty("bitrateOut", sample.bitrateOut());
        json.addProperty("concealed", sample.concealed());
        json.addProperty("mosIn", sample.mosIn());
        json.addProperty("mosOut", sample.mosOut());
        return json;
    }

    @Nullable
    private static CallQuality.Sample sampleOf(@Nullable final JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        final var json = element.getAsJsonObject();
        return new CallQuality.Sample(
                json.get("at").getAsLong(),
                json.get("rtt").getAsLong(),
                json.get("lossIn").getAsDouble(),
                json.get("lossOut").getAsDouble(),
                json.get("jitterIn").getAsLong(),
                json.get("jitterOut").getAsLong(),
                json.get("bitrateIn").getAsLong(),
                json.get("bitrateOut").getAsLong(),
                json.get("concealed").getAsDouble(),
                json.get("mosIn").getAsDouble(),
                json.get("mosOut").getAsDouble());
    }

    private static void addEnum(final JsonObject json, final String name, final Enum<?> value) {
        json.addProperty(name, value == null ? null : value.name());
    }

    @Nullable
    private static <E extends Enum<E>> E enumOf(
            final Class<E> type, @Nullable final JsonElement element) {
        final String value = string(element);
        return Strings.isNullOrEmpty(value) ? null : Enum.valueOf(type, value);
    }

    @Nullable
    private static String string(@Nullable final JsonElement element) {
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }
}
