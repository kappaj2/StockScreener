package za.co.sfh.stocklistener.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.Lumo;

/**
 * Renders a TradingView-style candlestick chart for a given symbol
 * using data from GET /api/bars/{symbol}.
 *
 * Route: /chart/{symbol}  e.g. /chart/AAPL
 */
@Route("chart/:symbol")
public class CandleChartView extends VerticalLayout implements BeforeEnterObserver {

    private String symbol = "UNKNOWN";

    public CandleChartView() {
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        getStyle()
                .set("background", "#0d1117")
                .set("min-height", "100vh");
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        symbol = event.getRouteParameters().get("symbol").orElse("UNKNOWN").toUpperCase();
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        buildUI();
    }

    private void buildUI() {
        removeAll();

        // ── Header ────────────────────────────────────────────────────────────
        HorizontalLayout header = new HorizontalLayout();
        header.setWidthFull();
        header.setPadding(true);
        header.setAlignItems(Alignment.CENTER);
        header.getStyle()
                .set("background", "#161b22")
                .set("border-bottom", "1px solid #21262d")
                .set("padding", "12px 24px")
                .set("flex-shrink", "0");

        Button back = new Button("← Back");
        back.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        back.getStyle().set("color", "#8b949e");
        back.addClickListener(e -> UI.getCurrent().navigate(""));

        H2 title = new H2(symbol + " · 1-min chart");
        title.getStyle()
                .set("margin", "0 0 0 16px")
                .set("font-size", "1.1rem")
                .set("color", "#e6edf3");

        Button refresh = new Button("↺ Refresh");
        refresh.addThemeVariants(ButtonVariant.LUMO_SMALL);
        refresh.getStyle().set("margin-left", "auto");
        refresh.addClickListener(e -> loadChart());

        header.add(back, title, refresh);

        // ── Chart container ───────────────────────────────────────────────────
        Div chartDiv = new Div();
        chartDiv.setId("candle-chart-" + symbol);
        chartDiv.setSizeFull();
        chartDiv.getStyle()
                .set("flex", "1")
                .set("position", "relative");

        add(header, chartDiv);
        setFlexGrow(1, chartDiv);

        loadChart();
    }

    private void loadChart() {
        getElement().executeJs(
            """
            (function(containerId, sym) {
                var container = document.getElementById(containerId);
                if (!container) return;
                container.innerHTML = '<div style="color:#8b949e;padding:24px;font-family:monospace">Loading ' + sym + '...</div>';

                fetch('/api/bars/' + sym)
                    .then(function(r) { return r.ok ? r.json() : Promise.reject('HTTP ' + r.status); })
                    .then(function(bars) {
                        if (!bars || bars.length === 0) {
                            container.innerHTML = '<div style="color:#8b949e;padding:24px;font-family:monospace">No bar data available for ' + sym + '</div>';
                            return;
                        }
                        drawChart(container, sym, bars);
                    })
                    .catch(function(err) {
                        container.innerHTML = '<div style="color:#ef5350;padding:24px;font-family:monospace">Error loading bars: ' + err + '</div>';
                    });

                function drawChart(container, sym, bars) {
                    container.innerHTML = '';

                    // Wrapper holds both canvases stacked
                    var wrapper = document.createElement('div');
                    wrapper.style.cssText = 'position:relative;width:100%;height:100%';
                    container.appendChild(wrapper);

                    var base = document.createElement('canvas');
                    base.style.cssText = 'position:absolute;top:0;left:0';
                    wrapper.appendChild(base);

                    var overlay = document.createElement('canvas');
                    overlay.style.cssText = 'position:absolute;top:0;left:0;cursor:crosshair';
                    wrapper.appendChild(overlay);

                    var W = wrapper.clientWidth  || 900;
                    var H = wrapper.clientHeight || 550;
                    base.width = W;    base.height = H;
                    overlay.width = W; overlay.height = H;

                    // ── Layout constants ──────────────────────────────────────
                    var PT = 55, PR = 82, PB = 48, PL = 10;
                    var cW     = W - PL - PR;
                    var cH     = H - PT - PB;
                    var volH   = cH * 0.20;
                    var gapH   = cH * 0.04;
                    var candH  = cH - volH - gapH;

                    // ── Data ranges ───────────────────────────────────────────
                    var priceHigh = -Infinity, priceLow = Infinity, maxVol = 0;
                    bars.forEach(function(b) {
                        if (b.h > priceHigh) priceHigh = b.h;
                        if (b.l < priceLow)  priceLow  = b.l;
                        if (b.v > maxVol)    maxVol    = b.v;
                    });
                    if (maxVol === 0) maxVol = 1;
                    var ppad      = (priceHigh - priceLow) * 0.06 || 0.01;
                    var priceMax  = priceHigh + ppad;
                    var priceMin  = priceLow  - ppad;
                    var priceDelta = priceMax - priceMin;

                    var n    = bars.length;
                    var slot = cW / n;
                    var bW   = Math.max(2, slot * 0.62);

                    // ── Coordinate helpers ────────────────────────────────────
                    function cx(i)     { return PL + (i + 0.5) * slot; }
                    function py(price) { return PT + candH * (1 - (price - priceMin) / priceDelta); }
                    function vy(vol)   { return PT + candH + gapH + volH * (1 - vol / maxVol); }

                    // ── Draw base chart ───────────────────────────────────────
                    var ctx = base.getContext('2d');

                    // Background
                    ctx.fillStyle = '#161b22';
                    ctx.fillRect(0, 0, W, H);

                    // Horizontal grid lines + price labels
                    var GRID_N = 6;
                    for (var g = 0; g <= GRID_N; g++) {
                        var gy      = PT + (candH / GRID_N) * g;
                        var gPrice  = priceMax - (priceDelta / GRID_N) * g;
                        ctx.strokeStyle = '#21262d';
                        ctx.lineWidth = 1;
                        ctx.beginPath();
                        ctx.moveTo(PL, gy);
                        ctx.lineTo(PL + cW, gy);
                        ctx.stroke();
                        ctx.fillStyle = '#8b949e';
                        ctx.font = '11px monospace';
                        ctx.textAlign = 'left';
                        ctx.fillText('$' + gPrice.toFixed(3), PL + cW + 6, gy + 4);
                    }

                    // Candles and volume bars
                    bars.forEach(function(b, i) {
                        var x     = cx(i);
                        var green = b.c >= b.o;
                        var col   = green ? '#26a69a' : '#ef5350';

                        var yH  = py(b.h), yL = py(b.l);
                        var yO  = py(b.o), yC = py(b.c);
                        var yTop = Math.min(yO, yC);
                        var yBot = Math.max(yO, yC);
                        var bodyH = Math.max(1, yBot - yTop);

                        // Wick
                        ctx.strokeStyle = col;
                        ctx.lineWidth = 1;
                        ctx.beginPath();
                        ctx.moveTo(x, yH);
                        ctx.lineTo(x, yL);
                        ctx.stroke();

                        // Body
                        ctx.fillStyle = col;
                        ctx.fillRect(x - bW / 2, yTop, bW, bodyH);

                        // John Wick marker dots (below lower wick for bullish, above upper wick for bearish)
                        if (b.johnWickType === 'BULLISH') {
                            ctx.fillStyle = '#ffd700';
                            ctx.beginPath();
                            ctx.arc(x, yL + 9, 3.5, 0, 6.283);
                            ctx.fill();
                        } else if (b.johnWickType === 'BEARISH') {
                            ctx.fillStyle = '#bf5fff';
                            ctx.beginPath();
                            ctx.arc(x, yH - 9, 3.5, 0, 6.283);
                            ctx.fill();
                        }

                        // Volume bar
                        var vyTop = vy(b.v);
                        var vyBot = PT + candH + gapH + volH;
                        ctx.fillStyle = green ? 'rgba(38,166,154,0.45)' : 'rgba(239,83,80,0.45)';
                        ctx.fillRect(x - bW / 2, vyTop, bW, vyBot - vyTop);
                    });

                    // Vol axis label
                    ctx.fillStyle = '#555e6b';
                    ctx.font = '10px monospace';
                    ctx.textAlign = 'left';
                    ctx.fillText('Vol', PL + cW + 6, PT + candH + gapH + 13);

                    // Time axis labels
                    ctx.fillStyle = '#8b949e';
                    ctx.font = '11px monospace';
                    ctx.textAlign = 'center';
                    var step = Math.max(1, Math.ceil(n / 12));
                    bars.forEach(function(b, i) {
                        if (i % step !== 0) return;
                        // "2026-04-09 09:42:00 ET" -> "09:42"
                        var lbl = b.s ? b.s.substring(11, 16) : String(i);
                        ctx.fillText(lbl, cx(i), PT + candH + gapH + volH + 18);
                    });

                    // Chart title
                    ctx.fillStyle = '#e6edf3';
                    ctx.font = 'bold 13px sans-serif';
                    ctx.textAlign = 'center';
                    var dateStr = bars[0].s ? bars[0].s.substring(0, 10) : '';
                    ctx.fillText(sym + '  ·  1-min  ·  ' + dateStr + '  ·  EDT', W / 2, 26);

                    // Subtitle / hover hint
                    ctx.fillStyle = '#555e6b';
                    ctx.font = '11px sans-serif';
                    ctx.fillText('Hover over a candle for details', W / 2, 44);

                    // Legend
                    var lx = PL;
                    ctx.fillStyle = '#ffd700';
                    ctx.fillRect(lx, H - PB + 12, 9, 9);
                    ctx.fillStyle = '#8b949e';
                    ctx.font = '10px monospace';
                    ctx.textAlign = 'left';
                    ctx.fillText('Bullish JohnWick', lx + 13, H - PB + 20);

                    ctx.fillStyle = '#bf5fff';
                    ctx.fillRect(lx + 148, H - PB + 12, 9, 9);
                    ctx.fillStyle = '#8b949e';
                    ctx.fillText('Bearish JohnWick', lx + 162, H - PB + 20);

                    // ── Overlay: crosshair + tooltip ──────────────────────────
                    var oct = overlay.getContext('2d');

                    overlay.addEventListener('mousemove', function(e) {
                        var rect = overlay.getBoundingClientRect();
                        var mx   = (e.clientX - rect.left) * (W / rect.width);
                        var my   = (e.clientY - rect.top)  * (H / rect.height);

                        oct.clearRect(0, 0, W, H);

                        if (mx < PL || mx > PL + cW) return;

                        // Nearest candle index
                        var idx = Math.max(0, Math.min(n - 1, Math.floor((mx - PL) / slot)));
                        var b   = bars[idx];
                        var bx  = cx(idx);

                        // Crosshair vertical line (full chart height)
                        oct.strokeStyle = 'rgba(139,148,158,0.45)';
                        oct.lineWidth = 1;
                        oct.setLineDash([4, 4]);
                        oct.beginPath();
                        oct.moveTo(bx, PT);
                        oct.lineTo(bx, PT + candH + gapH + volH);
                        oct.stroke();

                        // Crosshair horizontal (only in candle area)
                        if (my >= PT && my <= PT + candH) {
                            oct.beginPath();
                            oct.moveTo(PL, my);
                            oct.lineTo(PL + cW, my);
                            oct.stroke();

                            // Price tag on right axis
                            var hoverPrice = priceMin + priceDelta * (1 - (my - PT) / candH);
                            oct.fillStyle = '#444c56';
                            oct.fillRect(PL + cW + 1, my - 9, PR - 2, 18);
                            oct.fillStyle = '#e6edf3';
                            oct.font = '11px monospace';
                            oct.textAlign = 'left';
                            oct.setLineDash([]);
                            oct.fillText('$' + hoverPrice.toFixed(3), PL + cW + 5, my + 4);
                        }
                        oct.setLineDash([]);

                        // Tooltip
                        var green  = b.c >= b.o;
                        var bRange = b.range  !== undefined ? b.range  : (b.h - b.l);
                        var bBody  = b.body   !== undefined ? b.body   : Math.abs(b.c - b.o);
                        var bUW    = b.upperWick !== undefined ? b.upperWick : (b.h - Math.max(b.o, b.c));
                        var bLW    = b.lowerWick !== undefined ? b.lowerWick : (Math.min(b.o, b.c) - b.l);
                        var jwType = b.johnWickType || 'NONE';
                        var timeStr = b.s ? b.s.substring(0, 19) : '';

                        var lines = [
                            timeStr,
                            'O ' + b.o.toFixed(3) + '   C ' + b.c.toFixed(3),
                            'H ' + b.h.toFixed(3) + '   L ' + b.l.toFixed(3),
                            'Range:      ' + bRange.toFixed(3),
                            'Body:       ' + bBody.toFixed(3),
                            'Upper wick: ' + bUW.toFixed(3),
                            'Lower wick: ' + bLW.toFixed(3),
                            'Volume:     ' + b.v.toLocaleString(),
                            'JohnWick:   ' + jwType
                        ];

                        var ttW  = 210;
                        var ttLH = 15;
                        var ttH  = lines.length * ttLH + 18;
                        var ttX  = bx + 14;
                        var ttY  = PT + 8;
                        if (ttX + ttW > W - PR) ttX = bx - ttW - 14;

                        // Box
                        oct.fillStyle = 'rgba(22,27,34,0.93)';
                        oct.strokeStyle = green ? '#26a69a' : '#ef5350';
                        oct.lineWidth = 1;
                        oct.fillRect(ttX, ttY, ttW, ttH);
                        oct.strokeRect(ttX, ttY, ttW, ttH);

                        // Text
                        lines.forEach(function(line, li) {
                            if (li === 0) {
                                oct.fillStyle = green ? '#26a69a' : '#ef5350';
                                oct.font = 'bold 11px monospace';
                            } else if (li === lines.length - 1 && jwType !== 'NONE') {
                                oct.fillStyle = jwType === 'BULLISH' ? '#ffd700' : '#bf5fff';
                                oct.font = 'bold 11px monospace';
                            } else {
                                oct.fillStyle = '#c9d1d9';
                                oct.font = '11px monospace';
                            }
                            oct.textAlign = 'left';
                            oct.fillText(line, ttX + 8, ttY + 13 + li * ttLH);
                        });
                    });

                    overlay.addEventListener('mouseleave', function() {
                        oct.clearRect(0, 0, W, H);
                    });
                }
            })($0, $1);
            """,
            "candle-chart-" + symbol,
            symbol
        );
    }
}
