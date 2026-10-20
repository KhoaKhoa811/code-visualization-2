# Run API (Part 7d)

A minimal, noninteractive HTTP API for submitted Java source. It runs each
program through the single `RunSupervisor` and the
[traced container lifecycle](TRACED_RUNS.md), and publishes
[run-v1 snapshots](../contracts/run/README.md) that carry a trace-v1 result.
Behavior is specified in
[run-lifecycle.md](../specs/run-lifecycle.md#http-api-part-7d). The server binds
to `127.0.0.1:8080` only. Submitted Java never runs in the backend JVM.

## Review pieces

| Part | Commit | Change | Verification |
| --- | --- | --- | --- |
| 7d.1 | `c0e2988` | Traced lifecycle: run-v1 failure codes, reserved preparation deadline, explicit recovery | 12 lifecycle unit tests |
| 7d.2 | `0dc80a1` | `RunSupervisor`: 1 slot, 2 FIFO queued (30 s), snapshots with revisions, bounded retention | 8 supervisor tests |
| 7d.3 | `8cd5e4a` | `RunController`, strict request parser, `RunnerAccess`, server limits | 6 controller tests, 2 loopback HTTP tests |
| 7d.4 | `593c6ce` | `contracts/run/check.cjs` and live API tests | Full live verify, 118 tests |
| 7d.5 | docs commit | This guide, spec section and memory | Paths checked |

## Endpoints

| Method and path | Success | Errors |
| --- | --- | --- |
| `POST /api/runs` | `202`, `Location: /api/runs/{id}`, snapshot | `400 REQUEST_INVALID`, `400 SOURCE_INVALID`, `400 SOURCE_HASH_MISMATCH`, `413 REQUEST_TOO_LARGE`, `413 SOURCE_LIMIT`, `415 UNSUPPORTED_MEDIA_TYPE`, `415 UNSUPPORTED_ENCODING`, `503 RUNNER_BUSY`, `503 RUNNER_UNAVAILABLE` |
| `GET /api/runs/{id}` | `200` snapshot | `404 RUN_NOT_FOUND`, `410 QUEUE_EXPIRED` |
| `POST /api/runs/{id}/cancel` | `202` snapshot | `409 CANCEL_NOT_ACCEPTED`, `404`, `410` |
| `POST /api/runs/{id}/recover` | `200` snapshot (finished if recovery succeeded) | `409 RECOVERY_NOT_APPLICABLE`, `404`, `410` |
| `GET /api/runs/{id}/output` | `200 {"runId","stdout","stderr"}` | `409 OUTPUT_NOT_READY`, `404`, `410` |

The request body is [run request v1](../contracts/run/v1/request.schema.json):

```json
{"schemaVersion":"1.1.0","source":{"file":"Main.java","text":"...","sha256":"<hex of UTF-8 text>"},"inputMode":"live"}
```

`inputMode` is `none` (stdin closed at once) or `live` (Part 8g). A live run whose
source refers to `System.in` is interactive: its console is the WebSocket below.

## Live console (Part 8g)

`ws://127.0.0.1:8080/api/runs/{id}/console` streams state, output and the end of a
run, and accepts ordered input lines and EOF from the first connection (the
controller). Messages and bounds are in [live-console.md](../specs/live-console.md)
and [ADR 0001](../specs/decisions/0001-live-console-websocket.md). Closing the
controller while an interactive run executes cancels it with `INPUT_DISCONNECTED`.
Only `http://localhost:*` and `http://127.0.0.1:*` origins may connect.

Errors are `{"error":"CODE"}` and never echo request content. Poll the snapshot
until `phase` is `finished`; `trace` is then the trace-v1 envelope (or null after
an infrastructure failure). Clients should ignore snapshots with an older
`revision`. Output is available once whole-container termination is confirmed;
console bytes are decoded as UTF-8, with malformed sequences replaced by U+FFFD.

## Behavior

- **Admission order:** content type and encoding, body at most 2 MiB, strict
  JSON (UTF-8, no duplicate keys, nesting at most 32, at most 65,536 tokens),
  exact request shape, then source size (256 KiB UTF-8), Unicode and SHA-256.
  Rejected requests never queue or touch Docker.
- **Runner access:** the supervisor opens on the first valid submission. It first
  lists containers with the application owner label; any existing one blocks
  admission (`RUNNER_UNAVAILABLE`) until an operator reconciles it. A failed open
  is retried on the next submission. Set `codeviz.docker.executable` to the Docker
  CLI path; when blank, `PATH` is searched.
- **Capacity:** one occupied slot and at most two queued runs; a further submission
  gets `RUNNER_BUSY`. Queued runs expire 30 s after acceptance (`QUEUE_EXPIRED`) and
  never launch. The preparation deadline (10 s) starts at slot reservation.
- **Cancellation:** a queued run finishes at once without a slot or container, with
  a `cancelled` trace and unavailable capture. An active run latches cancellation
  on its phase gate (see [6e](CANCELLATION_RECOVERY.md)); a latched stop cause or a
  finished run returns `CANCEL_NOT_ACCEPTED`.
- **Held slot:** if stop or removal is unconfirmed, the run stays `stopping` or
  `cleaning` with the slot held and the queue blocked. `recover` force-removes the
  exact container ID; failures stay listed after success, and the original stop
  cause is kept.
- **Retention:** at most four finished or expired runs are kept, each for at most
  10 minutes; older ones return `RUN_NOT_FOUND`.

## Reproduce verification

From the repository root, with Docker Desktop running, the pinned image present
and Node 22 with `contracts/node_modules`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.12.1'
$env:MAVEN_USER_HOME = "$PWD\.tools\maven-home"
$dockerPath = (Get-Command docker.exe -CommandType Application).Source
.\.tools\apache-maven-3.9.16\bin\mvn.cmd -B -ntp -o '-Dmaven.repo.local=.tools/m2' '-Dcodeviz.docker.live=true' "-Dcodeviz.docker.executable=$dockerPath" clean verify
```

On 2026-10-18 this passed **118 tests (tracer 33, backend 85), zero failures or
skips**. The live API tests ran bubble sort over HTTP (completed trace, output
`1 2 3`, no container left) and covered busy, queued cancellation, active
cancellation and FIFO promotion. Every snapshot with a trace passed
`contracts/run/check.cjs`. `npm test` in `contracts/` passed 52 tests.

To try it by hand, start the packaged jar with
`-Dcodeviz.docker.executable=<path>` and send the request above with any HTTP client.

## Limits and gaps

- Live input covers one controller per run; the input-wait deadline of Part 4r is
  not enforced (the runner has no verified evidence that the program is waiting).
- No browser origin policy yet; Part 7e decides how the frontend reaches the API.
- Partly enforced Part 4p transport limits: headers 16 KiB and a 5 s connection
  timeout are set; the two-receiver cap, total body receive time and the shared
  decoded-memory budget are not enforced.
- Snapshots carry the full trace on every poll after it exists (up to 8 MiB).
- The in-memory supervisor is not a crash journal; see the 6e startup inventory.
- Not public deployment readiness; the 6g isolation review is still deferred.
