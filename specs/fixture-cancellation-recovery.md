# Fixture cancellation, slots, and recovery — Part 6e

Extend the trusted fixed-fixture boundary, without HTTP/source admission or traces.
One supervisor owns one occupied slot and at most two FIFO queued requests. Queue
expiry is 30 seconds from acceptance; expired/cancelled queued work never launches.
Scheduling outcomes are not fabricated Java results. Keep completed results on
caller-held handles, not an unbounded supervisor history.

Cancellation targets the exact handle. Serialize it with the active phase gate;
an observed timeout/overflow/failure or final drained exit wins over a later
request. Duplicate requests do not reset timers or repeat cleanup. A successful
compile is not final completion: cancellation between phases still prevents
runtime. Stop accepting bytes at the same gate that accepts cancellation.
Do not interrupt the run thread. An interrupted control call has an unknown daemon
outcome, quarantines the environment and would hold the only slot, permanently if
it hits container creation. Bounded preparation (at most 10 s) finishes, then the
cancellation check prevents compilation; phases observe cancellation through
their gate within one polling interval. Decision 2026-10-18 (Claude), replacing
an earlier draft that interrupted preparation.

The sole slot includes preparation, execution, stopping, and cleanup. Failed
termination/removal holds it and blocks queued promotion. Release once only after
both proofs, then promote the oldest eligible request. Shutdown rejects admission,
cancels queued work and requests cancellation of active work; it never erases an
unresolved occupied slot. Client polling must not reset deadlines.

Explicit recovery is separate from automatic execution. Retain the original
outcome, output and infrastructure failures. Recover only a known full container
ID after local control workers have settled. Inspect its original name/owner,
force-remove that exact ID, and verify absence. A successful exact-ID inventory
showing it already absent is sufficient only when its completed creation is known:
late exec/start/kill/remove operations cannot recreate an immutable removed ID.
Unknown create outcomes with no bound ID stay blocked; absence by name alone
cannot prove that a delayed create will never appear. Do not create repeated
replacement recovery workers while an earlier one is unsettled.

The in-memory supervisor is not a durable crash journal. On startup it inventories
containers with the application owner label; any existing ownership blocks new
admission for operator reconciliation, never automatic deletion. Crash-time late
daemon creates cannot be completely fenced by a CLI inventory: this remains an
explicit production admission prerequisite, not a claim of complete restart
recovery. No untrusted source is admitted in this increment. Global singleton
wiring and HTTP integration remain Part 6f; do not instantiate competing supervisors.

Verification must cover cancellation before launch/during compile/runtime,
duplicate requests, completion/limit precedence, FIFO/capacity/expiry, cleanup
failure retaining the slot, recovery failure and success, wrong ownership, and
live cancellation/removal. Report unverified crash/daemon timing explicitly.
