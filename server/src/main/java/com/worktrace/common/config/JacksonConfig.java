package com.worktrace.common.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

/**
 * 统一日期时间序列化契约（design.md §8.3 / T04 验收点 6）：
 * LocalDateTime ↔ yyyy-MM-dd'T'HH:mm:ss.SSS，不带偏移。
 */
@Configuration
public class JacksonConfig {

    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS";

    public static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);

    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer workTraceJacksonCustomizer() {
        return builder -> {
            builder.serializerByType(LocalDateTime.class,
                    new LocalDateTimeSerializer(DATE_TIME_FORMATTER));
            builder.deserializerByType(LocalDateTime.class,
                    new LocalDateTimeDeserializer(DATE_TIME_FORMATTER));
            builder.serializerByType(LocalDate.class,
                    new LocalDateSerializer(DATE_FORMATTER));
            builder.deserializerByType(LocalDate.class,
                    new LocalDateDeserializer(DATE_FORMATTER));

            // Null 必须显式输出（RateVO.value:null 契约）；禁止 NON_NULL 省略字段
            builder.serializationInclusion(JsonInclude.Include.ALWAYS);
            builder.timeZone(TimeZone.getTimeZone("Asia/Shanghai"));

            builder.featuresToDisable(
                    SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                    DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE
            );
        };
    }
}
