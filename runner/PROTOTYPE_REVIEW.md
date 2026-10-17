# Part 5u: fixed runner prototype review

Review date: 2026-10-16 (Asia/Saigon). Scope: Parts 5a–5t, plus a repeatable gate.
This closes the planned Part 5 fixed-probe sequence only. Milestone 0 remains
incomplete; it does not establish Milestone 1 tracing or V1 completion.

## Repeatable gate

```powershell
.\runner\test-prototype.ps1
$LASTEXITCODE
```

The gate parses runner PowerShell scripts, runs collector tests, fifteen smoke
modes (including default), nine output cases, and seven stop-failure/order cases.
It stops on the first failure and preserves the failing command's output. Each
subordinate probe owns and verifies its cleanup; stop-failure cases additionally
perform independent recovery without hiding the original failed result. Historical
temporary mutation checks remain documented in README, but are not all rerun here.

Environment: Windows PowerShell 5.1.26100.9711, Docker Desktop Linux/WSL2,
linux/amd64, and the [unchanged image pin](image.json):
`eclipse-temurin@sha256:b468c3fc688b14450571494f588bd939378e7fd542ed5a73f8efc13f17872a87`.
Observed Java/javac version: 21.0.12.1. No dependency or image upgrades in this part.

Result: **passed** on 2026-10-16 against the Part 5t implementation plus gate
commit 650e384. All fifteen smoke modes, nine output cases, seven stop cases,
collector tests, and PowerShell parsing passed. Cleanup/recovery absence was
verified by each applicable check. Runtime and child timeouts latched at 5,010 ms;
cancellation at 330 ms; compilation timeout at 10,009 ms. Memory evidence arrived
at 500 ms (max=622, oom=29, oom_kill=1); the task probe observed five PID denials
at 544 ms. Timings vary and are not hard real-time guarantees. No contract-schema,
browser, application, or general submitted-program tests were run in this part.

## Evidence against the isolation gates

These statuses apply to the full [Part 4u gates](../specs/execution-isolation.md),
not just the narrower fixtures. Passing a fixture does not mark its full gate passed.

| Gate | Status | Verified evidence | Required remaining work |
| --- | --- | --- | --- |
| 1. Admission/capacity | Not implemented | Fresh fixed containers; no application admission service. | Source/request limits, queue/slot accounting, preparation deadlines, reservations, restart reconciliation. |
| 2. Worker isolation | Partial | 5a–5d toolchain; 5h–5k filesystem/network/privileges/mounts; 5l–5p storage, heap, cgroup memory/PID denial; 5s private run files. | Integrate enforcement across workers; monitor task limits without relying on fixture cooperation; audit message queues, kernel interfaces, image contents and Java-worker privileges. |
| 3. Deadlines/input | Partial | 5e–5g runtime/descendant timeout and fixed cancellation; 5q stalled javac and runtime gate. | Shared compiler budgets across invocations; queue/preparation/analysis timers; interactive input, EOF/wait detection and 120-second policy. |
| 4. Output/transport | Partial | 5r shared 1 MiB raw-byte quota across fixed compile/runtime workers, stream identity, EOF draining, overflow stop. | Use bounded collection throughout the real lifecycle; trace channel, metadata/capture bounds, global memory accounting, slow clients and transport-error attribution. |
| 5. Capture/replay | Not verified by Part 5 | Trace v1 schema/examples exist; fixed isolation probes emit no real execution trace. | Semantic capture, arrays/sorting, scopes/frames/recursion, safe prefixes, replay and browser evidence; version contracts for collections/custom objects. |
| 6. Stop/cleanup | Partial | 5e–5g descendants/timeout/cancel; 5s independent ownership; 5t seven controlled failures/orderings and recovery. | Exact-boundary, launch/input/output/duplicate-request races; hung daemon operations; exactly-once slot release, retained traces and restart recovery. |

## Limits that must remain visible

- `/dev/mqueue` permits queue entry creation. Allocation/resource limits and other
  writable kernel interfaces remain unverified. Do not silently treat the mount audit
  as proof that arbitrary submitted programs are ready to run.
- Network probes use representative addresses and loopback checks, not actual
  Windows listener/live-peer, DNS/UDP, or exhaustive adversarial tests.
- General output collection is bounded only in `-OutputQuota`; earlier trusted
  probes still use their original collectors. Cgroup evidence and fixed synthetic
  tokens are not interchangeable with arbitrary program output.
- Tests do not implement application APIs, source admission, run-slot ownership,
  interactive execution, retained-result delivery, or trace publication. No
  frontend/backend implementation directory exists at this review point.
- Proposed choices remain proposed until verified. This review does not approve
  a tracing engine, alter budgets, remove V1 features, or authorize public deployment.

## Proposed Part 6 roadmap — not approved

Recommended next major part: Java/Spring Boot run-lifecycle foundation, preserving
the runner boundary and keeping fixed fixtures until the admission gate is ready.
Each smaller part requires its own checklist and approval; this roadmap can change.

| Part | Proposed deliverable |
| --- | --- |
| 6a | Pin/install-test Java 21, Spring Boot and Maven build setup; minimal backend skeleton without a source-execution endpoint. |
| 6b | Versioned run request/result and lifecycle contracts, with explicit stop/cleanup infrastructure failures. |
| 6c | Bounded Java Docker-control adapter, immutable profile and owned-resource identity. |
| 6d | Shared compile/runtime output quota and phase deadlines in one fixed-fixture lifecycle. |
| 6e | Cancellation, run-slot ownership and cleanup/recovery state transitions. |
| 6f | Fixed-fixture API integration and independent failure/order tests; no arbitrary source admission yet. |
| 6g | Review admission prerequisites and remaining isolation gaps; propose the smallest next tracing increment. |

Milestone 1 still requires real scalar/array/sorting traces with semantic equivalence.
Broader V1 structures, input, grid views, mappings and evidence-based rankings remain
required. Part 6 planning does not authorize these implementations or finish V1.
