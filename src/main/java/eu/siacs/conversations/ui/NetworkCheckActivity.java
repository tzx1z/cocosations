package eu.siacs.conversations.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import com.google.android.material.color.MaterialColors;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityNetworkCheckBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.ui.util.CallReport;
import eu.siacs.conversations.ui.util.CallReportView;
import eu.siacs.conversations.ui.util.NetworkCheckReport;
import eu.siacs.conversations.xmpp.jingle.CallDiagnostics;
import eu.siacs.conversations.xmpp.jingle.NetworkCheck;
import eu.siacs.conversations.xmpp.manager.JingleManager;
import java.util.Locale;

/**
 * Проверка канала для звонков без звонка: STUN/TURN, кандидаты, тип NAT, поток данных через TURN по
 * транспортам.
 */
public class NetworkCheckActivity extends XmppActivity implements NetworkCheck.Listener {

    private static final String EXTRA_AUTO_START = "auto_start";

    private ActivityNetworkCheckBinding binding;
    private CallReportView reportView;
    private Account account;
    @Nullable private CallReport report;
    @Nullable private ListenableFuture<NetworkCheck.Result> observed;
    private boolean autoStart;

    public static void launch(final Context context, final Account account) {
        launch(context, account, false);
    }

    /** autoStart - начать проверку сразу, если она еще не идет. */
    public static void launch(
            final Context context, final Account account, final boolean autoStart) {
        final Intent intent = new Intent(context, NetworkCheckActivity.class);
        intent.putExtra(EXTRA_ACCOUNT, account.getJid().asBareJid().toString());
        intent.putExtra(EXTRA_AUTO_START, autoStart);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_network_check);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
        setTitle(R.string.cocosations_network_check);
        this.reportView = new CallReportView(this);
        if (savedInstanceState != null) {
            reportView.restore(savedInstanceState);
        }
        this.autoStart =
                savedInstanceState == null
                        && getIntent() != null
                        && getIntent().getBooleanExtra(EXTRA_AUTO_START, false);
        binding.start.setOnClickListener(v -> start());
    }

    @Override
    public void onStart() {
        super.onStart();
        NetworkCheck.addListener(this);
    }

    @Override
    protected void onStop() {
        NetworkCheck.removeListener(this);
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        reportView.save(outState);
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.cocosations_network_check, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(final Menu menu) {
        menu.findItem(R.id.action_share_text).setVisible(report != null);
        menu.findItem(R.id.action_copy).setVisible(report != null);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        final var report = this.report;
        if (report == null) {
            return super.onOptionsItemSelected(item);
        }
        if (item.getItemId() == R.id.action_copy) {
            if (copyTextToClipboard(report.toText(), R.string.cocosations_network_check)
                    && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(this, R.string.cocosations_call_report_copied, Toast.LENGTH_SHORT)
                        .show();
            }
            return true;
        } else if (item.getItemId() == R.id.action_share_text) {
            final Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.cocosations_network_check));
            intent.putExtra(Intent.EXTRA_TEXT, report.toText());
            try {
                startActivity(
                        Intent.createChooser(
                                intent, getString(R.string.cocosations_call_report_share_text)));
            } catch (final ActivityNotFoundException e) {
                Toast.makeText(this, R.string.no_application_found, Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void refreshUiReal() {}

    @Override
    protected void onBackendConnected() {
        this.account = getIntent() == null ? null : extractAccount(getIntent());
        if (account == null) {
            finish();
            return;
        }
        if (getSupportActionBar() != null) {
            getSupportActionBar().setSubtitle(account.getJid().asBareJid().toString());
        }
        final var last = NetworkCheck.last(account.getUuid());
        if (autoStart && (last == null || last.isDone())) {
            autoStart = false;
            start();
        } else if (last != null) {
            observe(last);
        }
    }

    @Override
    public void onProgress(final String account, final NetworkCheck.Progress progress) {
        if (this.account == null || !this.account.getUuid().equals(account)) {
            return;
        }
        showProgress(progress);
    }

    private void showProgress(final NetworkCheck.Progress progress) {
        binding.status.setText(
                switch (progress.stage()) {
                    case DISCOVERY -> getString(R.string.cocosations_network_check_stage_discovery);
                    case GATHERING -> getString(R.string.cocosations_network_check_stage_gathering);
                    case CHANNEL ->
                            getString(
                                    R.string.cocosations_network_check_stage_channel,
                                    Strings.nullToEmpty(progress.transport())
                                            .toUpperCase(Locale.ROOT));
                });
        binding.status.setVisibility(View.VISIBLE);
        final var partial = progress.partial();
        if (partial != null) {
            render(partial, true);
        }
    }

    private void start() {
        if (account == null || xmppConnectionService == null) {
            return;
        }
        if (!account.isOnlineAndConnected()) {
            showStatus(R.string.cocosations_network_check_offline);
            return;
        }
        // параллельный PeerConnection не должен мешать идущему звонку
        if (JingleManager.isBusy(xmppConnectionService.getAccounts())) {
            showStatus(R.string.cocosations_network_check_busy);
            return;
        }
        final var service = xmppConnectionService;
        observe(
                NetworkCheck.start(
                        this,
                        account.getUuid(),
                        account.getXmppConnection(),
                        () -> JingleManager.isBusy(service.getAccounts())));
    }

    private void observe(final ListenableFuture<NetworkCheck.Result> future) {
        this.observed = future;
        if (!future.isDone()) {
            binding.progress.setVisibility(View.VISIBLE);
            binding.start.setEnabled(false);
            binding.verdictCard.setVisibility(View.GONE);
            final var progress = NetworkCheck.progress(account.getUuid());
            if (progress == null) {
                showStatus(R.string.cocosations_network_check_running);
            } else {
                showProgress(progress);
            }
        }
        Futures.addCallback(
                future,
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(final NetworkCheck.Result result) {
                        if (observed == future) {
                            finished();
                            render(result, false);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull final Throwable throwable) {
                        if (observed == future) {
                            finished();
                            binding.status.setText(
                                    getString(
                                            R.string.cocosations_network_check_failed,
                                            CallDiagnostics.errorName(throwable)));
                            binding.status.setVisibility(View.VISIBLE);
                        }
                    }
                },
                ContextCompat.getMainExecutor(this));
    }

    private void finished() {
        binding.progress.setVisibility(View.GONE);
        binding.start.setEnabled(true);
        binding.start.setText(R.string.cocosations_network_check_restart);
        binding.status.setVisibility(View.GONE);
    }

    private void showStatus(final int text) {
        binding.status.setText(text);
        binding.status.setVisibility(View.VISIBLE);
    }

    /** partial - проверка еще идет: итог не показывается. */
    private void render(final NetworkCheck.Result result, final boolean partial) {
        final var report =
                NetworkCheckReport.of(
                        this,
                        account.getJid().asBareJid().toString(),
                        result,
                        NetworkCheck.history(account.getUuid()));
        this.report = partial ? null : report;
        binding.intro.setVisibility(View.GONE);
        reportView.render(binding.sections, report.sections(), true);
        invalidateOptionsMenu();
        if (partial) {
            binding.verdictCard.setVisibility(View.GONE);
            return;
        }
        final boolean problem = NetworkCheckReport.status(result) == CallReport.Status.ERROR;
        binding.verdictCard.setCardBackgroundColor(
                MaterialColors.getColor(
                        binding.verdictCard,
                        problem
                                ? com.google.android.material.R.attr.colorErrorContainer
                                : com.google.android.material.R.attr.colorSurfaceContainerHighest));
        final int onCard =
                MaterialColors.getColor(
                        binding.verdictCard,
                        problem
                                ? com.google.android.material.R.attr.colorOnErrorContainer
                                : com.google.android.material.R.attr.colorOnSurface);
        binding.verdictTitle.setTextColor(onCard);
        binding.verdict.setTextColor(onCard);
        binding.verdict.setText(NetworkCheckReport.verdict(this, result));
        binding.verdictCard.setVisibility(View.VISIBLE);
    }
}
