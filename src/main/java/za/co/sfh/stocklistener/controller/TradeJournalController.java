package za.co.sfh.stocklistener.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import za.co.sfh.stocklistener.journal.CycleContext;
import za.co.sfh.stocklistener.journal.TradeDirection;
import za.co.sfh.stocklistener.journal.TradeJournalService;
import za.co.sfh.stocklistener.persistence.entities.TradeJournalEntity;
import za.co.sfh.stocklistener.signals.PatternType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

@Controller
@RequestMapping("/journal")
@RequiredArgsConstructor
public class TradeJournalController {

    private final TradeJournalService journalService;

    @GetMapping
    public String list(Model model) {
        List<TradeJournalEntity> entries = journalService.findAll();
        model.addAttribute("entries", entries);
        model.addAttribute("form", new TradeForm());
        model.addAttribute("directions", TradeDirection.values());
        model.addAttribute("setups", patternOptions());
        model.addAttribute("timeframes", List.of("1m", "2m", "5m", "15m", "30m", "1h", "Daily"));
        model.addAttribute("cycleContexts", CycleContext.values());
        model.addAttribute("today", LocalDate.now());
        return "journal";
    }

    @GetMapping("/edit/{id}")
    public String edit(@PathVariable Long id, Model model) {
        TradeJournalEntity entry = journalService.findById(id).orElseThrow();
        model.addAttribute("entries", journalService.findAll());
        model.addAttribute("form", TradeForm.from(entry));
        model.addAttribute("directions", TradeDirection.values());
        model.addAttribute("setups", patternOptions());
        model.addAttribute("timeframes", List.of("1m", "2m", "5m", "15m", "30m", "1h", "Daily"));
        model.addAttribute("cycleContexts", CycleContext.values());
        model.addAttribute("today", LocalDate.now());
        return "journal";
    }

    @PostMapping("/save")
    public String save(@ModelAttribute TradeForm form, RedirectAttributes ra) {
        TradeJournalEntity entry = form.getId() != null
                ? journalService.findById(form.getId()).orElse(TradeJournalEntity.builder().build())
                : TradeJournalEntity.builder().build();

        entry.setTradeDate(form.getTradeDate() != null ? form.getTradeDate() : LocalDate.now());
        entry.setTicker(form.getTicker().trim().toUpperCase());
        entry.setDirection(form.getDirection());
        entry.setEntryPrice(bd(form.getEntryPrice()));
        entry.setStopPrice(bd(form.getStopPrice()));
        entry.setExitPrice(form.getExitPrice() != null ? bd(form.getExitPrice()) : null);
        entry.setSetup(form.getSetup());
        entry.setTimeframe(form.getTimeframe());
        entry.setCycleContext(form.getCycleContext());
        entry.setFollowedPlan(form.isFollowedPlan());
        entry.setScreenshotUrl(blank(form.getScreenshotUrl()));
        entry.setWhatWentWell(blank(form.getWhatWentWell()));
        entry.setWhatToImprove(blank(form.getWhatToImprove()));

        journalService.save(entry);
        ra.addFlashAttribute("saved", true);
        return "redirect:/journal";
    }

    @PostMapping("/delete/{id}")
    public String delete(@PathVariable Long id) {
        journalService.delete(id);
        return "redirect:/journal";
    }

    private static List<String> patternOptions() {
        List<String> opts = Arrays.stream(PatternType.values()).map(Enum::name).collect(java.util.stream.Collectors.toList());
        opts.add("Other");
        return opts;
    }

    private static BigDecimal bd(Double v) {
        return v != null ? BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }

    private static String blank(String v) {
        return (v != null && !v.isBlank()) ? v.trim() : null;
    }

    public static class TradeForm {
        private Long id;
        private LocalDate tradeDate;
        private String ticker;
        private TradeDirection direction = TradeDirection.LONG;
        private Double entryPrice;
        private Double stopPrice;
        private Double exitPrice;
        private String setup;
        private String timeframe;
        private CycleContext cycleContext;
        private boolean followedPlan;
        private String screenshotUrl;
        private String whatWentWell;
        private String whatToImprove;

        public static TradeForm from(TradeJournalEntity e) {
            TradeForm f = new TradeForm();
            f.id = e.getId();
            f.tradeDate = e.getTradeDate();
            f.ticker = e.getTicker();
            f.direction = e.getDirection();
            f.entryPrice = e.getEntryPrice() != null ? e.getEntryPrice().doubleValue() : null;
            f.stopPrice = e.getStopPrice() != null ? e.getStopPrice().doubleValue() : null;
            f.exitPrice = e.getExitPrice() != null ? e.getExitPrice().doubleValue() : null;
            f.setup = e.getSetup();
            f.timeframe = e.getTimeframe();
            f.cycleContext = e.getCycleContext();
            f.followedPlan = Boolean.TRUE.equals(e.getFollowedPlan());
            f.screenshotUrl = e.getScreenshotUrl();
            f.whatWentWell = e.getWhatWentWell();
            f.whatToImprove = e.getWhatToImprove();
            return f;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public LocalDate getTradeDate() { return tradeDate; }
        public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }
        public String getTicker() { return ticker; }
        public void setTicker(String ticker) { this.ticker = ticker; }
        public TradeDirection getDirection() { return direction; }
        public void setDirection(TradeDirection direction) { this.direction = direction; }
        public Double getEntryPrice() { return entryPrice; }
        public void setEntryPrice(Double entryPrice) { this.entryPrice = entryPrice; }
        public Double getStopPrice() { return stopPrice; }
        public void setStopPrice(Double stopPrice) { this.stopPrice = stopPrice; }
        public Double getExitPrice() { return exitPrice; }
        public void setExitPrice(Double exitPrice) { this.exitPrice = exitPrice; }
        public String getSetup() { return setup; }
        public void setSetup(String setup) { this.setup = setup; }
        public String getTimeframe() { return timeframe; }
        public void setTimeframe(String timeframe) { this.timeframe = timeframe; }
        public CycleContext getCycleContext() { return cycleContext; }
        public void setCycleContext(CycleContext cycleContext) { this.cycleContext = cycleContext; }
        public boolean isFollowedPlan() { return followedPlan; }
        public void setFollowedPlan(boolean followedPlan) { this.followedPlan = followedPlan; }
        public String getScreenshotUrl() { return screenshotUrl; }
        public void setScreenshotUrl(String screenshotUrl) { this.screenshotUrl = screenshotUrl; }
        public String getWhatWentWell() { return whatWentWell; }
        public void setWhatWentWell(String whatWentWell) { this.whatWentWell = whatWentWell; }
        public String getWhatToImprove() { return whatToImprove; }
        public void setWhatToImprove(String whatToImprove) { this.whatToImprove = whatToImprove; }
    }
}