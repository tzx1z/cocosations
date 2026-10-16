package eu.siacs.conversations.ui.util;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.widget.QualityChartView;
import eu.siacs.conversations.xmpp.jingle.CallQuality;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Разделы отчета карточками; помнит свернутые разделы и выбранный показатель графика. */
public class CallReportView {

    private static final String STATE_COLLAPSED = "cocosations_report_collapsed";
    private static final String STATE_METRIC = "cocosations_report_metric";

    private final Activity activity;
    private CallQualityMetric metric = CallQualityMetric.MOS;
    // свернутость задает пользователь; отчет перерисовывается каждую секунду и не должен ее
    // сбрасывать
    private final HashMap<String, Boolean> collapsed = new HashMap<>();

    public CallReportView(final Activity activity) {
        this.activity = activity;
    }

    @SuppressWarnings("unchecked")
    public void restore(final Bundle state) {
        if (state.getSerializable(STATE_COLLAPSED) instanceof HashMap<?, ?> saved) {
            collapsed.putAll((Map<String, Boolean>) saved);
        }
        final String saved = state.getString(STATE_METRIC);
        if (saved != null) {
            this.metric = CallQualityMetric.valueOf(saved);
        }
    }

    public void save(final Bundle state) {
        state.putSerializable(STATE_COLLAPSED, collapsed);
        state.putString(STATE_METRIC, metric.name());
    }

    /** detailed false - только разделы краткого режима. */
    public void render(
            final ViewGroup container,
            final List<CallReport.Section> sections,
            final boolean detailed) {
        container.removeAllViews();
        final LayoutInflater inflater = activity.getLayoutInflater();
        for (final var section : sections) {
            if (!detailed && !section.brief()) {
                continue;
            }
            final View card =
                    inflater.inflate(R.layout.cocosations_report_section, container, false);
            final View header = card.findViewById(R.id.header);
            final LinearLayout body = card.findViewById(R.id.body);
            final ImageView chevron = card.findViewById(R.id.chevron);
            ((TextView) card.findViewById(R.id.title)).setText(section.title());
            card.findViewById(R.id.status)
                    .setBackgroundTintList(
                            ColorStateList.valueOf(statusColor(card, section.status(), true)));
            for (final var row : section.rows()) {
                body.addView(rowView(inflater, body, row));
            }
            applyCollapsed(body, chevron, isCollapsed(section));
            header.setOnClickListener(
                    v -> {
                        final boolean collapse = body.getVisibility() == View.VISIBLE;
                        collapsed.put(section.key(), collapse);
                        applyCollapsed(body, chevron, collapse);
                    });
            container.addView(card);
        }
    }

    public static int statusColor(
            final View view, final CallReport.Status status, final boolean indicator) {
        return switch (status) {
            case OK -> ContextCompat.getColor(view.getContext(), R.color.cocosations_status_ok);
            case WARNING ->
                    ContextCompat.getColor(view.getContext(), R.color.cocosations_status_warning);
            case ERROR -> MaterialColors.getColor(view, androidx.appcompat.R.attr.colorError);
            case NEUTRAL ->
                    MaterialColors.getColor(
                            view,
                            indicator
                                    ? com.google.android.material.R.attr.colorOutline
                                    : com.google.android.material.R.attr.colorOnSurface);
        };
    }

    private boolean isCollapsed(final CallReport.Section section) {
        final Boolean state = collapsed.get(section.key());
        return state == null ? section.collapsed() : state;
    }

    private static void applyCollapsed(
            final View body, final ImageView chevron, final boolean collapsed) {
        body.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        chevron.setRotation(collapsed ? 0 : 180);
    }

    private View rowView(
            final LayoutInflater inflater, final ViewGroup parent, final CallReport.Row row) {
        return switch (row) {
            case CallReport.Field field -> {
                final View view =
                        inflater.inflate(R.layout.cocosations_report_field, parent, false);
                ((TextView) view.findViewById(R.id.label)).setText(field.label());
                final TextView value = view.findViewById(R.id.value);
                value.setText(field.value());
                if (field.status() != CallReport.Status.NEUTRAL) {
                    value.setTextColor(statusColor(parent, field.status(), false));
                }
                yield view;
            }
            case CallReport.Text text -> {
                final TextView view = monospace(text.text());
                view.setTextIsSelectable(true);
                view.setPadding(0, dp(2), 0, dp(2));
                yield view;
            }
            case CallReport.Table table -> table(table);
            case CallReport.Chart chart -> chart(inflater, parent, chart.samples());
        };
    }

    private View table(final CallReport.Table table) {
        final TableLayout layout = new TableLayout(activity);
        layout.setShrinkAllColumns(false);
        layout.setColumnShrinkable(0, true);
        layout.setPadding(0, dp(8), 0, 0);
        layout.addView(tableRow(table.header(), true));
        for (final var cells : table.rows()) {
            layout.addView(tableRow(cells, false));
        }
        return layout;
    }

    private TableRow tableRow(final List<String> cells, final boolean header) {
        final TableRow row = new TableRow(activity);
        for (int i = 0; i < cells.size(); i++) {
            final TextView cell = monospace(cells.get(i));
            cell.setPadding(i == 0 ? 0 : dp(10), dp(2), 0, dp(2));
            cell.setGravity(i == 0 ? Gravity.START : Gravity.END);
            if (header) {
                cell.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            }
            row.addView(cell);
        }
        return row;
    }

    private View chart(
            final LayoutInflater inflater,
            final ViewGroup parent,
            final List<CallQuality.Sample> samples) {
        final View view = inflater.inflate(R.layout.cocosations_report_chart, parent, false);
        final ChipGroup metrics = view.findViewById(R.id.metrics);
        final QualityChartView chart = view.findViewById(R.id.chart);
        for (final var candidate : CallQualityMetric.values()) {
            if (samples.stream().noneMatch(s -> candidate.value.applyAsDouble(s) >= 0)) {
                continue;
            }
            final Chip chip = new Chip(activity);
            chip.setText(candidate.label);
            chip.setCheckable(true);
            chip.setId(View.generateViewId());
            chip.setTag(candidate);
            metrics.addView(chip);
            if (candidate == metric) {
                chip.setChecked(true);
            }
        }
        if (metrics.getCheckedChipId() == View.NO_ID && metrics.getChildCount() > 0) {
            final var first = (Chip) metrics.getChildAt(0);
            first.setChecked(true);
            this.metric = (CallQualityMetric) first.getTag();
        }
        chart.setData(samples, metric);
        metrics.setOnCheckedStateChangeListener(
                (group, checkedIds) -> {
                    if (checkedIds.isEmpty()) {
                        return;
                    }
                    final Chip chip = group.findViewById(checkedIds.get(0));
                    this.metric = (CallQualityMetric) chip.getTag();
                    chart.setData(samples, metric);
                });
        return view;
    }

    private TextView monospace(final String text) {
        final TextView view = new TextView(activity);
        TextViewCompat.setTextAppearance(
                view, resolveStyle(com.google.android.material.R.attr.textAppearanceBodySmall));
        view.setTypeface(Typeface.MONOSPACE);
        view.setText(text);
        return view;
    }

    private int resolveStyle(final int attribute) {
        final TypedValue value = new TypedValue();
        activity.getTheme().resolveAttribute(attribute, value, true);
        return value.resourceId;
    }

    private int dp(final int value) {
        return Math.round(
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        value,
                        activity.getResources().getDisplayMetrics()));
    }
}
