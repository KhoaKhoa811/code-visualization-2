# Fixed trusted fixture only; arbitrary submitted-program monitoring remains work.
function Invoke-TaskLimitProbe([string]$ContainerName, [string]$Fixture) {
    function Read-TaskDenials {
        $raw = Invoke-Docker "exec $ContainerName /bin/cat /sys/fs/cgroup/pids.events" 1000
        if ($raw -cnotmatch '^max ([0-9]+)$') { throw 'Missing or invalid PID denial counter.' }
        return [long]$Matches[1]
    }
    function Assert-NoMemoryKill {
        $raw = Invoke-Docker "exec $ContainerName /bin/cat /sys/fs/cgroup/memory.events" 1000
        foreach ($key in @('oom', 'oom_kill')) {
            $entries = [regex]::Matches($raw, "(?m)^$key ([0-9]+)\r?$")
            if ($entries.Count -ne 1 -or $entries[0].Groups[1].Value -ne '0') {
                throw 'Memory exhaustion invalidates the task-limit probe.'
            }
        }
    }
    $cap = Invoke-Docker "exec $ContainerName /bin/cat /sys/fs/cgroup/pids.max" 1000
    if ($cap -cne '128' -or (Read-TaskDenials) -ne 0) { throw 'Wrong task cap or preexisting PID denial.' }
    $compile = "printf %s $Fixture | base64 -d > /work/TaskLimit.java; " +
        'javac -J-Xmx256m -J-Djava.io.tmpdir=/tmp -encoding UTF-8 -proc:none --release 21 -d /work /work/TaskLimit.java'
    $null = Invoke-Docker ("exec $ContainerName /usr/bin/env -i PATH=/opt/java/openjdk/bin:/usr/bin:/bin HOME=/tmp /bin/sh -ec " + '"' + $compile + '"') 10000
    if ((Read-TaskDenials) -ne 0) { throw 'Compilation encountered a PID denial.' }
    Assert-NoMemoryKill
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $output = Invoke-Docker "exec $ContainerName /usr/bin/env -i PATH=/opt/java/openjdk/bin:/usr/bin:/bin HOME=/tmp java -Xmx128m -Xss256k -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Xlog:os+thread=off -Djava.io.tmpdir=/tmp -cp /work TaskLimit" 5000
    $elapsed = $timer.ElapsedMilliseconds
    $denials = Read-TaskDenials
    if ($elapsed -ge 5000 -or $output -cne 'tasks-ok' -or $denials -lt 2) {
        throw 'Task denial lacks verified fixture and controller evidence.'
    }
    Assert-NoMemoryKill
    # Fixture released its JVM threads; PID 1 and the fixed child still need stopping.
    $termination = [Diagnostics.Stopwatch]::StartNew()
    $state = (Invoke-Docker "inspect $ContainerName" 1000 | ConvertFrom-Json)[0].State
    if (-not $state.Running -or $state.OOMKilled) { throw 'Expected surviving task-probe environment.' }
    $remaining = 5000 - [int]$termination.ElapsedMilliseconds
    if ($remaining -le 0) { throw 'Task-probe termination deadline expired.' }
    $null = Invoke-Docker "kill --signal KILL $ContainerName" $remaining
    $remaining = 5000 - [int]$termination.ElapsedMilliseconds
    if ($remaining -le 0) { throw 'Task-probe termination deadline expired.' }
    $state = (Invoke-Docker "inspect $ContainerName" $remaining | ConvertFrom-Json)[0].State
    if ($termination.ElapsedMilliseconds -ge 5000 -or $state.Running -or $state.Status -ne 'exited' -or $state.Pid -ne 0) {
        throw 'Whole-container task-probe termination is unconfirmed.'
    }
    Write-Output "PASS: 128-task cap; thread and process creation denied; pids.events max=$denials after $elapsed ms."
    Write-Output 'PASS: compilation had no PID denial; no container OOM; remaining environment stopped with PID zero.'
}
