package za.co.sfh.stocklistener.payloads;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class EpochMsSerializer extends StdSerializer<ZonedDateTime> {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    public EpochMsSerializer() {
        super(ZonedDateTime.class);
    }

    @Override
    public void serialize(ZonedDateTime value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
        gen.writeString(value.format(FORMATTER));
    }
}
