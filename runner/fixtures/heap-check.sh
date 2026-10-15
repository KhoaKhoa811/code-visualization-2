#!/bin/sh
# Fixed fixture checks, not a classifier for submitted programs or error text.
set -eu
check_no_container_oom() {
    awk '
    $1 == "oom" || $1 == "oom_kill" {
        seen[$1]++
        if ($2 != "0") failed = 1
    }
    END {
        if (seen["oom"] != 1 || seen["oom_kill"] != 1 || failed) {
            print "Container OOM evidence is nonzero or incomplete" > "/dev/stderr"
            exit 1
        }
    }
    ' /sys/fs/cgroup/memory.events
}
check_no_container_oom
java -Xmx128m -Djava.io.tmpdir=/tmp -cp /work HeapExhaustion caught > /work/caught.out 2> /work/caught.err
printf 'heap-started\nheap-caught\nheap-continued\n' > /work/expected.out
cmp /work/expected.out /work/caught.out
test ! -s /work/caught.err
check_no_container_oom

if java -Xmx128m -Djava.io.tmpdir=/tmp -cp /work HeapExhaustion uncaught > /work/uncaught.out 2> /work/uncaught.err; then
    status=0
else
    status=$?
fi
test "$status" -eq 1
printf 'heap-started\n' > /work/expected.out
cmp /work/expected.out /work/uncaught.out
grep -Fx 'Exception in thread "main" java.lang.OutOfMemoryError: Java heap space' /work/uncaught.err > /dev/null
grep -F 'at HeapExhaustion.exhaust(HeapExhaustion.java:' /work/uncaught.err > /dev/null
check_no_container_oom
printf 'heap-ok\n'
