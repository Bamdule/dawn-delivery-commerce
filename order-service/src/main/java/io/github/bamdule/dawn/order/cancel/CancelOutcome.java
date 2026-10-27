package io.github.bamdule.dawn.order.cancel;

public enum CancelOutcome {
    CANCELLED,
    ALREADY_CANCELLED,
    CUTOFF_PASSED,
    NOT_CANCELLABLE
}
