package za.co.sfh.stocklistener.ibkr;

import com.ib.client.*;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Spring-managed IBKR TWS API client for real-time tape reading.
 *
 * <p>This class is a plain Spring {@code @Component} — it does <em>not</em> extend
 * {@link DefaultEWrapper} directly. Instead, the IBKR wrapper is held in a private inner
 * class ({@link EWrapperDelegate}) so that Spring's CGLIB proxying never has to subclass
 * a third-party type from the IBKR jar (which fails under Java 25 module restrictions).
 *
 * <p>Enable by setting {@code ibkr.enabled=true} in {@code application.yaml}. TWS (or IB
 * Gateway) must be running and the API enabled on the configured port.
 */
@Slf4j
@Component
public class IbkrTapeClient {

    // ── Configuration ─────────────────────────────────────────────────────────

    @Value("${ibkr.enabled:false}")
    private boolean enabled;

    @Value("${ibkr.host:127.0.0.1}")
    private String host;

    @Value("${ibkr.port:7496}")
    private int port;

    @Value("${ibkr.client-id:2}")
    private int clientId;

    @Value("${ibkr.symbols:}")
    private String symbolsConfig;

    // ── State ─────────────────────────────────────────────────────────────────

    private final IbkrTickStore     tickStore;
    private final AtomicBoolean     connected   = new AtomicBoolean(false);
    private final AtomicInteger     nextReqId   = new AtomicInteger(1);
    private final EJavaSignal       signal      = new EJavaSignal();

    /** Two-way lookup — resolves symbol ↔ reqId in O(1). */
    private final ConcurrentHashMap<Integer, String> reqIdToSymbol = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> symbolToReqId = new ConcurrentHashMap<>();

    private EClientSocket client;

    public IbkrTapeClient(IbkrTickStore tickStore) {
        this.tickStore = tickStore;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) {
            log.info("IBKR tape client disabled (ibkr.enabled=false) — set true to activate");
            return;
        }
        connect();
    }

    @PreDestroy
    public void onShutdown() {
        disconnect();
    }

    public void connect() {
        if (connected.get()) {
            log.info("IBKR already connected");
            return;
        }
        Thread.ofVirtual().name("ibkr-reader").start(() -> {
            try {
                client = new EClientSocket(new EWrapperDelegate(), signal);
                client.eConnect(host, port, clientId);

                EReader reader = new EReader(client, signal);
                reader.start();

                log.info("IBKR TWS connection initiated — {}:{} clientId={}", host, port, clientId);

                // Blocking message loop — runs until TWS disconnects or eDisconnect() is called
                while (client.isConnected()) {
                    signal.waitForSignal();
                    try {
                        reader.processMsgs();
                    } catch (Exception e) {
                        log.error("Error processing IBKR message", e);
                    }
                }
            } catch (Exception e) {
                log.error("IBKR connection failed ({}:{})", host, port, e);
            } finally {
                connected.set(false);
                log.warn("IBKR message loop ended");
            }
        });
    }

    public void disconnect() {
        if (client != null && client.isConnected()) {
            client.eDisconnect();
            log.info("IBKR TWS disconnected");
        }
        connected.set(false);
    }

    // ── Subscription management ───────────────────────────────────────────────

    /**
     * Subscribe to time-and-sales (Last prints) for a symbol.
     * Safe to call multiple times — already-subscribed symbols are skipped.
     */
    public void subscribe(String symbol) {
        String sym = symbol.toUpperCase();
        if (!connected.get()) {
            log.warn("Cannot subscribe to {} — not connected to IBKR TWS", sym);
            return;
        }
        if (symbolToReqId.containsKey(sym)) {
            log.info("Already subscribed to {}", sym);
            return;
        }

        int reqId = nextReqId.getAndIncrement();
        reqIdToSymbol.put(reqId, sym);
        symbolToReqId.put(sym, reqId);

        Contract contract = new Contract();
        contract.symbol(sym);
        contract.secType("STK");
        contract.currency("USD");
        contract.exchange("SMART");

        client.reqTickByTickData(reqId, contract, "Last", 0, false);
        log.info("Subscribed to IBKR tape for {} (reqId={})", sym, reqId);
    }

    /** Cancel the tick-by-tick stream for a symbol and clear its tape buffer. */
    public void unsubscribe(String symbol) {
        String sym = symbol.toUpperCase();
        Integer reqId = symbolToReqId.remove(sym);
        if (reqId == null) {
            log.warn("Cannot unsubscribe — not currently subscribed to {}", sym);
            return;
        }
        reqIdToSymbol.remove(reqId);
        if (client != null && client.isConnected()) {
            client.cancelTickByTickData(reqId);
        }
        tickStore.clear(sym);
        log.info("Unsubscribed from IBKR tape for {} (reqId={})", sym, reqId);
    }

    public boolean isConnected() {
        return connected.get();
    }

    public Set<String> subscribedSymbols() {
        return Collections.unmodifiableSet(symbolToReqId.keySet());
    }

    // ── EWrapper delegate ─────────────────────────────────────────────────────

    /**
     * Private inner class that extends {@link DefaultEWrapper}.
     *
     * <p>Kept separate from the Spring bean so CGLIB never has to subclass a
     * third-party type. All state is accessed via the enclosing {@link IbkrTapeClient}.
     */
    private class EWrapperDelegate extends DefaultEWrapper {

        // ── Connection callbacks ──────────────────────────────────────────────

        @Override
        public void connectAck() {
            connected.set(true);
            log.info("IBKR TWS connection acknowledged");
        }

        @Override
        public void connectionClosed() {
            connected.set(false);
            log.warn("IBKR TWS connection closed");
        }

        /** Called by TWS immediately after connect — signals we are ready to subscribe. */
        @Override
        public void nextValidId(int orderId) {
            nextReqId.set(orderId);
            log.info("IBKR ready — starting reqId={}", orderId);

            // Auto-subscribe to symbols listed in config (comma-separated)
            if (symbolsConfig != null && !symbolsConfig.isBlank()) {
                Arrays.stream(symbolsConfig.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .forEach(IbkrTapeClient.this::subscribe);
            }
        }

        // ── Tape data callbacks ───────────────────────────────────────────────

        /** Core tape callback — fires for every individual trade print. */
        @Override
        public void tickByTickAllLast(int reqId, int tickType, long time, double price,
                                       Decimal size, TickAttribLast tickAttribLast,
                                       String exchange, String specialConditions) {
            String symbol = reqIdToSymbol.get(reqId);
            if (symbol == null) {
                log.debug("Tick for unknown reqId={}", reqId);
                return;
            }
            var event = new IbkrTickEvent(
                    symbol,
                    price,
                    size.value().doubleValue(),
                    time,
                    exchange,
                    "LAST",
                    specialConditions
            );
            tickStore.add(symbol, event);
            log.debug("[{}] LAST  price={}  size={}  exch={}  cond='{}'",
                    symbol, price, size, exchange, specialConditions);
        }

        // ── Error callbacks ───────────────────────────────────────────────────

        /**
         * TWS error / informational callback.
         * Codes 2104, 2106, 2158 are normal farm-connection status messages.
         * Code 10197 = no market data subscription on this symbol.
         */
        @Override
        public void error(int id, long errorCode, int errorCodeSub, String errorMsg,
                          String advancedOrderRejectJson) {
            // errorCodeSub carries the actual semantic code (2104, 2106, etc.)
            // errorCode may be a timestamp-prefixed composite value in API 10.30
            if (errorCodeSub == 2104 || errorCodeSub == 2106 || errorCodeSub == 2158) {
                log.info("IBKR info  [{}] {}", errorCodeSub, errorMsg);
            } else if (errorCodeSub == 10197) {
                log.warn("IBKR no market data [id={}]: {} — check your IBKR data subscriptions", id, errorMsg);
            } else {
                log.error("IBKR error  [id={} code={}.{}]: {}{}",
                        id, errorCode, errorCodeSub, errorMsg,
                        advancedOrderRejectJson != null && !advancedOrderRejectJson.isEmpty()
                                ? " | " + advancedOrderRejectJson : "");
            }
        }

        @Override
        public void error(Exception e) {
            log.error("IBKR client exception", e);
        }

        @Override
        public void error(String str) {
            log.error("IBKR message: {}", str);
        }

        @Override
        public void managedAccounts(String accountsList) {
            log.info("IBKR managed accounts: {}", accountsList);
        }
    }
}
