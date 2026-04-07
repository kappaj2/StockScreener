package za.co.sfh.stocklistener.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.cfg.EnumFeature;

@Configuration
public class JacksonConfig {

    @Bean
    JsonMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> {
            builder.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            builder.enable(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);
            builder.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
            builder.enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);
            builder.disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS);
            builder.enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN);
            builder.enable(JsonWriteFeature.ESCAPE_NON_ASCII);
        };
    }
}