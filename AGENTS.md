# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

---

## Project Overview

Real-time pre-market breakout scanner that:
1. Connects to the **Polygon.io WebSocket API** for live 1-minute aggregate bars (AM events)
2. Connects to **IBKR TWS API** for real-time Level II tape reading
3. Maintains per-symbol rolling state (candles, EMA, VWAP, indicators) in memory and **Redis**
4. Runs pluggable **PatternScanner** implementations on every incoming bar
5. Optionally confirms candidates via **local Ollama LLM** (gemma4:e4b)
6. Queues confirmed signals in memory, exposes via REST for downstream TradingView alert delivery

---

## Technology Stack

| Layer | Technology |
|---|---|
| Language | Java 25 |
| Framework | Spring Boot 4.0.5 |
| State persistence | Redis (JSON blobs via `StringRedisTemplate`) |
| LLM integration | Spring AI + Ollama (gemma4:e4b) |
| WebSocket client | Spring WebSocket (`StandardWebSocketClient`) |
| IBKR integration | IBKR TWS API 10.30 (protobuf 4.x) |
| HTTP server | Spring MVC (port 8000) |
| UI | Vaadin 25 |
| JSON | Jackson 3.x (`tools.jackson` + fasterxml annotations) |
| Build | Maven |
| Containerisation | Docker (Azul Zulu OpenJDK 25 Alpine) |

---

## Common Commands

```bash
# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=BreakoutPatternScannerTest

# Build (skip tests)
./mvnw clean package -DskipTests

# Run locally (Redis + Ollama must be running)
MASSIVE_API_KEY=your_key ./mvnw spring-boot:run

# Build for production (compiles Vaadin frontend)
./mvnw clean package -Pproduction

# Start dependencies only
docker compose up redis -d

# Run full stack (app + redis)
MASSIVE_API_KEY=your_key docker compose up app

# Run in recording mode (saves raw WebSocket messages to src/test/resources/fixtures/)
MASSIVE_API_KEY=your_key docker compose up recorder

# Start Ollama model
ollama serve && ollama pull gemma4:e4b

# News enricher (polls /api/signals/pending and enriches with NewsAPI headlines)
NEWSAPI_KEY=your_key python3 scripts/news_enricher.py          # daemon mode
NEWSAPI_KEY=your_key python3 scripts/news_enricher.py --once   # single pass
```

---

## Architecture

### Message Processing Pipeline

```
Polygon WebSocket (virtual thread)
  └─→ MessageProcessor.offer()           # enqueue raw JSON string
        └─→ startProcessing()            # dedicated virtual thread, polls queue
              └─→ dispatch by ev field
                    ├─→ "AM"  → AggregateMinuteBarHandler
                    │           ├─→ filter (price, volume)
                    │           ├─→ SymbolState.addBar()
                    │           ├─→ DontDiddleInTheMiddle.isInMiddle()  # signal gate
                    │           ├─→ PatternScanner.scan() × N
                    │           └─→ OllamaBreakoutAnalyser.analyseAsync()  (if candidate)
                    │                   └─→ SignalStore.add()  (if confirmed + confident)
                    └─→ "status" → ProcessStatusMessage

IBKR TWS (EJavaSignal thread, separate from Polygon)
  └─→ IbkrTapeClient (EWrapperDelegate inner class)
        └─→ IbkrTickStore (rolling buffer per symbol, max 500 ticks)
              └─→ /api/tape/* endpoints
```

### Pattern Scanner Architecture

All scanners implement `PatternScanner`:

```java
public interface PatternScanner {
    Optional<BreakoutSignal> scan(AggregateMinuteBar bar, SymbolState state);
}
```

Beans are auto-collected as `List<PatternScanner>` in `AggregateMinuteBarHandler` via Spring injection.

**Active scanners:**

| Scanner | Status | Logic |
|---|---|---|
| `BreakoutPatternScanner` | Active | Previous + current bar both green, range > 2× avg, volume > 2× avg |
| `MomentumStrengthScanner` | Active | Multi-indicator scoring: LinReg + RSI + ROC + MACD + Volume; emits HIGH or VERY_HIGH signal |
| `UnsharpenPatternScanner` | Implemented, signal disabled (`storeSignal: false`) | Bearish → Bullish John Wick → current green above John Wick high |
| `InvertedVeePatternScanner` | TODO stub | Always returns `Optional.empty()` |

**`DontDiddleInTheMiddle`** is not a `PatternScanner` — it's a `@Component` gate called by `AggregateMinuteBarHandler` that suppresses signals whose entry price falls in the middle `patterns.dont-diddle.percentage`% of yesterday's range.

**Adding a new pattern:**
1. Create a class in `processor/scanners/` implementing `PatternScanner`, annotate `@Component`
2. Add threshold values in `application.yaml` under `patterns.*`, inject via `@Value`
3. Return `Optional.empty()` on no-match; populated `Optional<BreakoutSignal>` on match
4. Add a constant to `PatternType` enum in `signals/PatternType.java`

### Technical Indicators (`processor/indicators/`)

Each indicator is a Spring `@Component` with configurable parameters under `indicators.*`:

| Indicator | Config prefix | Purpose |
|---|---|---|
| `RsiIndicator` | `indicators.rsi` | Wilder's RSI (period 14) |
| `RocIndicator` | `indicators.roc` | Rate of Change with acceleration detection |
| `MacdIndicator` | `indicators.macd` | MACD histogram + crossover detection |
| `LinearRegressionIndicator` | `indicators.linreg` | Slope + R² trend strength over 60 bars |
| `VolumeProfileIndicator` | `indicators.volume` | Short vs long window volume surge detection |

All five are injected into `MomentumStrengthScanner`. Do not hold indicator state per-symbol inside these classes — read from `SymbolState`.

### IBKR Integration (`ibkr/`)

- `IbkrTapeClient` — Spring `@Component` that connects to TWS on `ApplicationReadyEvent`. Uses an inner `EWrapperDelegate` class rather than extending `DefaultEWrapper` directly (required because Spring CGLIB proxying cannot subclass third-party types under Java 25 modules).
- `IbkrTickStore` — rolling tick buffer (`ConcurrentHashMap<String, Deque<IbkrTickEvent>>`), configurable max depth (`ibkr.tape.max-ticks`, default 500).
- `IbkrTapeController` — REST API at `/api/tape/*`.
- Enable with `ibkr.enabled=true`; TWS live port = 7496, paper = 7497, IB Gateway = 4001.

### Ollama LLM Integration

Two separate Ollama callers:
- `OllamaBreakoutAnalyser` — used by `BreakoutPatternScanner`; always `@Async`
- `OllamaStrengthScanner` — used by `MomentumStrengthScanner`; gated by `patterns.momentum.ollama-enabled` (default `false`)

Both parse responses into structured records (`BreakoutAnalysis`). Temperature 0.1, format JSON.

---

## REST API

Base path: `/api`

| Method | Path | Description |
|---|---|---|
| GET | `/signals/pending` | All queued `BreakoutSignal` objects |
| DELETE | `/signals/ack` | Clear signal queue |
| PUT | `/signals/{id}/news` | Attach news headline to signal |
| GET | `/signals/state/{symbol}` | `SymbolState` for ticker, or 404 |
| GET | `/bars/{symbol}` | Last N 1-minute bars |
| GET | `/tape/{symbol}` | Rolling tick buffer for symbol |
| POST | `/tape/subscribe/{symbol}` | Start IBKR streaming for symbol |
| DELETE | `/tape/subscribe/{symbol}` | Stop streaming and clear buffer |
| GET | `/tape/symbols` | All subscribed IBKR symbols |
| GET | `/tape/status` | IBKR connection status |

---

## Configuration (`application.yaml`)

Secrets are injected via environment variables — never hardcode them.

| Key | Default | Purpose |
|---|---|---|
| `massive.api-key` | `${MASSIVE_API_KEY}` | Polygon.io API key |
| `massive.symbols` | `AM.*` | Polygon subscription pattern |
| `massive.cron.start/stop` | `04:00/20:00` ET | WebSocket connect/disconnect schedule |
| `filter.min-close` | `0.1` | Minimum bar close price |
| `filter.min-volume` | `500` | Minimum bar volume |
| `patterns.breakout.stop/target` | `0.9/1.05` | Stop/target multipliers |
| `patterns.breakout.storeSignal` | `true` | Emit to SignalStore |
| `patterns.dont-diddle.percentage` | `30` | Middle % of prev day range to suppress |
| `patterns.momentum.stop/target` | `0.97/1.06` | Momentum stop/target multipliers |
| `patterns.momentum.high-score` | `7` | Minimum score for HIGH signal |
| `patterns.momentum.very-high-score` | `10` | Minimum score for VERY_HIGH signal |
| `patterns.momentum.ollama-enabled` | `false` | Enable Ollama second opinion for momentum |
| `indicators.rsi.period` | `14` | RSI look-back |
| `indicators.macd.fast/slow/signal` | `12/26/9` | MACD periods |
| `indicators.linreg.period` | `60` | Linear regression window (bars) |
| `indicators.volume.surge-threshold` | `1.5` | Short/long ratio for volume surge |
| `ollama.breakout.min-confidence` | `70` | Minimum confidence to emit a signal |
| `ibkr.enabled` | `false` | Enable IBKR TWS connection |
| `ibkr.port` | `7496` | TWS port (7497 = paper, 4001 = IB Gateway) |
| `spring.ai.ollama.chat.model` | `gemma4:e4b` | Ollama model |
| `server.port` | `8000` | HTTP port |

---

## Coding Conventions

- Use **Lombok** (`@Slf4j`, `@RequiredArgsConstructor`, `@Value`, `@Builder`) and **Java records** for DTOs
- Use **virtual threads** for all I/O — rely on `spring.threads.virtual.enabled=true` and `@Async`; no manual thread creation
- All per-symbol state lives in `SymbolState`; scanners and indicators must never hold per-symbol state themselves
- `SymbolStateSnapshot` is the only class that crosses the Redis boundary — keep it serialisable (no complex types)
- LLM calls must always be `@Async` — never call them synchronously from the message processing loop
- Log and swallow exceptions in the message processing loop to prevent dropping subsequent messages
- Redis failures must be caught and logged; the app continues without Redis

---

## Testing

Only a single context-load smoke test exists (`StocklistenerApplicationTests`). No unit tests for scanners/indicators yet.

- Scanner unit tests: construct a `SymbolState` with controlled bar history; assert `Optional` result
- REST endpoint tests: `@SpringBootTest` + `MockMvc`; mock `SignalStore`, `SymbolStateRedisStore`, `IbkrTapeClient`
- Do **not** call the real Polygon WebSocket, Ollama, or IBKR TWS in tests

---

## Known Limitations / Future Work

- `InvertedVeePatternScanner` — stub, always returns `Optional.empty()`
- `UnsharpenPatternScanner` — logic complete but `storeSignal: false`; enable once validated
- `SignalStore` is in-memory only — signals lost on restart
- No `@ControllerAdvice` global error handler
- No REST API authentication
- `BarRecorder` is off by default — enable in `MessageProcessor` for raw message capture
- Vaadin UI views (`CandleChartView`, `SignalView`) exist but may not be fully wired

## Imported Claude Cowork project instructions
