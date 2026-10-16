package eu.siacs.conversations.ui.util;

import android.content.Context;
import android.text.format.DateUtils;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import eu.siacs.conversations.BuildConfig;
import eu.siacs.conversations.R;
import eu.siacs.conversations.xmpp.jingle.CallDiagnostics;
import eu.siacs.conversations.xmpp.jingle.ChannelTest;
import eu.siacs.conversations.xmpp.jingle.NetworkCheck;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Отчет о проверке канала для звонков в тех же разделах, что и отчет о звонке. */
public final class NetworkCheckReport {

    private NetworkCheckReport() {}

    /** history - завершенные проверки аккаунта от старых к новым, для таблицы сравнения. */
    public static CallReport of(
            final Context context,
            final String account,
            final NetworkCheck.Result result,
            final List<NetworkCheck.Result> history) {
        final var builder = new CallReport.Builder(context);
        builder.section("summary", R.string.cocosations_call_report_summary, true, false);
        builder.status(status(result.verdict()));
        builder.field(R.string.cocosations_network_check_account, account);
        builder.field(
                R.string.cocosations_network_check_time,
                DateUtils.formatDateTime(
                        context,
                        result.startedAt(),
                        DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME));
        builder.field(R.string.cocosations_network_check_network, result.network());
        final var external = result.externalAddresses();
        builder.field(
                R.string.cocosations_network_check_external,
                external.isEmpty()
                        ? context.getString(R.string.cocosations_call_report_none)
                        : String.join(", ", external));
        final var discovery = result.discovery();
        builder.field(
                R.string.cocosations_call_report_external_services,
                CallReportFormat.externalServices(context, discovery),
                switch (discovery.status()) {
                    case OK -> CallReport.Status.NEUTRAL;
                    case ERROR -> CallReport.Status.ERROR;
                    default -> CallReport.Status.WARNING;
                });
        if (discovery.expiresAt() > 0) {
            builder.field(
                    R.string.cocosations_call_report_credentials,
                    CallReportFormat.credentialsExpiry(context, discovery.expiresAt()),
                    discovery.expiresAt() < System.currentTimeMillis()
                            ? CallReport.Status.WARNING
                            : CallReport.Status.NEUTRAL);
        }
        builder.field(
                R.string.cocosations_network_check_gathering,
                result.gatheringComplete()
                        ? context.getString(
                                R.string.cocosations_call_report_milliseconds,
                                result.gatheringMillis())
                        : context.getString(
                                R.string.cocosations_network_check_gathering_incomplete,
                                result.gatheringMillis() / 1000),
                result.gatheringComplete() ? CallReport.Status.NEUTRAL : CallReport.Status.WARNING);
        String nat = context.getString(CallReportFormat.natType(result.natType()));
        if (result.carrierGradeNat()) {
            nat += ", " + context.getString(R.string.cocosations_call_report_cgnat);
        }
        builder.field(
                R.string.cocosations_call_report_nat,
                nat,
                result.natType() == CallDiagnostics.NatType.SYMMETRIC
                                || result.natType() == CallDiagnostics.NatType.UDP_BLOCKED
                        ? CallReport.Status.WARNING
                        : CallReport.Status.NEUTRAL);
        builder.field(R.string.cocosations_network_check_addresses, addresses(context, result));

        channel(builder, context, result);
        if (history.size() > 1) {
            history(builder, context, history);
        }

        builder.section("turn", R.string.cocosations_network_check_turn, true, false);
        for (final String transport : NetworkCheck.Result.transports()) {
            final var status = result.turn(transport);
            builder.field(
                    transport.toUpperCase(Locale.ROOT),
                    context.getString(
                            switch (status) {
                                case OK -> R.string.cocosations_network_check_turn_ok;
                                case FAILED -> R.string.cocosations_network_check_turn_failed;
                                case NOT_OFFERED ->
                                        R.string.cocosations_network_check_turn_not_offered;
                            }),
                    switch (status) {
                        case OK -> CallReport.Status.OK;
                        case FAILED -> CallReport.Status.ERROR;
                        case NOT_OFFERED -> CallReport.Status.NEUTRAL;
                    });
            if (status == NetworkCheck.TransportStatus.FAILED) {
                builder.status(CallReport.Status.ERROR);
            }
        }

        builder.section("servers", R.string.cocosations_network_check_servers, false, false);
        if (result.servers().isEmpty()) {
            builder.text(context.getString(R.string.cocosations_call_report_none));
        }
        for (final var server : result.servers()) {
            builder.field(server.url().url(), serverValue(context, server), status(server));
            if (server.status() != NetworkCheck.ServerStatus.OK) {
                builder.status(CallReport.Status.WARNING);
            }
        }

        final var candidates = result.candidates();
        builder.section(
                "candidates",
                context.getString(R.string.cocosations_call_report_local_candidates)
                        + " ("
                        + candidates.size()
                        + ")",
                false,
                true);
        if (candidates.isEmpty()) {
            builder.text(context.getString(R.string.cocosations_call_report_none));
        }
        for (final var candidate : candidates) {
            builder.text(candidate.toString());
        }
        return builder.build(
                BuildConfig.APP_NAME
                        + " "
                        + BuildConfig.VERSION_NAME
                        + " - "
                        + context.getString(R.string.cocosations_network_check),
                context.getString(R.string.cocosations_call_report_recommendation)
                        + ": "
                        + verdict(context, result));
    }

    private static void channel(
            final CallReport.Builder builder,
            final Context context,
            final NetworkCheck.Result result) {
        builder.section("channel", R.string.cocosations_network_check_channel, true, false);
        builder.field(
                R.string.cocosations_network_check_channel_load,
                context.getResources()
                        .getQuantityString(
                                R.plurals.cocosations_network_check_channel_load_value,
                                ChannelTest.PACKETS,
                                ChannelTest.PACKETS,
                                ChannelTest.PACKETS * ChannelTest.PACKET_INTERVAL_MILLIS / 1000));
        for (final String transport : NetworkCheck.Result.transports()) {
            final var channel = result.channel(transport);
            final CallReport.Status status =
                    channel == null ? CallReport.Status.NEUTRAL : status(channel);
            builder.field(name(transport), channelValue(context, channel), status);
            builder.status(status);
        }

        builder.section(
                "channel_details", R.string.cocosations_network_check_channel_details, false, true);
        boolean any = false;
        for (final var channel : result.channels()) {
            final var stream = channel.stream();
            if (stream == null) {
                continue;
            }
            any = true;
            final String name = name(channel.transport());
            builder.field(
                    context.getString(R.string.cocosations_network_check_channel_connect, name),
                    context.getString(
                            R.string.cocosations_network_check_channel_connect_value,
                            channel.connectMillis(),
                            channel.allocationMillis()),
                    CallReport.Status.NEUTRAL);
            final var delay = stream.delay();
            if (delay != null) {
                builder.field(
                        context.getString(R.string.cocosations_network_check_channel_delay, name),
                        context.getString(
                                R.string.cocosations_network_check_channel_delay_value,
                                Math.round(delay.average()),
                                Math.round(delay.p95()),
                                Math.round(delay.max())),
                        CallReport.Status.NEUTRAL);
            }
            builder.field(
                    context.getString(R.string.cocosations_network_check_channel_packets, name),
                    stream.received() + " / " + stream.sent(),
                    CallReport.Status.NEUTRAL);
            builder.field(
                    context.getString(R.string.cocosations_network_check_channel_network, name),
                    channel.network(),
                    CallReport.Status.NEUTRAL);
            builder.field(
                    context.getString(R.string.cocosations_network_check_channel_path, name),
                    context.getString(
                            channel.reflexive()
                                    ? R.string.cocosations_network_check_channel_path_reflexive
                                    : R.string.cocosations_network_check_channel_path_relay),
                    channel.reflexive() ? CallReport.Status.WARNING : CallReport.Status.NEUTRAL);
            if (channel.local() != null) {
                builder.text(channel.local().toString());
            }
            if (channel.remote() != null) {
                builder.text(channel.remote().toString());
            }
        }
        if (!any) {
            builder.text(context.getString(R.string.cocosations_call_report_none));
        }
    }

    private static void history(
            final CallReport.Builder builder,
            final Context context,
            final List<NetworkCheck.Result> history) {
        builder.section("history", R.string.cocosations_network_check_history, true, false);
        final List<String> header = new ArrayList<>();
        header.add(context.getString(R.string.cocosations_network_check_history_address));
        header.add(context.getString(R.string.cocosations_network_check_history_time));
        for (final String transport : NetworkCheck.Result.transports()) {
            header.add(name(transport));
        }
        final List<List<String>> rows = new ArrayList<>();
        for (final var result : Lists.reverse(history)) {
            final List<String> row = new ArrayList<>();
            row.add(Iterables.getFirst(result.externalAddresses(), "-"));
            row.add(
                    DateUtils.formatDateTime(
                            context, result.startedAt(), DateUtils.FORMAT_SHOW_TIME));
            for (final String transport : NetworkCheck.Result.transports()) {
                row.add(historyCell(context, result.channel(transport)));
            }
            rows.add(row);
        }
        builder.row(new CallReport.Table(header, rows));
    }

    private static String historyCell(
            final Context context, @Nullable final ChannelTest.Result channel) {
        if (channel == null) {
            return "-";
        }
        return switch (channel.status()) {
            case OK -> CallReportFormat.mos(channel.mos());
            case NO_DATA, NO_CONNECTION, NO_RELAY ->
                    context.getString(R.string.cocosations_network_check_history_failed);
            case NOT_OFFERED, SKIPPED -> "-";
        };
    }

    private static String channelValue(
            final Context context, @Nullable final ChannelTest.Result channel) {
        if (channel == null) {
            return context.getString(R.string.cocosations_network_check_channel_pending);
        }
        return switch (channel.status()) {
            case OK -> {
                final var stream = channel.stream();
                yield context.getString(
                        R.string.cocosations_network_check_channel_value,
                        CallReportFormat.mos(channel.mos()),
                        delayMillis(stream),
                        stream == null ? -1 : stream.jitterMillis(),
                        CallReportFormat.percent(context, stream == null ? -1 : stream.loss()));
            }
            case NO_DATA -> context.getString(R.string.cocosations_network_check_channel_no_data);
            case NO_CONNECTION ->
                    context.getString(R.string.cocosations_network_check_channel_no_connection);
            case NO_RELAY -> context.getString(R.string.cocosations_network_check_channel_no_relay);
            case NOT_OFFERED ->
                    context.getString(R.string.cocosations_network_check_turn_not_offered);
            case SKIPPED -> context.getString(R.string.cocosations_network_check_channel_skipped);
        };
    }

    private static CallReport.Status status(final ChannelTest.Result channel) {
        return switch (channel.status()) {
            case OK -> CallReport.status(channel.rating());
            case NO_DATA, NO_CONNECTION, NO_RELAY -> CallReport.Status.ERROR;
            case NOT_OFFERED, SKIPPED -> CallReport.Status.NEUTRAL;
        };
    }

    private static long delayMillis(@Nullable final ChannelTest.Stream stream) {
        return stream == null || stream.delay() == null ? -1 : Math.round(stream.delay().average());
    }

    private static String name(final String transport) {
        return transport.toUpperCase(Locale.ROOT);
    }

    /** Статус итога: по каналу, если он проверялся, иначе по сбору кандидатов. */
    public static CallReport.Status status(final NetworkCheck.Result result) {
        final var channel = result.channelVerdict();
        if (channel == null) {
            return status(result.verdict());
        }
        return switch (channel) {
            case GOOD -> CallReport.Status.OK;
            case FAIR -> CallReport.Status.WARNING;
            case POOR, NO_TRAFFIC -> CallReport.Status.ERROR;
        };
    }

    public static String verdict(final Context context, final NetworkCheck.Result result) {
        final var channel = result.channelVerdict();
        if (channel == null) {
            return context.getString(verdict(result.verdict()));
        }
        final var builder = new StringBuilder();
        final var call = result.callChannel();
        if (call == null) {
            builder.append(
                    context.getString(R.string.cocosations_network_check_verdict_no_traffic));
        } else {
            final var stream = call.stream();
            builder.append(
                    context.getString(
                            switch (channel) {
                                case GOOD ->
                                        R.string.cocosations_network_check_verdict_channel_good;
                                case FAIR ->
                                        R.string.cocosations_network_check_verdict_channel_fair;
                                default -> R.string.cocosations_network_check_verdict_channel_poor;
                            },
                            name(call.transport()),
                            delayMillis(stream),
                            CallReportFormat.percent(
                                    context, stream == null ? -1 : stream.loss())));
            final var better = result.betterChannel();
            if (better != null) {
                builder.append(' ')
                        .append(
                                context.getString(
                                        R.string.cocosations_network_check_verdict_better,
                                        name(better.transport()),
                                        CallReportFormat.mos(better.mos()),
                                        name(call.transport())));
            }
        }
        if (result.relayToRelayBlocked()) {
            builder.append(' ')
                    .append(
                            context.getString(
                                    R.string.cocosations_network_check_verdict_relay_to_relay));
        }
        return builder.toString();
    }

    public static CallReport.Status status(final NetworkCheck.Verdict verdict) {
        return switch (verdict) {
            case OK -> CallReport.Status.OK;
            case NO_TURN, UDP_BLOCKED -> CallReport.Status.WARNING;
            default -> CallReport.Status.ERROR;
        };
    }

    @StringRes
    public static int verdict(final NetworkCheck.Verdict verdict) {
        return switch (verdict) {
            case OK -> R.string.cocosations_network_check_verdict_ok;
            case NO_ICE_SERVERS -> R.string.cocosations_network_check_verdict_no_ice_servers;
            case NO_TURN -> R.string.cocosations_network_check_verdict_no_turn;
            case TURN_FAILED -> R.string.cocosations_network_check_verdict_turn_failed;
            case UDP_BLOCKED -> R.string.cocosations_network_check_verdict_udp_blocked;
            case SYMMETRIC_NAT -> R.string.cocosations_network_check_verdict_symmetric_nat;
        };
    }

    private static CallReport.Status status(final NetworkCheck.Server server) {
        return switch (server.status()) {
            case OK -> CallReport.Status.OK;
            case ERROR -> CallReport.Status.ERROR;
            case NO_RESPONSE -> CallReport.Status.WARNING;
        };
    }

    private static String serverValue(final Context context, final NetworkCheck.Server server) {
        final var candidate = server.candidate();
        if (candidate != null) {
            final String address = endpoint(candidate.address(), candidate.port());
            if (candidate.isType("relay") && candidate.relatedAddress() != null) {
                return context.getString(
                        R.string.cocosations_network_check_server_relay,
                        address,
                        endpoint(candidate.relatedAddress(), candidate.relatedPort()));
            }
            return context.getString(R.string.cocosations_network_check_server_mapped, address);
        }
        if (!server.errors().isEmpty()) {
            final var builder = new StringBuilder();
            for (final var error : server.errors()) {
                if (builder.length() > 0) {
                    builder.append("; ");
                }
                builder.append(
                        context.getString(
                                R.string.cocosations_network_check_server_error,
                                error.code(),
                                error.text()));
            }
            return builder.toString();
        }
        return context.getString(R.string.cocosations_network_check_server_no_response);
    }

    private static String endpoint(final String address, final int port) {
        return address.indexOf(':') >= 0 ? "[" + address + "]:" + port : address + ":" + port;
    }

    private static String addresses(final Context context, final NetworkCheck.Result result) {
        final boolean ipv4 = result.hasHostCandidate(false);
        final boolean ipv6 = result.hasHostCandidate(true);
        if (ipv4 && ipv6) {
            return context.getString(R.string.cocosations_network_check_addresses_both);
        } else if (ipv4) {
            return "IPv4";
        } else if (ipv6) {
            return "IPv6";
        }
        return context.getString(R.string.cocosations_call_report_none);
    }
}
