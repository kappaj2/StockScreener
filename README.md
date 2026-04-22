# Stock Listener — Pre-Market Breakout Scanner

A Spring Boot 4 application that listens to the [Polygon.io](https://polygon.io) real-time WebSocket feed, filters and analyses pre-market equity bars using a local Ollama LLM, and surfaces confirmed breakout signals as TradingView alerts.

### Database Setup
```mysql
CREATE DATABASE stock;

CREATE USER 'stock'@'%' IDENTIFIED BY 'stock';
GRANT ALL PRIVILEGES ON stock . * TO 'stock'@'%';
flush privileges;

---

## Architecture

```
Polygon WebSocket
      │
      ▼
MassiveWebSocketClient          Raw WS frames received and forwarded to processor
      │
      ▼
MessageProcessor                Queues messages on a virtual thread; dispatches by event type ("AM", etc.)
      │
      ▼
AggregateMinuteBarHandler
  ├─ Pre-filter ──────────────► drop if close < $2.00 or volume < 50,000
  ├─ SymbolState ─────────────► accumulate rolling 20-bar window per symbol
  │                              track pre-market high/low (04:00–09:29 ET)
  │                              maintain rolling avgRange and avgVolume
  └─ isBreakout() ────────────► fast gate: range > 2× avg AND volume > 2× avg
                                            AND close > pre-market high
                                      │
                                      │ (candidates only)
                                      ▼
                          OllamaBreakoutAnalyser           @Async — runs on virtual thread
                            └─ ChatClient ──────────────► qwen3:8b via Ollama (localhost:11434)
                                                           Sends last 20 bars as compact JSON
                                                           Receives structured JSON response
                                      │
                                      │ confirmed=true AND confidence ≥ 70%
                                      ▼
                              SignalStore                  ConcurrentLinkedQueue in memory
                                      │
                                      ▼
                              SignalController             GET  /api/signals/pending
                                                           DEL  /api/signals/ack
                                      │
                                      ▼
                    Scheduled SKILL.md task (Cowork / Claude agent)
                      ├─ curl GET  /api/signals/pending
                      ├─ mcp__tradingview__alert_create   🚀 TradingView push notification
                      ├─ mcp__tradingview__capture_screenshot
                      └─ curl DEL  /api/signals/ack
```

---

## Signal Flow — Step by Step

1. **Spring Boot listens** for new `AM` (Aggregate Minute Bar) events from Polygon via WebSocket.
2. **`AggregateMinuteBarHandler`** receives each bar and filters out noise — low-price and low-volume symbols are dropped immediately.
3. **`SymbolState`** accumulates a rolling 20-bar window per symbol and tracks the pre-market high/low. `isBreakout()` applies a fast deterministic check (range surge + volume surge + break above pre-market high) as a cheap gate.
4. **`AggregateMinuteBarHandler`** fires breakout candidates asynchronously to Ollama — the main message processing queue is never blocked.
5. **`OllamaBreakoutAnalyser`** uses Spring AI's `ChatClient` to send the last 20 bars as compact JSON to the local `qwen3:8b` model. It asks the model to confirm the 2-candle breakout pattern and return a structured JSON response (confirmed, confidence, entry, stop, target, risk, notes).
6. **If confirmed** with ≥ 70% confidence, the signal is added to `SignalStore` — an in-memory queue.
7. **`SignalController`** exposes the queue over REST so the TradingView scheduled task can retrieve and acknowledge signals.

---

## 2-Candle Breakout Pattern

| | Rule |
|---|---|
| **Candle 1** — Breakout | Green (close > open) |
| | Body ≥ 50% of full candle range (no giant wicks) |
| | Move ≥ 5% open → close |
| | Volume ≥ 1.5× prior 10-bar rolling average |
| **Candle 2** — Confirmation | Opens AT or ABOVE Candle 1 close |
| | Green (close > open) |
| | Closes ABOVE Candle 1 close |
| **Entry** | Candle 2 open |
| **Stop** | 8% below entry |
| **Target** | 50% above entry (typical R/R ≈ 6:1) |

---

## Tech Stack

| Component | Technology |
|---|---|
| Runtime | Java 25, Spring Boot 4 |
| WebSocket client | Spring WebSocket (`MassiveWebSocketClient`) |
| Concurrency | Virtual threads (`spring.threads.virtual.enabled: true`) |
| LLM integration | Spring AI 1.1.4 — `spring-ai-starter-model-ollama` |
| Local LLM | Ollama — `qwen3:8b` (recommended for M4 Mac with 16 GB) |
| JSON | Jackson 3.x (`tools.jackson`) |
| Data source | Polygon.io WebSocket feed — `AM` (Aggregate Minute Bar) events |
| Alert delivery | TradingView MCP — `mcp__tradingview__alert_create` |
| Scheduled orchestration | Cowork scheduled task (SKILL.md) |

---

## Configuration

### `application.yaml`

```yaml
spring:
  threads:
    virtual:
      enabled: true
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        model: qwen3:8b
        options:
          temperature: 0.1   # low = deterministic JSON output
          format: json

filter:
  min-close: 2.0       # ignore symbols below $2
  min-volume: 50000    # ignore bars with fewer than 50k shares traded

ollama:
  breakout:
    min-confidence: 70  # minimum Ollama confidence % to emit a signal
```

### Environment Variables

| Variable | Description |
|---|---|
| `MASSIVE_API_KEY` | Polygon.io WebSocket API key |

---

## REST API

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/signals/pending` | Returns all confirmed signals waiting to be fired as TradingView alerts |
| `DELETE` | `/api/signals/ack` | Clears the signal queue after alerts have been fired |

### Example response — `GET /api/signals/pending`

```json
[
  {
    "id": "3f2a1c4e-...",
    "symbol": "AAPL",
    "entry": 195.40,
    "stop": 179.77,
    "target": 293.10,
    "confidence": 85,
    "risk": "medium",
    "notes": "Strong volume surge on C1, C2 confirms gap continuation above pre-market high",
    "timestamp": 1712345678000
  }
]
```

---

## TradingView Integration (Cowork Scheduled Task)

The Cowork scheduled task (`premarket-breakout-scanner`) runs every 5 minutes between 04:00–09:59 AM ET, Monday–Friday.

At the start of each run (Step 1.5) it polls `GET /api/signals/pending`. For every signal returned it:
1. Switches the TradingView chart to that symbol
2. Fires a `mcp__tradingview__alert_create` — delivered as a push notification to the TradingView mobile app and desktop
3. Takes a chart screenshot
4. Calls `DELETE /api/signals/ack` to clear the queue

Alert message format:
```
🚀 [SPRINGBOOT+OLLAMA] BREAKOUT {symbol} | Entry ${entry} | Stop ${stop} (-8%) | Target ${target} (+50%) | Confidence {confidence}% | Risk: {risk} | {notes}
```

---

## Running Locally

```bash
# Start Ollama with qwen3:8b
ollama serve
ollama pull qwen3:8b

# Run the application
export MASSIVE_API_KEY=your_polygon_api_key
./mvnw spring-boot:run

# Optional: record a fixture for replay tests
./mvnw spring-boot:run -Dspring-boot.run.profiles=record
```

---

## Project Structure

```
src/main/java/za/co/sfh/stocklistener/
├── config/
│   └── AsyncConfig.java              @EnableAsync — virtual thread executor
├── payloads/
│   ├── AggregateMinuteBar.java       Polygon AM event record
│   ├── BreakoutAnalysis.java         Structured Ollama response record
├── processor/
│   ├── AggregateMinuteBarHandler.java  Main bar processing + breakout gate
│   ├── MessageHandler.java             Handler interface
│   ├── MessageProcessor.java           Queue + dispatcher
│   ├── OllamaBreakoutAnalyser.java     Spring AI ChatClient → Ollama
│   ├── ProcessStatusMessage.java
│   └── states/
│       └── SymbolState.java            Per-symbol rolling window + pre-market tracking
├── recorder/
│   └── BarRecorder.java               Records raw WS messages for test fixtures (profile: record)
├── signals/
│   ├── BreakoutSignal.java            Confirmed signal record (queued for TradingView)
│   ├── SignalController.java          REST: GET /api/signals/pending, DELETE /api/signals/ack
│   └── SignalStore.java               Thread-safe in-memory signal queue
└── websocket/
    └── MassiveWebSocketClient.java    Polygon WebSocket connection
```


Starting the docker instance:

./mvnw clean package -Pproduction -DskipTests

docker build -t stocklistener:latest .

# To start in recording mode:
docker compose up recorder -d

# Or to start without recording:
docker compose up app
