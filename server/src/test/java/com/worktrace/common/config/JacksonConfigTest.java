package com.worktrace.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonConfigTest {

    private ObjectMapper objectMapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().workTraceJacksonCustomizer().customize(builder);
        return builder.build();
    }

    @Test
    void localDateTimeRoundTripUsesMillisecondsWithoutOffset() throws Exception {
        ObjectMapper mapper = objectMapper();
        LocalDateTime value = LocalDateTime.of(2026, 11, 2, 9, 12, 0, 0);
        TimeHolder holder = new TimeHolder(value);

        String json = mapper.writeValueAsString(holder);
        assertThat(json).contains("\"2026-11-02T09:12:00.000\"");
        assertThat(json).doesNotContain("+08:00");

        TimeHolder parsed = mapper.readValue(json, TimeHolder.class);
        assertThat(parsed.time()).isEqualTo(value);
    }

    @Test
    void nullValueIsSerializedAsExplicitNull() throws Exception {
        ObjectMapper mapper = objectMapper();
        assertThat(mapper.writeValueAsString(new NullHolder(null))).isEqualTo("{\"value\":null}");
    }

    record TimeHolder(LocalDateTime time) {
    }

    record NullHolder(Object value) {
    }
}
