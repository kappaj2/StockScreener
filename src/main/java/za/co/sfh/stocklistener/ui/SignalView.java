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
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.theme.lumo.Lumo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import za.co.sfh.stocklistener.signals.BreakoutSignal;
import za.co.sfh.stocklistener.signals.SignalStore;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Route("") // This will be the home page
@RequiredArgsConstructor
public class SignalView extends VerticalLayout {

    private final SignalStore signalStore;
    private final Grid<BreakoutSignal> grid = new Grid<>(BreakoutSignal.class);
    private Registration pollRegistration;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

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

        // Configure the Grid columns
        grid.setColumns("symbol", "pattern", "entry", "stop", "target", "confidence", "risk", "notes");

        // Make all auto-generated columns resizable and constrain the wide pattern column
        grid.getColumns().forEach(col -> col.setResizable(true));
        grid.getColumnByKey("pattern").setWidth("150px").setFlexGrow(0);

        grid.addColumn(signal -> FORMATTER.format(Instant.ofEpochMilli(signal.timestamp())))
                .setHeader("Generated At")
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

        // Dark theme variants
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

        // Initial load
        refreshGrid();

        // Enable polling every 1 second
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
        // Optionally disable polling if no other component needs it, 
        // but often it's safer to just leave it if there are multiple views
        detachEvent.getUI().setPollInterval(-1);
    }

    private void refreshGrid() {
        List<BreakoutSignal> signals = signalStore.peekAll().reversed();
        grid.setItems(signals);
    }
}
