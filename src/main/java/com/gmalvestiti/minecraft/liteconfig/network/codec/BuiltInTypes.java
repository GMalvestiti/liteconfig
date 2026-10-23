package com.gmalvestiti.minecraft.liteconfig.network.codec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Immutable values represented by their lossless, locale-independent string form. */
public final class BuiltInTypes {

    private static final Map<Class<?>, Function<String, ?>> TEXT_VALUES = Map.of(
        UUID.class, UUID::fromString,
        BigInteger.class, BigInteger::new,
        BigDecimal.class, BigDecimal::new,
        Duration.class, Duration::parse,
        Instant.class, Instant::parse,
        LocalDate.class, LocalDate::parse,
        LocalTime.class, LocalTime::parse,
        LocalDateTime.class, LocalDateTime::parse
    );

    private BuiltInTypes() {}

    public static boolean isTextValue(Class<?> type) {
        return TEXT_VALUES.containsKey(type);
    }

    public static Object parseText(Class<?> type, String value) {
        Function<String, ?> parser = TEXT_VALUES.get(type);
        if (parser == null) {
            throw new IllegalArgumentException("Not a built-in text value: " + type.getTypeName());
        }
        return parser.apply(value);
    }
}
