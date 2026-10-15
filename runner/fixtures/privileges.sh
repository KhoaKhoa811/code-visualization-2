#!/bin/sh
# Fixed trusted probe of PID 1 and this probe's inherited process restrictions.
set -eu
for status in /proc/1/status /proc/self/status; do
    awk '
    function reject(field) {
        print "Unexpected privilege field: " field > "/dev/stderr"
        failed = 1
    }
    BEGIN {
        split("Uid: Gid: Groups: CapInh: CapPrm: CapEff: CapBnd: CapAmb: NoNewPrivs: Seccomp: Seccomp_filters:", keys)
        for (i in keys) required[keys[i]] = 1
    }
    $1 in required {
        seen[$1]++
        if ($1 == "Uid:" || $1 == "Gid:") {
            if (NF != 5) reject($1)
            for (i = 2; i <= NF; i++) if ($i != "10001") reject($1)
        } else if ($1 == "Groups:") {
            for (i = 2; i <= NF; i++) if ($i != "10001") reject($1)
        } else if ($1 ~ /^Cap/) {
            if (NF != 2 || $2 !~ /^0+$/) reject($1)
        } else if ($1 == "NoNewPrivs:") {
            if (NF != 2 || $2 != "1") reject($1)
        } else if ($1 == "Seccomp:") {
            if (NF != 2 || $2 != "2") reject($1)
        } else if (NF != 2 || $2 !~ /^[0-9]+$/ || $2 + 0 < 1) reject($1)
    }
    END {
        for (key in required) if (seen[key] != 1) reject(key)
        exit failed ? 1 : 0
    }' "$status"
done
printf 'privileges-ok\n'
