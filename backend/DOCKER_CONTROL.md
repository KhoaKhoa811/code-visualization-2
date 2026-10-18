# Docker control adapter (Part 6c)

This records the completed 6c increment. Part 6d now extends the adapter with
[fixed-fixture preparation and execution](FIXTURE_LIFECYCLE.md); the original
idle-container checks remain available. Later-work statements below describe 6c.

This is backend implementation for controlling a fixed idle runner container.
It is not connected to an HTTP endpoint or Spring service yet. No submitted Java
is staged, compiled, or executed. Existing PowerShell probes remain unchanged.

## Review order

1. **6c.1 — `995808d`:** `ContainerProfile` fixes isolation arguments and creates
   a unique owner/name. Its test checks the digest/platform against runner/image.json.
2. **6c.2 — `ecb48b4`:** `BoundedCommandRunner` limits waiting, retained control
   output, and concurrent workers. Local fixture tests cover literal arguments,
   rejected quotes, exact/overflow output, failure, timeout, and busy rejection.
3. **6c.3 — `7928ff5`:** `DockerControlAdapter` and `ContainerInspection` bind the
   immutable ID, check ownership/profile, and verify start/stop/removal. Tests
   cover false acknowledgements, foreign ownership, unsafe settings, uncertain
   create, and stop-before-start fencing. An opt-in test exercises real Docker.
4. **6c.4:** this guide and both memory files record verified results and scope.

All pieces are complete. The detailed boundary is in
[Docker control specification](../specs/docker-control.md).

## Verification

Normal `clean verify` runs the local tests without requiring Docker; the live
test is explicitly skipped unless enabled. From backend/, after the Java/cache
setup in [README](README.md), run the full suite with Docker Desktop ready:

```powershell
$dockerPath = (Get-Command docker.exe -CommandType Application).Source
.\mvnw.cmd -B -ntp '-Dmaven.repo.local=../.tools/m2' '-Dcodeviz.docker.live=true' "-Dcodeviz.docker.executable=$dockerPath" clean verify
```

The pinned image must already be local; the adapter never pulls it. On 2026-10-17,
this command passed **13 tests, zero failures/errors/skips**, including the real
loopback backend startup and a real restricted-container lifecycle. The final
live test verified removal of its exact container ID. No new dependencies were
added and no broad Part 5 probe suite was rerun.

Docker was initially stopped; the first live attempt failed closed. After starting
Desktop, the recorded failed-create name was confirmed absent. The final tests
used new identities. A Windows embedded-quote round-trip failed during development;
the fixed control vocabulary now rejects embedded quotes before process launch.

## Remaining boundaries

Ambiguous control failure retains identity and quarantines mutations. It does not
guarantee a pending daemon operation was cancelled or release a run slot. There
is no automatic cleanup/recovery after quarantine; future reconciliation must
verify ownership and pending operations explicitly. The opt-in test prints its
owned name for this purpose and never deletes an unverified resource.

No supervisor singleton/capacity enforcement, phase lifecycle, user stdout/trace
transport, source admission, API, or restart recovery is implemented here. Part
6d proposes the fixed-fixture compile/runtime lifecycle with shared output and
phase deadlines. Full isolation and V1 remain incomplete.
