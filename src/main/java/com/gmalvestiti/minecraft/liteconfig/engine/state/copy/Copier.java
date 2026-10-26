package com.gmalvestiti.minecraft.liteconfig.engine.state.copy;

/** Copies one non-null value of a known type. */
@FunctionalInterface
interface Copier {

    /** Marks values that are immutable and can be shared between copies. */
    Copier SAME = value -> value;

    Object copy(Object value);
}
