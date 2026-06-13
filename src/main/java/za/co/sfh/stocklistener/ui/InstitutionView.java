package za.co.sfh.stocklistener.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import za.co.sfh.stocklistener.persistence.entities.DailyBarScoreEntity;
import za.co.sfh.stocklistener.scoring.InstitutionalScoringService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Route("institutions")
@PageTitle("Institutional Signals")
@RequiredArgsConstructor
public class InstitutionView extends VerticalLayout {

    private static final int    POLL_INTERVAL_MS = 30 * 60 * 1_000;
    private static final int    TOP_LIMIT        = 100;
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("America/New_York"));

    private final InstitutionalScoringService scoringService;

    private final Grid<DailyBarScoreEntity> grid         = new Grid<>(DailyBarScoreEntity.class, false);
    private final Set<String>               knownSymbols = new HashSet<>();
    private final Span                      countLabel   = new Span("0 signals");
    private final Span                      refreshLabel = new Span("—");

    private LocalDate selectedDate = LocalDate.now();
    private int       minScore     = 3;
    private boolean   initialLoad  = true;
    private Registration pollRegistration;

    // ── Init ──────────────────────────────────────────────────────────────────

    @PostConstruct
    private void init() {
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        getStyle().set("background", "#0d1117").set("min-height", "100vh");

        configureGrid();

        Div gridWrapper = new Div(grid);
        gridWrapper.setSizeFull();
        gridWrapper.getStyle().set("padding", "0 24px 16px 24px").set("flex", "1");

        add(buildHeader(), buildFilterBar(), buildStatsBar(), gridWrapper);
        setFlexGrow(1, gridWrapper);
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private HorizontalLayout buildHeader() {
        HorizontalLayout header = new HorizontalLayout();
        header.setWidthFull();
        header.setAlignItems(Alignment.CENTER);
        header.getStyle()
                .set("background", "#161b22")
                .set("border-bottom", "1px solid #21262d")
                .set("padding", "12px 24px");

        Span icon = new Span("🏦");
        icon.getStyle().set("font-size", "1.4rem");

        H1 title = new H1("Institutional Signals");
        title.getStyle()
                .set("margin", "0")
                .set("font-size", "1.2rem")
                .set("font-weight", "600")
                .set("color", "#e6edf3")
                .set("letter-spacing", "0.5px");

        Span autoBadge = new Span("↻ 30 min");
        autoBadge.getStyle()
                .set("color", "#8b949e")
                .set("font-size", "0.72rem")
                .set("margin-left", "12px")
                .set("letter-spacing", "0.5px");

        Button refreshBtn = new Button("Refresh Now");
        refreshBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY);
        refreshBtn.getStyle().set("margin-left", "auto");
        refreshBtn.addClickListener(e -> refreshGrid(true));

        Button back = new Button("← Signals");
        back.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        back.getStyle().set("color", "#8b949e");
        back.addClickListener(e -> getUI().ifPresent(ui -> ui.navigate("")));

        header.add(icon, title, autoBadge, refreshBtn, back);
        return header;
    }

    // ── Filter bar ────────────────────────────────────────────────────────────

    private HorizontalLayout buildFilterBar() {
        HorizontalLayout bar = new HorizontalLayout();
        bar.setWidthFull();
        bar.setAlignItems(Alignment.BASELINE);
        bar.getStyle()
                .set("padding", "12px 24px 8px 24px")
                .set("background", "#0d1117")
                .set("border-bottom", "1px solid #21262d");

        DatePicker datePicker = new DatePicker("Date");
        datePicker.setValue(selectedDate);
        datePicker.setWidth("180px");
        datePicker.getStyle().set("--vaadin-input-field-background", "#161b22");
        datePicker.addValueChangeListener(e -> {
            if (e.getValue() != null) {
                selectedDate = e.getValue();
                knownSymbols.clear();
                initialLoad = true;
                refreshGrid(false);
            }
        });

        IntegerField minScoreField = new IntegerField("Min 10d Score");
        minScoreField.setValue(minScore);
        minScoreField.setMin(1);
        minScoreField.setMax(30);
        minScoreField.setStepButtonsVisible(true);
        minScoreField.setWidth("150px");
        minScoreField.getStyle().set("--vaadin-input-field-background", "#161b22");
        minScoreField.addValueChangeListener(e -> {
            if (e.getValue() != null) {
                minScore = e.getValue();
                refreshGrid(false);
            }
        });

        bar.add(datePicker, minScoreField);
        return bar;
    }

    // ── Stats bar ─────────────────────────────────────────────────────────────

    private HorizontalLayout buildStatsBar() {
        HorizontalLayout stats = new HorizontalLayout();
        stats.setWidthFull();
        stats.setAlignItems(Alignment.CENTER);
        stats.getStyle()
                .set("padding", "6px 24px")
                .set("background", "#0d1117")
                .set("border-bottom", "1px solid #21262d");

        styleStatLabel(countLabel, "#8b949e");
        styleStatLabel(refreshLabel, "#8b949e");

        Span sep = new Span("|");
        sep.getStyle().set("color", "#21262d").set("margin", "0 8px");

        stats.add(countLabel, sep, refreshLabel);
        return stats;
    }

    private void styleStatLabel(Span span, String color) {
        span.getStyle()
                .set("color", color)
                .set("font-size", "0.82rem")
                .set("font-weight", "500")
                .set("font-family", "monospace");
    }

    // ── Grid ──────────────────────────────────────────────────────────────────

    private void configureGrid() {
        grid.addThemeVariants(GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_ROW_STRIPES);
        grid.setSizeFull();
        grid.getElement().setAttribute("theme", "dark");

        grid.addComponentColumn(s -> {
            Span sym = new Span(s.getSymbol());
            sym.getStyle()
                    .set("font-weight", "700")
                    .set("font-family", "monospace")
                    .set("color", "#e6edf3")
                    .set("letter-spacing", "0.5px");
            return sym;
        }).setHeader("Symbol").setWidth("100px").setFlexGrow(0).setSortable(true)
                .setComparator(DailyBarScoreEntity::getSymbol);

        grid.addComponentColumn(s -> rollingScoreBadge(s.getRolling10dScore()))
                .setHeader("10d Score").setWidth("100px").setFlexGrow(0).setSortable(true)
                .setComparator(Comparator.comparingInt(DailyBarScoreEntity::getRolling10dScore));

        grid.addComponentColumn(s -> {
            Span ds = new Span(String.valueOf(s.getDayScore()));
            ds.getStyle()
                    .set("font-family", "monospace")
                    .set("font-weight", "600")
                    .set("color", s.getDayScore() >= 4 ? "#3fb950" : "#8b949e");
            return ds;
        }).setHeader("Day").setWidth("70px").setFlexGrow(0).setSortable(true)
                .setComparator(Comparator.comparingInt(DailyBarScoreEntity::getDayScore));

        grid.addColumn(s -> s.getClosePrice() != null
                ? "$" + String.format("%.2f", s.getClosePrice())
                : "—")
                .setHeader("Close").setWidth("90px").setFlexGrow(0).setSortable(true)
                .setComparator(Comparator.comparing(DailyBarScoreEntity::getClosePrice,
                        Comparator.nullsLast(Comparator.naturalOrder())));

        grid.addColumn(s -> s.getVolRatio() != null
                ? String.format("%.2fx", s.getVolRatio())
                : "—")
                .setHeader("Vol/Avg").setWidth("90px").setFlexGrow(0).setSortable(true)
                .setComparator(Comparator.comparing(DailyBarScoreEntity::getVolRatio,
                        Comparator.nullsLast(Comparator.naturalOrder())));

        grid.addComponentColumn(this::buildFlagChips)
                .setHeader("Signals").setFlexGrow(1).setAutoWidth(true);

        grid.addColumn(s -> s.getComputedAt() != null
                ? TIME_FMT.format(s.getComputedAt()) + " ET"
                : "—")
                .setHeader("Computed").setWidth("130px").setFlexGrow(0);
    }

    private Span rollingScoreBadge(int score) {
        String bg, fg;
        if (score >= 10) {
            bg = "#3fb950"; fg = "#0d1117";
        } else if (score >= 7) {
            bg = "#56d364"; fg = "#0d1117";
        } else if (score >= 5) {
            bg = "#e3b341"; fg = "#0d1117";
        } else if (score >= 3) {
            bg = "#d29922"; fg = "#e6edf3";
        } else {
            bg = "#30363d"; fg = "#e6edf3";
        }
        Span badge = new Span(String.valueOf(score));
        badge.getStyle()
                .set("background", bg)
                .set("color", fg)
                .set("padding", "2px 8px")
                .set("border-radius", "12px")
                .set("font-weight", "700")
                .set("font-family", "monospace")
                .set("font-size", "0.85rem");
        return badge;
    }

    private HorizontalLayout buildFlagChips(DailyBarScoreEntity s) {
        HorizontalLayout row = new HorizontalLayout();
        row.setSpacing(false);
        row.getStyle().set("gap", "4px").set("flex-wrap", "wrap");

        int vs = s.getVolSpike()          != null ? s.getVolSpike()          : 0;
        int ud = s.getUpDay()             != null ? s.getUpDay()             : 0;
        int sc = s.getStrongClose()       != null ? s.getStrongClose()       : 0;
        int tr = s.getTightRange()        != null ? s.getTightRange()        : 0;
        int qa = s.getQuietAccumulation() != null ? s.getQuietAccumulation() : 0;
        int vc = s.getVolConcentration()  != null ? s.getVolConcentration()  : 0;

        row.add(flagChip("VS",          vs > 0, "#388bfd"));
        row.add(flagChip("↑D",          ud > 0, "#3fb950"));
        row.add(flagChip("SC",          sc > 0, "#56d364"));
        row.add(flagChip("TR",          tr > 0, "#e3b341"));
        row.add(flagChip(qa > 1 ? "QA²" : "QA", qa > 0, "#d29922"));
        row.add(flagChip("VC",          vc > 0, "#bc8cff"));
        return row;
    }

    private Span flagChip(String label, boolean active, String activeColor) {
        Span s = new Span(label);
        s.getStyle()
                .set("font-size", "0.7rem")
                .set("font-weight", "600")
                .set("font-family", "monospace")
                .set("padding", "1px 5px")
                .set("border-radius", "4px")
                .set("white-space", "nowrap");
        if (active) {
            s.getStyle().set("background", activeColor).set("color", "#0d1117");
        } else {
            s.getStyle().set("background", "#21262d").set("color", "#484f58");
        }
        return s;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onAttach(AttachEvent event) {
        super.onAttach(event);
        refreshGrid(false);
        event.getUI().setPollInterval(POLL_INTERVAL_MS);
        pollRegistration = event.getUI().addPollListener(e -> refreshGrid(false));
    }

    @Override
    protected void onDetach(DetachEvent event) {
        super.onDetach(event);
        if (pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
        }
        event.getUI().setPollInterval(-1);
    }

    // ── Data refresh ──────────────────────────────────────────────────────────

    private void refreshGrid(boolean isManual) {
        List<DailyBarScoreEntity> scores = scoringService.getTopSignals(selectedDate, minScore, TOP_LIMIT);
        grid.setItems(scores);

        countLabel.setText(scores.size() + " signals");
        refreshLabel.setText("Refreshed: " + TIME_FMT.format(Instant.now()) + " ET");

        if (initialLoad) {
            scores.forEach(s -> knownSymbols.add(s.getSymbol()));
            initialLoad = false;
            if (!scores.isEmpty()) {
                announceSymbols("Institutional signals", topSymbols(scores, 3));
            }
        } else {
            List<String> newSymbols = scores.stream()
                    .map(DailyBarScoreEntity::getSymbol)
                    .filter(sym -> !knownSymbols.contains(sym))
                    .toList();
            scores.forEach(s -> knownSymbols.add(s.getSymbol()));

            if (!newSymbols.isEmpty()) {
                announceSymbols("New signal", newSymbols);
            } else if (isManual && !scores.isEmpty()) {
                announceSymbols("Top signals", topSymbols(scores, 3));
            }
        }
    }

    // ── Speech synthesis ──────────────────────────────────────────────────────

    private List<String> topSymbols(List<DailyBarScoreEntity> scores, int n) {
        return scores.stream().limit(n).map(DailyBarScoreEntity::getSymbol).toList();
    }

    private void announceSymbols(String prefix, List<String> symbols) {
        if (symbols.isEmpty()) return;

        StringBuilder js = new StringBuilder(
                "if ('speechSynthesis' in window) { var sp = window.speechSynthesis; sp.cancel();");

        appendUtterance(js, prefix);
        symbols.forEach(sym -> {
            String spaced = sym.chars()
                    .mapToObj(c -> String.valueOf((char) c))
                    .collect(Collectors.joining(" "));
            appendUtterance(js, spaced);
        });
        js.append("}");

        getUI().ifPresent(ui -> ui.getPage().executeJs(js.toString()));
    }

    private void appendUtterance(StringBuilder js, String text) {
        js.append("{ var u = new SpeechSynthesisUtterance('")
          .append(text.replace("'", "\\'"))
          .append("'); u.rate = 0.85; u.pitch = 1.1; sp.speak(u); }");
    }
}