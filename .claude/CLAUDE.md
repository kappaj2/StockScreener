# CLAUDE.md — Stock Listener Project Guide

This file guides Claude's development assistance for the **Stock Listener** Spring Boot application. Read this before making any changes.

---

## Project Overview

The Stock Listener is a real-time pre-market breakout scanner that:
1. Connects to the **Polygon.io WebSocket API** to receive live 1-minute aggregate bars (AM events) for all symbols
2. Maintains per-symbol rolling state (candles, EMA, VWAP, pre-market high/low) in memory and **Redis**
3. Runs pluggable **PatternScanner** implementations on every incoming bar to detect trade setups
4. Confirms breakout candidates asynchronously via a **local Ollama LLM** (qwen3:8b)
5. Queues confirmed signals in memory and exposes them via a **REST API** for downstream TradingView alert delivery

---

## Technology Stack

| Layer | Technology |
|---|---|
| Language | Java 25 |
| Framework | Spring Boot 4.0.5 |
| State persistence | Redis (JSON blobs via StringRedisTemplate) |
| In-memory queue | ConcurrentHashMap / ConcurrentLinkedQueue |
| LLM integration | Spring AI + Ollama (qwen3:8b) |
| WebSocket client | Spring WebSocket (`StandardWebSocketClient`) |
| HTTP server | Spring MVC (port 8000) |
| UI | Vaadin 25 |
| JSON | Jackson 3.x (tools.jackson + fasterxml annotations) |
| Code generation | Lombok |
| Build | Maven |
| Containerisation | Docker (Azul Zulu OpenJDK 25 Alpine) |
| Testing | JUnit 5 + Spring Boot Test |

---

## Package Structure

```
za.co.sfh.stocklistener
├── StocklistenerApplication.java        # Entry point (@SpringBootApplication, @EnableScheduling)
├── config/
│   ├── AsyncConfig.java                 # Virtual-thread executor for @Async
│   └── JacksonConfig.java               # ObjectMapper customisation
├── payloads/
│   ├── AggregateMinuteBar.java          # Polygon AM event record (with range/body/wick helpers)
│   ├── BreakoutAnalysis.java            # LLM response record
│   ├── EpochMsDeserializer.java         # Jackson: epoch-ms → ZonedDateTime
│   ├── EpochMsSerializer.java           # Jackson: ZonedDateTime → epoch-ms
│   ├── JohnWickType.java                # Enum: NONE / BULLISH / BEARISH candle classification
│   └── StatusMessage.java               # Polygon connection/auth status event
├── processor/
│   ├── MessageHandler.java              # Interface: handle(JsonNode node)
│   ├── MessageProcessor.java            # Dispatcher: queue → virtual thread → handlers by ev type
│   ├── AggregateMinuteBarHandler.java   # Processes AM events, updates SymbolState, runs scanners
│   ├── ProcessStatusMessage.java        # Handles connection/auth status events
│   ├── PatternScanner.java              # Interface: Optional<BreakoutSignal> scan(bar, state)
│   ├── ollama/
│   │   └── OllamaBreakoutAnalyser.java  # Async LLM confirmation via Spring AI ChatClient
│   ├── scanners/
│   │   ├── BreakoutPatternScanner.java  # 2-candle breakout (primary, active)
│   │   ├── InvertedVeePatternScanner.java  # TODO: not yet implemented
│   │   └── UnsharpenPatternScanner.java # John Wick pattern (implemented, signal disabled)
│   └── states/
│       ├── SymbolState.java             # Per-symbol rolling window: candles, EMA9, VWAP, averages
│       ├── SymbolStateRedisStore.java   # Persist/rehydrate SymbolState as JSON in Redis
│       └── SymbolStateSnapshot.java     # Serialisable DTO for Redis storage
├── recorder/
│   └── BarRecorder.java                 # Debug utility: records raw WebSocket messages to file
├── signals/
│   ├── BreakoutSignal.java              # Signal record: id, symbol, pattern, entry/stop/target, ...
│   ├── SignalStore.java                 # In-memory ConcurrentHashMap of pending signals
│   ├── SignalController.java            # REST: GET /api/signals, DELETE ack, PUT news, GET state
│   └── BarController.java              # REST: GET /api/bars/{symbol}
├── ui/
│   ├── CandleChartView.java             # Vaadin candlestick chart view
│   └── SignalView.java                  # Vaadin signal list view
└── websocket/
    └── MassiveWebSocketClient.java      # Polygon.io WebSocket client (auth, subscribe, reconnect)
```

---

## Domain Model

### Core Records / DTOs

**`AggregateMinuteBar`** — Polygon AM WebSocket event
- Fields: `symbol`, `volume`, `open`, `close`, `high`, `low`, `vwap`, `startTime`, `endTime`
- Computed: `range()`, `body()`, `upperWick()`, `lowerWick()`, `johnWickType()`
- Epoch-ms timestamps deserialised via `EpochMsDeserializer`

**`BreakoutSignal`** — Queued trade setup
- Fields: `id` (UUID), `symbol`, `pattern`, `entry`, `stop`, `target`, `confidence`, `risk`, `notes`, `timestamp`, `preMarketHigh`, `preMarketLow`, `news`
- Held in `SignalStore` until TradingView alerts are acknowledged via `DELETE /api/signals/ack`

**`BreakoutAnalysis`** — LLM response from Ollama
- Fields: `confirmed` (bool), `confidence` (0–100), `entry`, `stop`, `target`, `risk`, `notes`

**`SymbolState`** — Live per-symbol rolling state
- Rolling 20-bar candle window (FIFO)
- Pre-market high/low (04:00–09:29 ET)
- Session VWAP (reset daily)
- 9-period EMA of closes (seeded with SMA of first 9)
- `avgRange` and `avgVolume` (10-bar rolling averages)
- Persisted to/from Redis via `SymbolStateRedisStore` after every bar

### Key Invariants
- One `SymbolState` per ticker, stored in a `ConcurrentHashMap` in `AggregateMinuteBarHandler`
- Bars below `filter.min-close` ($0.10) or `filter.min-volume` (500) are silently dropped
- Ollama analysis fires **asynchronously** — never block the message processing queue
- A signal is only emitted when Ollama returns `confirmed = true` AND `confidence ≥ ollama.breakout.min-confidence` (default 70)

---

## Message Processing Pipeline

```
Polygon WebSocket (virtual thread)
  └─→ MessageProcessor.offer()           # enqueue raw JSON string
        └─→ startProcessing()            # dedicated virtual thread, polls queue
              └─→ dispatch by ev field
                    ├─→ "AM"  → AggregateMinuteBarHandler
                    │           ├─→ filter (price, volume)
                    │           ├─→ SymbolState.addBar()
                    │           ├─→ PatternScanner.scan() × N
                    │           └─→ OllamaBreakoutAnalyser.analyseAsync()  (if candidate)
                    │                   └─→ SignalStore.add()  (if confirmed + confident)
                    └─→ "status" → ProcessStatusMessage
```

---

## Pattern Scanner Architecture

All scanners implement `PatternScanner`:

```java
public interface PatternScanner {
    Optional<BreakoutSignal> scan(AggregateMinuteBar bar, SymbolState state);
}
```

All beans are auto-collected as `List<PatternScanner>` in `AggregateMinuteBarHandler` via Spring injection.

**Adding a new pattern:**
1. Create a new class in `processor/scanners/` implementing `PatternScanner`
2. Annotate with `@Component` — it will be picked up automatically
3. Inject any threshold values via `@Value` from `application.yaml` under a new `patterns.*` key
4. Return `Optional.empty()` if the pattern does not match; return a populated `Optional<BreakoutSignal>` if it does

**Active scanners:**
| Scanner | Status | Logic |
|---|---|---|
| `BreakoutPatternScanner` | Active | Previous bar green + current bar green with range > 2× avg and volume > 2× avg |
| `InvertedVeePatternScanner` | TODO stub | Returns `Optional.empty()` — not implemented |
| `UnsharpenPatternScanner` | Implemented, disabled | Bearish → Bullish John Wick → current green above John Wick high (signal emission off) |

---

## REST API

Base path: `/api`

| Method | Path | Description |
|---|---|---|
| GET | `/signals/pending` | Returns all queued `BreakoutSignal` objects (non-destructive) |
| DELETE | `/signals/ack` | Clears the signal queue (call after TradingView alerts are fired) |
| PUT | `/signals/{id}/news` | Attaches a news headline string to an existing signal |
| GET | `/signals/state/{symbol}` | Returns `SymbolState` for the given ticker, or 404 |
| GET | `/bars/{symbol}` | Returns last N 1-minute bars for the given symbol |

---

## Configuration (`application.yaml`)

All secrets are injected via environment variables — **never hardcode them**.

| Key | Purpose |
|---|---|
| `massive.api-key` | Polygon.io API key (`MASSIVE_API_KEY` env var) |
| `massive.symbols` | Polygon subscription pattern (e.g. `AM.*`) |
| `massive.cron.start / .stop` | Cron schedule for WebSocket connect/disconnect |
| `massive.window.start / .stop` | Time window for active message processing |
| `filter.min-close` | Minimum bar close price (default $0.10) |
| `filter.min-volume` | Minimum bar volume (default 500 shares) |
| `patterns.breakout.stop` | Stop-loss multiplier (default 0.90) |
| `patterns.breakout.target` | Target multiplier (default 1.05) |
| `ollama.breakout.min-confidence` | Minimum Ollama confidence to emit a signal (default 70) |
| `spring.data.redis.host` | Redis host (default localhost) |
| `spring.ai.ollama.base-url` | Ollama server URL (default http://localhost:11434) |
| `spring.ai.ollama.chat.model` | Ollama model name (default qwen3:8b) |
| `server.port` | HTTP server port (default 8000) |

---

## Coding Conventions

### General
- Use **Lombok** (`@Slf4j`, `@RequiredArgsConstructor`, `@Value`, `@Builder`) to reduce boilerplate
- Use **Java records** for immutable DTOs and payloads
- Use **virtual threads** for all I/O — do not create dedicated `Thread` objects; rely on `spring.threads.virtual.enabled=true` and `@Async`
- Prefer `ConcurrentHashMap` and `ConcurrentLinkedQueue` over explicit synchronisation

### State Management
- All per-symbol state lives in `SymbolState`; never hold state in individual scanners
- After every `addBar()` call, `SymbolStateRedisStore.save()` is invoked — keep this path fast (fire-and-forget virtual thread is acceptable)
- `SymbolStateSnapshot` is the only class that crosses the Redis boundary; keep it serialisable (no complex types)

### Async / Threading
- LLM calls (`OllamaBreakoutAnalyser`) must always be `@Async` — never call them synchronously from the message processing loop
- The `AsyncConfig` executor uses virtual threads; do not add a custom pool unless there is a specific reason

### Error Handling
- Log and swallow exceptions in the message processing loop to avoid dropping subsequent messages
- WebSocket reconnection is handled by `MassiveWebSocketClient`; do not add reconnect logic elsewhere
- Redis failures should be caught and logged; the app must continue processing even if Redis is unavailable

### Controllers
- Thin controllers; delegate all logic to `SignalStore` / `SymbolState` directly or via a service
- Use `ResponseEntity<>` for endpoints that may return 404 (e.g. `GET /signals/state/{symbol}`)

---

## External Integrations

### Polygon.io WebSocket (`MassiveWebSocketClient`)
- Endpoint: `wss://delayed.massive.com/stocks`
- Auth: Bearer token via `MASSIVE_API_KEY` environment variable
- Connects at `massive.cron.start` (default 10:00 ET), disconnects at `massive.cron.stop` (default 22:00 ET)
- Subscription pattern: `massive.symbols` (default `AM.*` = all 1-minute aggregate bars)

### Ollama Local LLM (`OllamaBreakoutAnalyser`)
- Model: `qwen3:8b` (configurable via `spring.ai.ollama.chat.model`)
- Temperature: 0.1, format: json — to get deterministic structured output
- Prompt: last 20 bars as JSON array + pattern criteria
- Response parsed into `BreakoutAnalysis` record

### Redis (`SymbolStateRedisStore`)
- Key format: `sym:{symbol}` (e.g. `sym:AAPL`)
- Value: JSON serialisation of `SymbolStateSnapshot`
- On app startup, existing keys are rehydrated to resume state without replaying bar history
- Timeout: 200ms connect + read (non-blocking, failures are tolerated)

### TradingView (external scheduled Cowork task)
- Polls `GET /api/signals/pending` every 5 minutes (weekdays 04:00–09:59 ET)
- For each signal: switches TradingView chart symbol, creates an alert, takes a screenshot
- Calls `DELETE /api/signals/ack` to clear the queue after processing

---

## Testing

### Current State
- Only a single context-load smoke test exists (`StocklistenerApplicationTests`)
- No integration or unit tests for pattern scanners, state management, or REST endpoints yet

### Writing Tests
- Unit tests for `PatternScanner` implementations: inject a mock `SymbolState` with controlled bar history; assert `Optional` result
- Integration tests for REST endpoints: use `@SpringBootTest` + `MockMvc`; mock `SignalStore` and `SymbolStateRedisStore`
- Redis integration tests: use Testcontainers (`GenericContainer` for Redis) if live Redis behaviour needs verification
- Do **not** call the real Polygon WebSocket or Ollama in tests — mock `MassiveWebSocketClient` and `OllamaBreakoutAnalyser`

---

## Running Locally

```bash
# Start Redis
docker run -d -p 6379:6379 redis:7-alpine

# Start Ollama with the required model
ollama serve
ollama pull qwen3:8b

# Run the application
MASSIVE_API_KEY=your_polygon_key \
./mvnw spring-boot:run

# Run tests
./mvnw test

# Build Docker image
./mvnw clean package -DskipTests
docker build -t stocklistener:latest .

# Run Docker container
docker run -p 8000:8000 \
  -e MASSIVE_API_KEY=your_polygon_key \
  -e SPRING_DATA_REDIS_HOST=host.docker.internal \
  stocklistener:latest
```

---

## Known Limitations / Future Work

- `InvertedVeePatternScanner` is a stub — always returns `Optional.empty()`; needs implementation
- `UnsharpenPatternScanner` pattern logic is complete but **signal emission is disabled** — enable once pattern is validated
- `BarRecorder` is a debug utility; recording is off by default — enable in `MessageProcessor` when raw message capture is needed
- No authentication on the REST API — add Spring Security if the service is ever exposed beyond localhost
- `SignalStore` is in-memory only — signals are lost on restart; consider persisting to Redis alongside `SymbolState` if durability is needed
- No `@ControllerAdvice` / global error handler — add one when standardised HTTP error responses become important
- Vaadin UI views (`CandleChartView`, `SignalView`) exist but may not be fully wired — verify before relying on them
- No docker-compose file for local dependency orchestration (Redis + Ollama) — consider adding one for convenience
