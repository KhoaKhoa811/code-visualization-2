package com.codevisualization.backend.runner;

/**
 * Serializes a user's request with phase registration and final completion.
 * It never interrupts the run thread: an interrupted Docker control call has an
 * unknown daemon outcome and would quarantine the environment. Phases observe the
 * request through their gate; bounded preparation finishes before the next check.
 */
final class RunCancellation {
    private boolean requested, finished, finalPhase;
    private PhaseGate active;

    synchronized boolean requested() { return requested; }

    synchronized void register(PhaseGate gate, boolean terminalPhase) {
        active = gate; finalPhase = terminalPhase;
        if (requested) gate.alreadyCancelled();
    }

    synchronized boolean request() {
        if (requested || finished) return false;
        if (active != null) {
            PhaseGate.Cause cause = active.cause();
            if (cause != null && (cause != PhaseGate.Cause.EXIT || finalPhase)) return false;
            if (cause == null && active.finish(PhaseGate.Cause.CANCELLED) != PhaseGate.Cause.CANCELLED) return false;
        }
        requested = true;
        return true;
    }

    synchronized void finish() { finished = true; }

    synchronized PhaseGate.Cause completed(PhaseGate gate, int exitCode) {
        PhaseGate.Cause cause = gate.finish(PhaseGate.Cause.EXIT);
        if (cause == PhaseGate.Cause.EXIT && (finalPhase || exitCode != 0)) finished = true;
        return cause;
    }
}
