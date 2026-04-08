package za.co.sfh.stocklistener.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
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
        add(new H1("Pending Breakout Signals"));

        // Configure the Grid columns
        grid.setColumns("symbol", "pattern", "entry", "stop", "target", "confidence", "risk", "notes");
        grid.addColumn(signal -> FORMATTER.format(Instant.ofEpochMilli(signal.timestamp())))
                .setHeader("Generated At")
                .setSortable(true);
        grid.addColumn(signal -> signal.preMarketHigh() == Double.MIN_VALUE ? "-" : String.format("%.2f", signal.preMarketHigh()))
                .setHeader("PM High")
                .setSortable(true);
        grid.addColumn(signal -> signal.preMarketLow() == Double.MAX_VALUE ? "-" : String.format("%.2f", signal.preMarketLow()))
                .setHeader("PM Low")
                .setSortable(true);
        grid.addComponentColumn(signal -> {
            Span newsSpan = new Span(signal.news() != null ? signal.news() : "");
            newsSpan.getStyle().set("white-space", "normal").set("word-break", "break-word");
            return newsSpan;
        }).setHeader("News").setAutoWidth(true).setFlexGrow(2);

        grid.addComponentColumn(signal -> {
            Button remove = new Button("Remove");
            remove.addClickListener(e -> {
                signalStore.remove(signal.id());
                refreshGrid();
            });
            return remove;
        }).setHeader("").setAutoWidth(true).setFlexGrow(0);

        grid.setSizeFull();
        add(grid);
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
        List<BreakoutSignal> signals = signalStore.peekAll();
        grid.setItems(signals);
    }
}
