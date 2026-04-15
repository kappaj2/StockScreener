package za.co.sfh.stocklistener.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.theme.lumo.Lumo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import za.co.sfh.stocklistener.ibkr.IbkrTapeClient;
import za.co.sfh.stocklistener.ibkr.IbkrTickEvent;
import za.co.sfh.stocklistener.ibkr.IbkrTickStore;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Real-time tape reader view — Route: /tape
 *
 * <p>Enter a ticker, press Start to begin streaming live prints from IBKR TWS.
 * The grid updates every 500 ms via Vaadin polling (same pattern as {@link SignalView}).
 * Press Cancel to pause the display without losing the buffered data.
 */
@Route("tape")
@RequiredArgsConstructor
public class TapeView extends VerticalLayout {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final IbkrTapeClient tapeClient;
    private final IbkrTickStore  tickStore;

    // ── UI components ─────────────────────────────────────────────────────────

    private final TextField symbolField = new TextField();
    private final Button    startBtn    = new Button("Start");
    private final Button    cancelBtn   = new Button("Cancel");
    private final Span      statusLabel = new Span("Stopped");
    private final Span      lastPrice   = new Span("—");
    private final Span      tickCount   = new Span("0 ticks");
    private final Grid<IbkrTickEvent> grid = new Grid<>(IbkrTickEvent.class, false);

    // ── State ─────────────────────────────────────────────────────────────────

    private Registration pollRegistration;
    private String       watchingSymbol = null;

    // ── Init ──────────────────────────────────────────────────────────────────

    @PostConstruct
    private void init() {
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        getStyle()
                .set("background", "#0d1117")
                .set("min-height", "100vh");

        add(buildHeader(), buildControls(), buildStatsBar(), buildGrid());
        setFlexGrow(1, buildGridWrapper());
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

        Span icon = new Span("📜");
        icon.getStyle().set("font-size", "1.4rem");

        H1 title = new H1("Tape Reader");
        title.getStyle()
                .set("margin", "0")
                .set("font-size", "1.2rem")
                .set("font-weight", "600")
                .set("color", "#e6edf3")
                .set("letter-spacing", "0.5px");

        Button back = new Button("← Signals");
        back.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        back.getStyle().set("margin-left", "auto").set("color", "#8b949e");
        back.addClickListener(e -> getUI().ifPresent(ui -> ui.navigate("")));

        header.add(icon, title, back);
        return header;
    }

    // ── Controls row ──────────────────────────────────────────────────────────

    private HorizontalLayout buildControls() {
        HorizontalLayout row = new HorizontalLayout();
        row.setWidthFull();
        row.setAlignItems(Alignment.BASELINE);
        row.getStyle()
                .set("padding", "16px 24px 8px 24px")
                .set("background", "#0d1117");

        symbolField.setPlaceholder("e.g. AAPL");
        symbolField.setLabel("Ticker Symbol");
        symbolField.setWidth("160px");
        symbolField.getStyle()
                .set("--vaadin-input-field-background", "#161b22")
                .set("color", "#e6edf3");
        symbolField.addValueChangeListener(e ->
                symbolField.setValue(e.getValue().toUpperCase()));

        startBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SUCCESS);
        startBtn.addClickShortcut(Key.ENTER);
        startBtn.addClickListener(e -> onStart());

        cancelBtn.addThemeVariants(ButtonVariant.LUMO_ERROR);
        cancelBtn.setEnabled(false);
        cancelBtn.addClickListener(e -> onCancel());

        row.add(symbolField, startBtn, cancelBtn);
        return row;
    }

    // ── Stats bar ─────────────────────────────────────────────────────────────

    private HorizontalLayout buildStatsBar() {
        HorizontalLayout stats = new HorizontalLayout();
        stats.setWidthFull();
        stats.setAlignItems(Alignment.CENTER);
        stats.setSpacing(true);
        stats.getStyle()
                .set("padding", "6px 24px 12px 24px")
                .set("background", "#0d1117")
                .set("border-bottom", "1px solid #21262d");

        styleStatLabel(statusLabel, "#8b949e");
        styleStatLabel(lastPrice,   "#3fb950");
        styleStatLabel(tickCount,   "#8b949e");

        Span sep1 = separator();
        Span sep2 = separator();

        stats.add(statusLabel, sep1, lastPrice, sep2, tickCount);
        return stats;
    }

    private void styleStatLabel(Span span, String color) {
        span.getStyle()
                .set("color", color)
                .set("font-size", "0.82rem")
                .set("font-weight", "500")
                .set("font-family", "monospace");
    }

    private Span separator() {
        Span s = new Span("|");
        s.getStyle().set("color", "#21262d").set("margin", "0 4px");
        return s;
    }

    // ── Grid ──────────────────────────────────────────────────────────────────

    private Div buildGridWrapper() {
        Div wrapper = new Div(buildGrid());
        wrapper.setSizeFull();
        wrapper.getStyle().set("padding", "0 24px 16px 24px").set("flex", "1");
        return wrapper;
    }

    private Grid<IbkrTickEvent> buildGrid() {
        grid.addThemeVariants(GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_ROW_STRIPES);
        grid.setSizeFull();
        grid.getStyle().set("background", "#0d1117");

        grid.addColumn(tick -> TIME_FMT.format(Instant.ofEpochSecond(tick.timestamp())))
                .setHeader("Time")
                .setWidth("90px")
                .setFlexGrow(0);

        grid.addComponentColumn(tick -> {
                    Span cell = new Span(String.format("%.4f", tick.price()));
                    cell.getStyle()
                            .set("color", "#3fb950")
                            .set("font-weight", "600")
                            .set("font-family", "monospace");
                    return cell;
                })
                .setHeader("Price")
                .setWidth("110px")
                .setFlexGrow(0);

        grid.addColumn(tick -> String.format("%.0f", tick.size()))
                .setHeader("Size")
                .setWidth("90px")
                .setFlexGrow(0);

        grid.addColumn(IbkrTickEvent::exchange)
                .setHeader("Exchange")
                .setWidth("110px")
                .setFlexGrow(0);

        grid.addComponentColumn(tick -> {
                    String cond = tick.conditions();
                    Span cell = new Span(cond == null || cond.isBlank() ? "regular" : cond);
                    cell.getStyle()
                            .set("color", cond == null || cond.isBlank() ? "#484f58" : "#e6edf3")
                            .set("font-size", "0.8rem");
                    return cell;
                })
                .setHeader("Conditions")
                .setFlexGrow(1);

        return grid;
    }

    // ── Button handlers ───────────────────────────────────────────────────────

    private void onStart() {
        String sym = symbolField.getValue().trim().toUpperCase();
        if (sym.isBlank()) {
            notify("Enter a ticker symbol first", NotificationVariant.LUMO_WARNING);
            return;
        }
        if (!tapeClient.isConnected()) {
            notify("Not connected to IBKR TWS — check ibkr.enabled in application.yaml",
                    NotificationVariant.LUMO_ERROR);
            return;
        }

        watchingSymbol = sym;
        tapeClient.subscribe(sym);

        startBtn.setEnabled(false);
        cancelBtn.setEnabled(true);
        symbolField.setReadOnly(true);

        statusLabel.setText("Watching: " + sym);
        statusLabel.getStyle().set("color", "#3fb950");

        // Enable polling — fires refreshGrid() every 500 ms
        getUI().ifPresent(ui -> {
            ui.setPollInterval(500);
            pollRegistration = ui.addPollListener(event -> refreshGrid());
        });

        refreshGrid();
    }

    private void onCancel() {
        stopWatching();
        notify("Stopped watching " + (watchingSymbol != null ? watchingSymbol : ""), null);
        watchingSymbol = null;
    }

    private void stopWatching() {
        if (pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
        }
        getUI().ifPresent(ui -> ui.setPollInterval(-1));

        startBtn.setEnabled(true);
        cancelBtn.setEnabled(false);
        symbolField.setReadOnly(false);

        statusLabel.setText("Stopped");
        statusLabel.getStyle().set("color", "#8b949e");
    }

    // ── Data refresh ──────────────────────────────────────────────────────────

    private void refreshGrid() {
        if (watchingSymbol == null) return;

        List<IbkrTickEvent> ticks = tickStore.getTicks(watchingSymbol);
        if (ticks.isEmpty()) {
            tickCount.setText("0 ticks");
            return;
        }

        // Reverse so the newest print is at the top of the grid
        List<IbkrTickEvent> reversed = new ArrayList<>(ticks);
        java.util.Collections.reverse(reversed);

        grid.setItems(reversed);

        IbkrTickEvent latest = reversed.get(0);
        lastPrice.setText("Last: $" + String.format("%.4f", latest.price()));
        tickCount.setText(ticks.size() + " ticks buffered");
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onAttach(AttachEvent event) {
        super.onAttach(event);
        event.getUI().getElement().setAttribute("theme", Lumo.DARK);
    }

    @Override
    protected void onDetach(DetachEvent event) {
        super.onDetach(event);
        stopWatching();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void notify(String message, NotificationVariant variant) {
        Notification n = Notification.show(message, 3000, Notification.Position.BOTTOM_END);
        if (variant != null) n.addThemeVariants(variant);
    }
}
