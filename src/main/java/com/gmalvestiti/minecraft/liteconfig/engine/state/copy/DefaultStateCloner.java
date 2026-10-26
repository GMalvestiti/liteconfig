package com.gmalvestiti.minecraft.liteconfig.engine.state.copy;

import com.gmalvestiti.minecraft.liteconfig.api.spi.StateCloner;

/**
 * Default {@link StateCloner}. Copies a config field by field using a plan cached per class, which
 * gives the same result as a persisted JSON round-trip without the cost of one:
 *
 * <ul>
 *   <li>immutable values (boxed primitives, strings, enums, built-in text values) are shared;</li>
 *   <li>arrays, {@code java.util} collections and maps, records and nested config objects are
 *       rebuilt;</li>
 *   <li>{@code @Ignore} fields keep their value by reference;</li>
 *   <li>types with a registered codec, and anything else it cannot copy directly, go through JSON
 *       for that value only.</li>
 * </ul>
 */
public final class DefaultStateCloner implements StateCloner<Object> {

    @Override
    public Object copy(Object source) {
        return source == null ? null : ClassCopier.of(source.getClass()).copy(source);
    }
}
