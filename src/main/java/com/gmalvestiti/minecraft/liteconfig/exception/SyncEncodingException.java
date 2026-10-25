package com.gmalvestiti.minecraft.liteconfig.exception;

/**
 * Signals an expected sync encoding limit violation rather than a codec defect.
 * Sync validation converts it into a scoped {@link LiteConfigException}.
 */
public final class SyncEncodingException extends IllegalArgumentException {

    private final String rule;

    /**
     * @param rule the validation rule identifying the exceeded limit
     * @param message the limit violation details
     */
    public SyncEncodingException(String rule, String message) {
        super(message);
        this.rule = rule;
    }

    /**
     * @return the validation rule identifying the exceeded limit
     */
    public String rule() {
        return rule;
    }
}
