package com.gmalvestiti.minecraft.liteconfig.api;

/**
 * Selects the outcome for anticipated failures in read, write, and update operations.
 *
 * <p>One policy per operation family, declared by the config class:
 *
 * <pre>{@code
 * @Config(
 *     name = "mymod",
 *     readFailurePolicy = FailurePolicy.FALLBACK,
 *     writeFailurePolicy = FailurePolicy.STRICT,
 *     updateFailurePolicy = FailurePolicy.FALLBACK
 * )
 * public final class MyModConfig {}
 * }</pre>
 *
 * <ul>
 *   <li><b>read</b> — load, parse, and load-time validation failures.</li>
 *   <li><b>write</b> — storage failures on the save path; the write path never rolls back memory.</li>
 *   <li><b>update</b> — validation failures on a candidate produced by a caller mutator.</li>
 * </ul>
 *
 * <p>No policy covers defects such as a broken validator, an invalid config model, or a
 * {@code null} mutator; defect {@code ConfigError} values always propagate as
 * {@code LiteConfigException}.
 */
public enum FailurePolicy {
    /**
     * Throws an {@code LiteConfigException} and leaves the caller to handle the failure.
     *
     * <p>On read, the caller handles the failed load. On write, the exception signals that the
     * save could not be completed. On update, the published state is left unchanged.
     */
    STRICT,
    /**
     * Logs the failure and degrades instead of throwing.
     *
     * <p>On read, default values are restored in memory; malformed files are moved aside first,
     * so the next save can write a clean file without rereading the same corrupt content. On
     * write, the current in-memory state stays published. On update, the rejected candidate is
     * discarded and the published state is left unchanged.
     */
    FALLBACK
}
