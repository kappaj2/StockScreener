package za.co.sfh.stocklistener.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.shared.Tooltip;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import za.co.sfh.stocklistener.signals.BreakoutSignal;
import za.co.sfh.stocklistener.signals.SignalStore;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Route("")
@RequiredArgsConstructor
public class SignalView extends VerticalLayout {

    private final SignalStore signalStore;
    private final Grid<BreakoutSignal> grid = new Grid<>(BreakoutSignal.class, false);
    private final Set<String> knownHighWatchIds = new HashSet<>();
    private Registration pollRegistration;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("America/New_York"));

    @Value("${paper-trading.account-size}")
    private double accountSize;

    @Value("${paper-trading.max-trade-percentage}")
    private double maxTradePercentage;

    @PostConstruct
    private void init() {
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        getStyle()
                .set("background", "#0d1117")
                .set("min-height", "100vh");

        // Header bar
        HorizontalLayout header = new HorizontalLayout();
        header.setWidthFull();
        header.setPadding(true);
        header.setAlignItems(Alignment.CENTER);
        header.getStyle()
                .set("background", "#161b22")
                .set("border-bottom", "1px solid #21262d")
                .set("padding", "12px 24px");

        Span rocket = new Span("🚀");
        rocket.getStyle().set("font-size", "1.4rem");

        H1 title = new H1("Breakout Signals");
        title.getStyle()
                .set("margin", "0")
                .set("font-size", "1.2rem")
                .set("font-weight", "600")
                .set("color", "#e6edf3")
                .set("letter-spacing", "0.5px");

        Span liveIndicator = new Span("● LIVE");
        liveIndicator.getStyle()
                .set("color", "#3fb950")
                .set("font-size", "0.75rem")
                .set("font-weight", "600")
                .set("margin-left", "auto")
                .set("letter-spacing", "1px");

        header.add(rocket, title, liveIndicator);

        // Grid container
        Div gridWrapper = new Div();
        gridWrapper.setSizeFull();
        gridWrapper.getStyle()
                .set("padding", "16px 24px")
                .set("flex", "1");

        // Columns — pattern uses the short code so long enum names don't overflow
        grid.addColumn(BreakoutSignal::symbol).setHeader("Symbol").setResizable(true);
        grid.addColumn(BreakoutSignal::displayCode).setHeader("Pattern").setWidth("80px").setFlexGrow(0).setResizable(true);
        grid.addColumn(BreakoutSignal::entry).setHeader("Entry").setResizable(true);
        grid.addColumn(BreakoutSignal::stop).setHeader("Stop").setResizable(true);
        grid.addColumn(BreakoutSignal::target).setHeader("Target").setResizable(true);
        grid.addColumn(BreakoutSignal::confidence).setHeader("Conf").setResizable(true);
        grid.addComponentColumn(signal -> {
            double budget = accountSize * maxTradePercentage;
            int shares = (int)(budget / signal.entry());
            double tradeValue = shares * signal.entry();
            Span cell = new Span(shares + " sh / $" + String.format("%,.2f", tradeValue));
            cell.getStyle().set("cursor", "help").set("white-space", "nowrap");
            Tooltip.forComponent(cell).withText(String.format(
                    "Shares: %d  |  Buy @ $%.2f  |  Total: $%,.2f  |  Budget: $%,.0f (%.0f%% of $%,.0f)",
                    shares, signal.entry(), tradeValue,
                    budget, maxTradePercentage * 100, accountSize));
            return cell;
        }).setHeader("Trade").setAutoWidth(true).setFlexGrow(0).setResizable(true);
        grid.addColumn(BreakoutSignal::notes).setHeader("Notes").setResizable(true);

        grid.getColumns().forEach(col -> col.setResizable(true));

        grid.addColumn(signal -> String.format("%.1f%%", (signal.target() - signal.entry()) / signal.entry() * 100))
                .setHeader("% Gain")
                .setSortable(true)
                .setResizable(true);

        grid.addColumn(signal -> FORMATTER.format(Instant.ofEpochMilli(signal.timestamp())))
                .setHeader("Bar Time (ET)")
                .setSortable(true)
                .setResizable(true);
        grid.addColumn(signal -> signal.preMarketHigh() == Double.MIN_VALUE ? "-" : String.format("%.2f", signal.preMarketHigh()))
                .setHeader("PM High")
                .setSortable(true)
                .setResizable(true);
        grid.addColumn(signal -> signal.preMarketLow() == Double.MAX_VALUE ? "-" : String.format("%.2f", signal.preMarketLow()))
                .setHeader("PM Low")
                .setSortable(true)
                .setResizable(true);
        grid.addComponentColumn(signal -> {
            Span newsSpan = new Span(signal.news() != null ? signal.news() : "");
            newsSpan.getStyle().set("white-space", "normal").set("word-break", "break-word");
            return newsSpan;
        }).setHeader("News").setAutoWidth(true).setFlexGrow(2).setResizable(true);

        grid.addComponentColumn(signal -> {
            Button chart = new Button("Chart");
            chart.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            chart.addClickListener(e -> getUI().ifPresent(ui -> ui.navigate("chart/" + signal.symbol())));
            return chart;
        }).setHeader("").setAutoWidth(true).setFlexGrow(0);

        grid.addComponentColumn(signal -> {
            Button remove = new Button("Remove");
            remove.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
            remove.addClickListener(e -> {
                signalStore.remove(signal.id());
                refreshGrid();
            });
            return remove;
        }).setHeader("").setAutoWidth(true).setFlexGrow(0);

        grid.addThemeVariants(GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_NO_BORDER);
        grid.getElement().setAttribute("theme", "dark");

        grid.setSizeFull();
        gridWrapper.add(grid);

        add(header, gridWrapper);
        setFlexGrow(1, gridWrapper);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        refreshGrid();
        attachEvent.getUI().setPollInterval(1000);
        pollRegistration = attachEvent.getUI().addPollListener(event -> refreshGrid());
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        if (pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
        }
        detachEvent.getUI().setPollInterval(-1);
    }

    private void refreshGrid() {
        List<BreakoutSignal> signals = signalStore.peekAll().reversed();
        grid.setItems(signals);
        checkForNewHighWatchSignals(signals);
    }

    private void checkForNewHighWatchSignals(List<BreakoutSignal> signals) {
        boolean hasNew = false;
        for (BreakoutSignal s : signals) {
            if (s.highWatch() && knownHighWatchIds.add(s.id())) {
                hasNew = true;
            }
        }
        if (hasNew) {
            playHighWatchAlert();
        }
    }

    private void playHighWatchAlert() {
        getUI().ifPresent(ui -> ui.getPage().executeJs(
                "try {" +
                "  var ctx = new (window.AudioContext || window.webkitAudioContext)();" +
                "  function beep(freq, start, dur) {" +
                "    var o = ctx.createOscillator(), g = ctx.createGain();" +
                "    o.connect(g); g.connect(ctx.destination);" +
                "    o.frequency.value = freq; o.type = 'sine';" +
                "    g.gain.setValueAtTime(0.4, ctx.currentTime + start);" +
                "    g.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + start + dur);" +
                "    o.start(ctx.currentTime + start); o.stop(ctx.currentTime + start + dur);" +
                "  }" +
                "  beep(880, 0.0, 0.18);" +
                "  beep(1100, 0.22, 0.18);" +
                "  beep(880, 0.44, 0.28);" +
                "} catch(e) { console.error('HW audio alert failed:', e); }"
        ));
    }
}