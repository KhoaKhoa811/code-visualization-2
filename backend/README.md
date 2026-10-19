# Backend setup (Part 6a)

Minimal Spring Boot application, bound to `127.0.0.1:8080`. Part 6c adds a
[Docker control adapter](DOCKER_CONTROL.md); Part 6d adds a
[fixed-fixture lifecycle](FIXTURE_LIFECYCLE.md) for isolated compilation/execution.
Part 7c adds [traced runs](TRACED_RUNS.md) and Part 7d the [run API](RUN_API.md)
through a single run supervisor. No frontend exists yet.

## Pinned tools

- Java language/release: 21. Verified locally with Oracle JDK 21.0.12.1.
- Spring Boot: 4.1.1; its parent manages dependency and plugin versions.
- Maven: 3.9.16, downloaded by the generated Apache Maven Wrapper 3.3.4.
- Wrapper distribution: ZIP with a pinned SHA-256 checksum in
  `.mvn/wrapper/maven-wrapper.properties`; no global Maven installation required.

[Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html)
support this Java/Maven combination. See the
[Maven releases](https://maven.apache.org/download.cgi) and
[wrapper documentation](https://maven.apache.org/tools/wrapper/).
The standard generated wrapper scripts account for most setup lines.

## Windows PowerShell

From `backend/`, use a Java 21 JDK installed on your machine. This is the exact
local JDK path used for verification; adjust it if yours differs:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.12.1'
$env:MAVEN_USER_HOME = Join-Path (Split-Path $PWD) '.tools\maven-home'
.\mvnw.cmd -B -ntp '-Dmaven.repo.local=../.tools/m2' clean verify
```

Since Part 7c the backend depends on `runner/tracer` and bundles its runtime jar,
so build from the repository root pom (see [traced runs](TRACED_RUNS.md)). The
command above works only after the tracer has been installed or built by the reactor.

The first build needs network access to download Maven and dependencies. These
commands keep caches in the ignored root `.tools/` directory. Quote Maven `-D`
arguments in PowerShell. Build output stays in ignored `backend/target/`.

Verification on 2026-10-17: `clean verify` passed with one test, no failures,
errors, or skipped tests. The test starts a real HTTP server on a random loopback
port and checks that `POST /api/runs` returns 404. This is a startup smoke check,
not an implemented API contract. Maven also packaged the executable JAR. Since
Part 7d that test expects `400 REQUEST_INVALID`; current results are in RUN_API.md.

To start the packaged application manually:

```powershell
& "$env:JAVA_HOME\bin\java.exe" -jar target/backend-0.0.1-SNAPSHOT.jar
```

Stop with Ctrl+C. Only the [run API](RUN_API.md) routes exist; pass
`-Dcodeviz.docker.executable=<path>` or keep Docker on `PATH`.
The packaged launch command and Unix wrapper have not been separately smoke-tested.
Submitted Java runs only in the isolated runner, never in this JVM.

## Next scope

Parts 6b-6e and 7c-7d are implemented. See [next steps](../memory/next-steps.md) for the
roadmap and approval state.
