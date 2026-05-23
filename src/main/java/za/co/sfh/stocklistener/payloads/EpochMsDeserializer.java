package za.co.sfh.stocklistener.payloads;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class EpochMsDeserializer extends StdDeserializer<ZonedDateTime> {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    public EpochMsDeserializer() {
        super(ZonedDateTime.class);
    }

    @Override
    public ZonedDateTime deserialize(JsonParser p, DeserializationContext ctx) throws JacksonException {
        // Accept both epoch-ms number (Polygon WebSocket) and human-readable string (serialized REST form)
        if (p.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            return Instant.ofEpochMilli(p.getLongValue()).atZone(ET);
        }
        return ZonedDateTime.parse(p.getString(), FORMATTER);
    }
}
