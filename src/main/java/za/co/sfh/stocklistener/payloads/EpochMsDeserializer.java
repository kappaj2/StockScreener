package za.co.sfh.stocklistener.payloads;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class EpochMsDeserializer extends StdDeserializer<ZonedDateTime> {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    public EpochMsDeserializer() {
        super(ZonedDateTime.class);
    }

    @Override
    public ZonedDateTime deserialize(JsonParser p, DeserializationContext ctx) throws JacksonException {
        return Instant.ofEpochMilli(p.getLongValue()).atZone(ET);
    }
}
