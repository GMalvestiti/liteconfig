package com.gmalvestiti.minecraft.liteconfig.api.metadata;

import com.gmalvestiti.minecraft.liteconfig.api.annotations.Length;
import com.gmalvestiti.minecraft.liteconfig.api.annotations.Range;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * The rules a single config value has to satisfy.
 *
 * <p>Every component is optional. Read them to build an editor control that cannot produce an
 * invalid value in the first place:
 *
 * <pre>{@code
 * ConfigConstraints constraints = property.constraints();
 * if (constraints.min().isPresent() && constraints.max().isPresent()) {
 *     addSlider(property, constraints.min().getAsDouble(), constraints.max().getAsDouble());
 * } else {
 *     addTextField(property);
 * }
 * }</pre>
 *
 * <p>The same rules are enforced by the holder, so an editor that ignores them still cannot
 * publish a value the library would reject.
 *
 * @param min inclusive lower bound from {@link Range#min()}, empty when unbounded
 * @param max inclusive upper bound from {@link Range#max()}, empty when unbounded
 * @param pattern compiled expression from {@code @Pattern}, empty when the value is free text
 * @param minLength inclusive lower element count from {@link Length#min()}, empty when unbounded
 * @param maxLength inclusive upper element count from {@link Length#max()}, empty when unbounded
 * @param allowedValues the only accepted values, in declaration order; filled for enum fields and
 *                      string fields annotated with {@code @AllowedValues}, and empty otherwise
 * @param hasRange whether the field has a {@link Range} constraint, including an unbounded one
 * @param hasPattern whether the field has a {@code @Pattern} constraint
 * @param hasLength whether the field has a {@link Length} constraint, including an unbounded one
 */
public record ConfigConstraints(
    OptionalDouble min,
    OptionalDouble max,
    Optional<Pattern> pattern,
    OptionalInt minLength,
    OptionalInt maxLength,
    List<String> allowedValues,
    boolean hasRange,
    boolean hasPattern,
    boolean hasLength
) {

    public ConfigConstraints {
        allowedValues = List.copyOf(allowedValues);
        hasRange = hasRange || min.isPresent() || max.isPresent();
        hasPattern = hasPattern || pattern.isPresent();
        hasLength = hasLength || minLength.isPresent() || maxLength.isPresent();
    }

    /**
     * Reports whether this value accepts anything its type can represent.
     *
     * @return {@code true} when no rule is declared
     */
    public boolean isEmpty() {
        return !hasRange
            && !hasPattern
            && !hasLength
            && allowedValues.isEmpty();
    }
}
