package eu.siacs.conversations.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.preference.PreferenceManager;
import com.google.android.material.color.MaterialColors;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityCallDiagnosticsBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.ui.util.CallReport;
import eu.siacs.conversations.ui.util.CallReportFormat;
import eu.siacs.conversations.ui.util.CallReportView;
import eu.siacs.conversations.ui.util.CallStageBar;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.jingle.CallDiagnostics;
import eu.siacs.conversations.xmpp.jingle.CallReportStore;
import eu.siacs.conversations.xmpp.manager.JingleManager;
import java.io.File;

/** Отчет о звонке: этапы установки, причина сбоя, качество, данные ICE и рекомендация. */
public class CallDiagnosticsActivity extends XmppActivity {

    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_WITH = "with";

    private static final String PREFERENCE_DETAILED = "cocosations_call_report_detailed";
    private static final long REFRESH_INTERVAL_MILLIS = 1000;
    private static final int RENDERED_STORED = Integer.MIN_VALUE;

    private ActivityCallDiagnosticsBinding binding;
    private CallStageBar callStageBar;
    private CallDiagnostics diagnostics;
    private CallDiagnostics.Snapshot storedSnapshot;
    private CallDiagnostics.Snapshot snapshot;
    private boolean loadingStored = false;
    private int renderedVersion = -1;
    private boolean detailed = false;
    private CallReportView reportView;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh =
            new Runnable() {
                @Override
                public void run() {
                    render();
                    if (diagnostics != null && !diagnostics.isEnded()) {
                        handler.postDelayed(this, REFRESH_INTERVAL_MILLIS);
                    }
                }
            };

    public static void launch(
            final Context context, final Account account, final String sessionId) {
        final Intent intent = new Intent(context, CallDiagnosticsActivity.class);
        intent.putExtra(EXTRA_ACCOUNT, account.getJid().asBareJid().toString());
        intent.putExtra(EXTRA_SESSION_ID, sessionId);
        context.startActivity(intent);
    }

    /**
     * Отчет о последнем звонке с контактом: из памяти, если звонок был в этом сеансе, иначе из
     * журнала.
     */
    public static void launchLastCall(
            final Context context, final Account account, final Jid with) {
        final Intent intent = new Intent(context, CallDiagnosticsActivity.class);
        intent.putExtra(EXTRA_ACCOUNT, account.getJid().asBareJid().toString());
        intent.putExtra(EXTRA_WITH, with.asBareJid().toString());
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_call_diagnostics);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
        setTitle(R.string.cocosations_call_diagnostics);
        this.callStageBar = new CallStageBar(binding.callStages);
        this.binding.copyReport.setOnClickListener(this::copyReport);
        this.reportView = new CallReportView(this);
        if (savedInstanceState != null) {
            reportView.restore(savedInstanceState);
        }
        this.detailed =
                PreferenceManager.getDefaultSharedPreferences(this)
                        .getBoolean(PREFERENCE_DETAILED, false);
        binding.mode.check(detailed ? R.id.mode_detailed : R.id.mode_brief);
        binding.mode.addOnButtonCheckedListener(
                (group, checkedId, isChecked) -> {
                    if (!isChecked) {
                        return;
                    }
                    this.detailed = checkedId == R.id.mode_detailed;
                    PreferenceManager.getDefaultSharedPreferences(this)
                            .edit()
                            .putBoolean(PREFERENCE_DETAILED, detailed)
                            .apply();
                    this.renderedVersion = -1;
                    render();
                });
    }

    @Override
    protected void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        reportView.save(outState);
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.cocosations_call_diagnostics, menu);
        return super.onCreateOptionsMenu(menu);
    }

    // лог склеивается через пару секунд после звонка, поэтому проверяем при каждом открытии меню
    @Override
    public boolean onPrepareOptionsMenu(final Menu menu) {
        final MenuItem shareLog = menu.findItem(R.id.action_share_log);
        final Account account = getIntent() == null ? null : extractAccount(getIntent());
        final var snapshot = this.snapshot;
        shareLog.setVisible(
                account != null
                        && snapshot != null
                        && snapshot.sessionId() != null
                        && CallReportStore.of(this).logOf(account.getUuid(), snapshot.sessionId())
                                != null);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == R.id.action_share_text) {
            shareText();
            return true;
        } else if (itemId == R.id.action_share_json) {
            shareJson();
            return true;
        } else if (itemId == R.id.action_share_log) {
            shareLog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void refreshUiReal() {}

    @Override
    protected void onBackendConnected() {
        final Intent intent = getIntent();
        final Account account = intent == null ? null : extractAccount(intent);
        final String sessionId = intent == null ? null : intent.getStringExtra(EXTRA_SESSION_ID);
        final String with = intent == null ? null : intent.getStringExtra(EXTRA_WITH);
        final var jingleManager =
                account == null
                        ? null
                        : account.getXmppConnection().getManager(JingleManager.class);
        if (jingleManager == null) {
            this.diagnostics = null;
        } else if (with != null) {
            this.diagnostics = jingleManager.getLatestCallDiagnostics(Jid.of(with));
            if (diagnostics == null && storedSnapshot == null && !loadingStored) {
                loadStoredReport(CallReportStore.of(this).loadLatestAsync(account.getUuid(), with));
            }
        } else {
            this.diagnostics = jingleManager.getCallDiagnostics(sessionId);
            if (diagnostics == null
                    && sessionId != null
                    && storedSnapshot == null
                    && !loadingStored) {
                loadStoredReport(
                        CallReportStore.of(this).loadSessionAsync(account.getUuid(), sessionId));
            }
        }
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    private void loadStoredReport(final ListenableFuture<CallDiagnostics.Snapshot> future) {
        this.loadingStored = true;
        Futures.addCallback(
                future,
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(final CallDiagnostics.Snapshot snapshot) {
                        loadingStored = false;
                        storedSnapshot = snapshot;
                        render();
                    }

                    @Override
                    public void onFailure(@NonNull final Throwable throwable) {
                        loadingStored = false;
                        render();
                    }
                },
                ContextCompat.getMainExecutor(this));
    }

    @Override
    public void onStop() {
        handler.removeCallbacks(refresh);
        callStageBar.release();
        this.renderedVersion = -1;
        super.onStop();
    }

    private void render() {
        final CallDiagnostics.Snapshot snapshot;
        if (diagnostics != null) {
            final int version = diagnostics.getVersion();
            if (version == renderedVersion) {
                return;
            }
            this.renderedVersion = version;
            snapshot = diagnostics.snapshot();
        } else if (storedSnapshot != null) {
            if (renderedVersion == RENDERED_STORED) {
                return;
            }
            this.renderedVersion = RENDERED_STORED;
            snapshot = storedSnapshot;
        } else {
            this.snapshot = null;
            callStageBar.hide();
            binding.mode.setVisibility(View.GONE);
            binding.suggestionCard.setVisibility(View.GONE);
            binding.sections.removeAllViews();
            binding.copyReport.setEnabled(false);
            if (loadingStored) {
                binding.placeholder.setVisibility(View.GONE);
            } else {
                final var intent = getIntent();
                binding.placeholder.setText(
                        intent != null && intent.hasExtra(EXTRA_WITH)
                                ? R.string.cocosations_call_report_no_calls
                                : R.string.cocosations_call_report_unavailable);
                binding.placeholder.setVisibility(View.VISIBLE);
            }
            return;
        }
        this.snapshot = snapshot;
        binding.placeholder.setVisibility(View.GONE);
        binding.mode.setVisibility(View.VISIBLE);
        callStageBar.render(snapshot);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setSubtitle(snapshot.with());
        }
        final boolean failure = snapshot.stage().isFailure();
        binding.suggestionCard.setVisibility(View.VISIBLE);
        binding.suggestionCard.setCardBackgroundColor(
                MaterialColors.getColor(
                        binding.suggestionCard,
                        failure
                                ? com.google.android.material.R.attr.colorErrorContainer
                                : com.google.android.material.R.attr.colorSurfaceContainerHighest));
        final int onCard =
                MaterialColors.getColor(
                        binding.suggestionCard,
                        failure
                                ? com.google.android.material.R.attr.colorOnErrorContainer
                                : com.google.android.material.R.attr.colorOnSurface);
        binding.suggestionTitle.setTextColor(onCard);
        binding.suggestion.setTextColor(onCard);
        binding.suggestion.setText(CallReportFormat.suggestion(snapshot.suggestion()));
        reportView.render(binding.sections, CallReport.of(this, snapshot).sections(), detailed);
        binding.copyReport.setEnabled(true);
    }

    private String reportText() {
        return snapshot == null ? "" : CallReport.of(this, snapshot).toText();
    }

    private void copyReport(final View view) {
        if (copyTextToClipboard(reportText(), R.string.cocosations_call_diagnostics)
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.cocosations_call_report_copied, Toast.LENGTH_SHORT)
                    .show();
        }
    }

    private void shareText() {
        if (snapshot == null) {
            return;
        }
        final Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.cocosations_call_diagnostics));
        intent.putExtra(Intent.EXTRA_TEXT, reportText());
        startChooser(intent, R.string.cocosations_call_report_share_text);
    }

    private void shareJson() {
        if (snapshot == null) {
            return;
        }
        Futures.addCallback(
                CallReportStore.exportAsync(this, snapshot),
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(final File file) {
                        shareFile(file, "application/json");
                    }

                    @Override
                    public void onFailure(@NonNull final Throwable throwable) {
                        Log.w(Config.LOGTAG, "unable to export call report", throwable);
                        Toast.makeText(
                                        CallDiagnosticsActivity.this,
                                        R.string.cocosations_call_report_export_failed,
                                        Toast.LENGTH_SHORT)
                                .show();
                    }
                },
                ContextCompat.getMainExecutor(this));
    }

    private void shareLog() {
        final Account account = getIntent() == null ? null : extractAccount(getIntent());
        final var snapshot = this.snapshot;
        if (account == null || snapshot == null || snapshot.sessionId() == null) {
            return;
        }
        Futures.addCallback(
                CallReportStore.exportLogAsync(this, account.getUuid(), snapshot.sessionId()),
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(final File file) {
                        shareFile(file, "text/plain");
                    }

                    @Override
                    public void onFailure(@NonNull final Throwable throwable) {
                        Log.w(Config.LOGTAG, "unable to export call log", throwable);
                        Toast.makeText(
                                        CallDiagnosticsActivity.this,
                                        R.string.cocosations_call_report_export_failed,
                                        Toast.LENGTH_SHORT)
                                .show();
                    }
                },
                ContextCompat.getMainExecutor(this));
    }

    private void shareFile(final File file, final String type) {
        final Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(type);
        intent.putExtra(Intent.EXTRA_STREAM, FileBackend.getUriForFile(this, file));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startChooser(intent, R.string.cocosations_call_diagnostics);
    }

    private void startChooser(final Intent intent, @StringRes final int title) {
        try {
            startActivity(Intent.createChooser(intent, getString(title)));
        } catch (final ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_application_found, Toast.LENGTH_SHORT).show();
        }
    }
}
