package eu.siacs.conversations.xmpp.manager;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.jingle.CallDiagnostics;
import im.conversations.android.xmpp.model.error.Condition;
import im.conversations.android.xmpp.model.last.Query;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** XEP-0012: время выхода офлайн-контактов от их сервера и наше время простоя по запросу. */
public class LastActivityManager extends AbstractManager {

    private static final Duration REQUEST_INTERVAL = Duration.ofMinutes(15);

    /** Откуда взято время последней активности контакта. */
    public enum Source {
        XEP_0319,
        XEP_0256,
        DELAY,
        OBSERVED,
        XEP_0012
    }

    /**
     * Сведения для профиля контакта за время работы процесса. queryResult - "result", "online",
     * условие ошибки XMPP или "timeout"; время 0 - события не было.
     */
    public record Status(
            @Nullable Source source, long sourceAt, long queriedAt, @Nullable String queryResult) {}

    private final Map<Jid, Status> statuses = new ConcurrentHashMap<>();

    // запросы идут из списка контактов при прокрутке, поэтому один контакт спрашиваем не чаще
    // раза в REQUEST_INTERVAL, в том числе после ошибки
    private final Cache<Jid, Boolean> recentRequests =
            CacheBuilder.newBuilder().expireAfterWrite(REQUEST_INTERVAL).maximumSize(2048).build();

    private final XmppConnectionService service;
    private final AppSettings appSettings;

    public LastActivityManager(
            final XmppConnectionService service, final XmppConnection connection) {
        super(service.getApplicationContext(), connection);
        this.service = service;
        this.appSettings = new AppSettings(service.getApplicationContext());
    }

    public void request(final Contact contact) {
        // сервер отвечает за пользователя только тем, кто подписан на его presence
        if (!getAccount().isOnlineAndConnected()
                || contact.isSelf()
                || contact.isBlocked()
                || !contact.getOption(Contact.Options.TO)
                || !contact.getPresences().isEmpty()) {
            return;
        }
        final Jid address = contact.getAddress().asBareJid();
        if (address.getLocal() == null
                || recentRequests.asMap().putIfAbsent(address, true) != null) {
            return;
        }
        final long requestedAt = System.currentTimeMillis();
        final var future = connection.sendIqPacket(new Iq(Iq.Type.GET, address, new Query()));
        recordQuery(address, requestedAt, null);
        Futures.addCallback(
                future,
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(final Iq result) {
                        final var query = result.getExtension(Query.class);
                        final var seconds = query == null ? null : query.getSeconds();
                        recordQuery(
                                address,
                                requestedAt,
                                seconds != null && seconds == 0 ? "online" : "result");
                        // seconds='0' означает, что контакт в сети; это видно по presence
                        if (seconds == null || seconds == 0 || !contact.getPresences().isEmpty()) {
                            return;
                        }
                        if (contact.setLastSeen(requestedAt - seconds * 1000)) {
                            recordSource(address, Source.XEP_0012);
                            getManager(RosterManager.class).writeToDatabaseAsync();
                            service.updateRosterUi();
                            service.updateConversationUi();
                        }
                    }

                    @Override
                    public void onFailure(@NonNull final Throwable throwable) {
                        recordQuery(address, requestedAt, CallDiagnostics.errorName(throwable));
                        Log.d(
                                Config.LOGTAG,
                                getAccount().getJid().asBareJid()
                                        + ": no last activity for "
                                        + address
                                        + " ("
                                        + throwable.getMessage()
                                        + ")");
                    }
                },
                MoreExecutors.directExecutor());
    }

    public void recordSource(final Jid address, final Source source) {
        final long now = System.currentTimeMillis();
        statuses.merge(
                address.asBareJid(),
                new Status(source, now, 0, null),
                (old, update) -> new Status(source, now, old.queriedAt(), old.queryResult()));
    }

    private void recordQuery(
            final Jid address, final long queriedAt, @Nullable final String result) {
        statuses.merge(
                address.asBareJid(),
                new Status(null, 0, queriedAt, result),
                (old, update) -> new Status(old.source(), old.sourceAt(), queriedAt, result));
    }

    @Nullable
    public Status getStatus(final Jid address) {
        return statuses.get(address.asBareJid());
    }

    public void handleRequest(final Iq request) {
        final var from = request.getFrom();
        final var contact =
                from == null
                        ? null
                        : getManager(RosterManager.class).getContactFromContactList(from);
        // время простоя видят только те, кому мы и так отдаем presence с <idle/>
        if (!appSettings.isBroadcastLastActivity()
                || contact == null
                || !contact.getOption(Contact.Options.FROM)) {
            connection.sendErrorFor(request, new Condition.Forbidden());
            return;
        }
        final long lastActivity = service.getLastActivity();
        final long idle =
                lastActivity <= 0
                        ? 0
                        : Math.max(0, (System.currentTimeMillis() - lastActivity) / 1000);
        connection.sendResultFor(request, new Query(idle));
    }
}
