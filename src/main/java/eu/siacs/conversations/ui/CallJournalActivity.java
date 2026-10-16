package eu.siacs.conversations.ui;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityCallJournalBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.ui.util.CallReport;
import eu.siacs.conversations.ui.util.CallReportFormat;
import eu.siacs.conversations.ui.util.CallStageBar;
import eu.siacs.conversations.utils.TimeFrameUtils;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.jingle.CallDiagnostics;
import eu.siacs.conversations.xmpp.jingle.CallQuality;
import eu.siacs.conversations.xmpp.jingle.CallReportStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Журнал отчетов о звонках всех аккаунтов с фильтром по результату. */
public class CallJournalActivity extends XmppActivity {

    private enum Filter {
        ALL,
        SUCCESSFUL,
        FAILED,
        CANCELLED,
        POOR_QUALITY
    }

    private ActivityCallJournalBinding binding;
    private final Adapter adapter = new Adapter();
    private List<CallReportStore.Item> items = List.of();
    private Filter filter = Filter.ALL;
    private boolean loaded = false;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_call_journal);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
        setTitle(R.string.cocosations_call_journal);
        binding.list.setLayoutManager(new LinearLayoutManager(this));
        binding.list.setAdapter(adapter);
        binding.filters.setOnCheckedStateChangeListener(
                (group, checkedIds) -> {
                    if (checkedIds.isEmpty()) {
                        return;
                    }
                    final int id = checkedIds.get(0);
                    if (id == R.id.filter_successful) {
                        filter = Filter.SUCCESSFUL;
                    } else if (id == R.id.filter_failed) {
                        filter = Filter.FAILED;
                    } else if (id == R.id.filter_cancelled) {
                        filter = Filter.CANCELLED;
                    } else if (id == R.id.filter_poor_quality) {
                        filter = Filter.POOR_QUALITY;
                    } else {
                        filter = Filter.ALL;
                    }
                    show();
                });
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.cocosations_call_journal, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == R.id.action_clear_journal) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.cocosations_journal_clear_confirm)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(
                            R.string.cocosations_journal_clear, (dialog, which) -> clear())
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void refreshUiReal() {}

    // вызывается и при возврате на экран: журнал перечитывается с отчетом о новом звонке
    @Override
    protected void onBackendConnected() {
        load();
    }

    private void load() {
        Futures.addCallback(
                CallReportStore.of(this).listAsync(),
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(final List<CallReportStore.Item> result) {
                        items = result;
                        loaded = true;
                        show();
                    }

                    @Override
                    public void onFailure(@NonNull final Throwable throwable) {
                        items = List.of();
                        loaded = true;
                        show();
                    }
                },
                ContextCompat.getMainExecutor(this));
    }

    private void clear() {
        Futures.addCallback(
                CallReportStore.of(this).clearAsync(),
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(final Void result) {
                        load();
                    }

                    @Override
                    public void onFailure(@NonNull final Throwable throwable) {
                        load();
                    }
                },
                ContextCompat.getMainExecutor(this));
    }

    private void show() {
        final List<CallReportStore.Item> visible = new ArrayList<>();
        for (final var item : items) {
            if (matches(item.snapshot())) {
                visible.add(item);
            }
        }
        adapter.submit(visible);
        binding.empty.setVisibility(loaded && visible.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private boolean matches(final CallDiagnostics.Snapshot snapshot) {
        final boolean failed = snapshot.stage().isFailure();
        return switch (filter) {
            case ALL -> true;
            case SUCCESSFUL -> !failed && snapshot.connectedMillis() > 0;
            case FAILED -> failed;
            case CANCELLED -> !failed && snapshot.connectedMillis() == 0;
            case POOR_QUALITY -> {
                final var quality = snapshot.quality();
                final var rating = quality == null ? null : quality.rating();
                yield rating == CallQuality.Rating.FAIR || rating == CallQuality.Rating.POOR;
            }
        };
    }

    private void open(final CallReportStore.Item item) {
        if (xmppConnectionService == null || item.snapshot().sessionId() == null) {
            return;
        }
        final Account account = xmppConnectionService.findAccountByUuid(item.account());
        if (account != null) {
            CallDiagnosticsActivity.launch(this, account, item.snapshot().sessionId());
        }
    }

    private String displayName(final CallReportStore.Item item) {
        final String with = item.snapshot().with();
        if (xmppConnectionService == null) {
            return with;
        }
        final Account account = xmppConnectionService.findAccountByUuid(item.account());
        if (account == null) {
            return with;
        }
        try {
            final var contact = account.getRoster().getContactFromContactList(Jid.of(with));
            return contact == null ? with : contact.getDisplayName();
        } catch (final IllegalArgumentException e) {
            return with;
        }
    }

    private int statusColor(final CallReport.Status status) {
        return switch (status) {
            case OK -> ContextCompat.getColor(this, R.color.cocosations_status_ok);
            case WARNING -> ContextCompat.getColor(this, R.color.cocosations_status_warning);
            case ERROR ->
                    MaterialColors.getColor(
                            binding.getRoot(), androidx.appcompat.R.attr.colorError);
            case NEUTRAL ->
                    MaterialColors.getColor(
                            binding.getRoot(),
                            com.google.android.material.R.attr.colorOnSurfaceVariant);
        };
    }

    private static final DiffUtil.ItemCallback<CallReportStore.Item> DIFF =
            new DiffUtil.ItemCallback<>() {
                @Override
                public boolean areItemsTheSame(
                        @NonNull final CallReportStore.Item a,
                        @NonNull final CallReportStore.Item b) {
                    return a.account().equals(b.account())
                            && Objects.equals(a.snapshot().sessionId(), b.snapshot().sessionId())
                            && a.snapshot().startedAt() == b.snapshot().startedAt();
                }

                @Override
                public boolean areContentsTheSame(
                        @NonNull final CallReportStore.Item a,
                        @NonNull final CallReportStore.Item b) {
                    return a.equals(b);
                }
            };

    private class Adapter extends ListAdapter<CallReportStore.Item, ViewHolder> {

        private Adapter() {
            super(DIFF);
        }

        private void submit(final List<CallReportStore.Item> items) {
            submitList(items);
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
            return new ViewHolder(
                    LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.cocosations_item_call_journal, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull final ViewHolder holder, final int position) {
            final var item = getItem(position);
            final var snapshot = item.snapshot();
            final var context = holder.itemView.getContext();
            final boolean failed = snapshot.stage().isFailure();
            holder.direction.setImageResource(
                    snapshot.direction() == CallDiagnostics.Direction.OUTGOING
                            ? R.drawable.ic_call_made_24dp
                            : R.drawable.ic_call_received_24dp);
            holder.direction.setImageTintList(
                    ColorStateList.valueOf(
                            statusColor(
                                    failed ? CallReport.Status.ERROR : CallReport.Status.NEUTRAL)));
            holder.name.setText(displayName(item));
            final var details =
                    new StringBuilder(
                            DateUtils.formatDateTime(
                                    context,
                                    snapshot.startedAt(),
                                    DateUtils.FORMAT_SHOW_DATE
                                            | DateUtils.FORMAT_SHOW_TIME
                                            | DateUtils.FORMAT_ABBREV_MONTH));
            if (!snapshot.media().isEmpty()) {
                details.append(" · ").append(CallStageBar.media(context, snapshot.media()));
            }
            if (snapshot.connectedMillis() > 0) {
                details.append(" · ")
                        .append(
                                TimeFrameUtils.formatElapsedTime(
                                        snapshot.connectedMillis(), false));
            }
            if (item.hasLog()) {
                details.append(" · ").append(context.getString(R.string.cocosations_journal_log));
            }
            holder.details.setText(details);
            holder.result.setText(CallStageBar.describe(context, snapshot));
            holder.result.setTextColor(
                    statusColor(failed ? CallReport.Status.ERROR : CallReport.Status.NEUTRAL));
            final var quality = snapshot.quality();
            final var rating = quality == null ? null : quality.rating();
            if (rating == null) {
                holder.quality.setVisibility(View.GONE);
            } else {
                holder.quality.setText(CallReportFormat.rating(rating));
                holder.quality.setTextColor(statusColor(CallReport.status(rating)));
                holder.quality.setVisibility(View.VISIBLE);
            }
            holder.itemView.setOnClickListener(v -> open(item));
        }
    }

    private static class ViewHolder extends RecyclerView.ViewHolder {

        private final ImageView direction;
        private final TextView name;
        private final TextView details;
        private final TextView result;
        private final TextView quality;

        private ViewHolder(@NonNull final View view) {
            super(view);
            this.direction = view.findViewById(R.id.direction);
            this.name = view.findViewById(R.id.name);
            this.details = view.findViewById(R.id.details);
            this.result = view.findViewById(R.id.result);
            this.quality = view.findViewById(R.id.quality);
        }
    }
}
