# ADR 0001: Live console over WebSocket

Status: **Accepted** (decision confirmed by the user on 2026-09-16; written down in
Part 8g on 2026-10-19, when the user approved adding `spring-boot-starter-websocket`).
Summarized in the [requirements](../../requirements/PROJECT_REQUIREMENTS.md) (section
on the end-to-end API); the message contract is in [live-console.md](../live-console.md).

## Context

V1 requires a live console: program output appears while the program runs, and
the user types a line and presses Enter to send it to a running program that
reads `System.in` through `Scanner`. Diagram playback still starts only after the
run ends and never asks for input again. Run creation, cancellation, status and
completed results already use HTTP (Axios and TanStack Query in the frontend).

## Decision

1. Console output, input lines, end of input (EOF) and console notifications use
   one native browser WebSocket per run, served by a Spring WebSocket handler at
   `/api/runs/{id}/console`.
2. Run creation, cancellation, recovery, snapshots and the final output stay on
   HTTP. The WebSocket never carries traces.
3. No message broker, STOMP, or durable job system is introduced. Messages are
   small JSON objects with a fixed vocabulary and bounded size.
4. The first connection to a run is its controller; only it may send input. If the
   controller disconnects while an interactive run executes, the run is cancelled
   (`INPUT_DISCONNECTED`). Other connections only observe; no reconnection or
   restart is attempted.

## Consequences

- The backend depends on `spring-boot-starter-websocket` (version managed by the
  Spring Boot parent). The frontend uses the browser WebSocket API with no new
  package; the Vite dev proxy forwards WebSocket upgrades.
- Input bounds, EOF and disconnect handling follow
  [execution-isolation.md Part 4r](../execution-isolation.md).
- Acknowledgement means the backend accepted a line and will write it in order; it
  does not mean the program consumed it.
