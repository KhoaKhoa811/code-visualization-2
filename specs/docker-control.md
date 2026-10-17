# Docker control boundary — Part 6c

The backend adapter controls one owned container using the installed Docker CLI
and explicit `desktop-linux` context. It is not a Spring bean or execution API.
Only a fixed idle command is exposed in this increment; no source staging,
compiler, submitted JVM, or arbitrary command entry point is provided.

## Immutable profile and identity

Reuse the digest/platform in `runner/image.json` and the restricted Part 5 profile:
UID/GID 10001, read-only root, no network, private IPC, no capabilities, no new
privileges, default seccomp, 512 MiB memory/no swap allowance, 128 tasks, disabled
restart/logging/core dumps, and the three bounded tmpfs mounts. Clear the worker
environment using `/usr/bin/env -i`; allow only fixed PATH and HOME. No volumes,
host paths, sockets, caller flags, or image downloads are accepted.

Generate a UUID owner/name before create. Keep that identity even if create fails.
After create, bind the full immutable container ID. Before start, kill, or remove,
inspect that exact ID and verify name and owner label. Validate the restricted
configuration before start. Labels are ownership checks against accidental
cross-run operations, not protection against a privileged host administrator.

## Bounded control calls

Pass arguments directly to ProcessBuilder, never through a host shell. The
runner rejects embedded double quotes: the fixed control vocabulary needs none,
and Windows/JDK argument reconstruction must not silently change them. See the
[OpenJDK Windows process implementation](https://github.com/openjdk/jdk21u/blob/master/src/java.base/windows/classes/java/lang/ProcessImpl.java).
One shared
command runner accepts at most one in-flight call and no queued work. Merge only
Docker control stdout/stderr and retain at most 65,536 raw bytes per call. This
is separate from the later submitted-program output transport/quota.

Each call has a caller-supplied remaining duration, capped at 10 seconds. The
default adapter operation uses one five-second monotonic budget across all its
CLI calls (create uses ten seconds). Process start, output EOF, and process exit
are included in waiting. Timeout, interruption, or overflow fails closed and
requests local CLI termination. A stuck worker remains bounded to the single
daemon thread; no replacement thread or automatic retry is created.

Killing the CLI does not cancel a daemon operation. Any ambiguous adapter failure
quarantines its identity and prohibits further mutations. It neither releases a
run slot nor claims termination/cleanup. Explicit reconciliation of pending daemon
work and restart recovery belong to Part 6e. Closing the command runner does not
remove containers or erase ownership. The adapter is not yet application-wired;
the eventual supervisor must share one runner and enforce run capacity.

## Proof and scope

Start verifies running state after the acknowledgement. Kill requires a subsequent
owned inspection showing stopped state. Removal refuses running containers, uses
the bound ID without force, and verifies absence with a successful exact-ID list.
An inspect/list error is never absence. Late/failed operations remain quarantined;
no same-name replacement is deleted. Normal control acknowledgements alone do not
prove termination or cleanup.

These checks cover the fixed idle container only. Effective kernel isolation is
still supported by the separate Part 5 probes; full admission, phase deadlines,
shared user output, slot accounting, cancellation races, and recovery remain
unimplemented. No trace status or budget in execution-isolation.md changes.
