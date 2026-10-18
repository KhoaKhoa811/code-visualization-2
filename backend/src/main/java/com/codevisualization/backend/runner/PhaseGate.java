package com.codevisualization.backend.runner;

import java.time.Duration;
import java.util.function.LongSupplier;

/** Serializes observed deadline, bytes, and fully drained completion. */
final class PhaseGate {
    enum Cause { EXIT, TIMEOUT, OUTPUT_LIMIT, TRANSPORT_FAILURE }
    private final RunOutput output;
    private final LongSupplier clock;
    private final long deadline;
    private Cause cause;

    PhaseGate(RunOutput output, Duration budget) { this(output, budget, System::nanoTime); }

    PhaseGate(RunOutput output, Duration budget, LongSupplier clock) {
        this.output = output;
        this.clock = clock;
        deadline = clock.getAsLong() + budget.toNanos();
    }

    synchronized Cause cause() {
        if (cause == null && clock.getAsLong() - deadline >= 0) cause = Cause.TIMEOUT;
        return cause;
    }

    synchronized void accept(boolean error, byte[] bytes, int length) {
        if (cause() == null && output.append(error, bytes, length)) cause = Cause.OUTPUT_LIMIT;
    }

    synchronized Cause finish(Cause observed) {
        if (cause() == null) cause = observed;
        return cause;
    }
}
