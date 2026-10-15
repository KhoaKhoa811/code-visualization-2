#!/bin/sh
# Audit the pinned Linux profile, not arbitrary host mounts or submitted paths.
set -eu
test "$(id -u)" = 10001
test "$(id -g)" = 10001
awk '
function require_option(option) {
    if (index("," $4 ",", "," option ",") == 0) failed = 1
}
{
    seen[$2]++
    if (seen[$2] != 1) failed = 1
    if ($2 == "/") { if ($3 != "overlay") failed = 1; require_option("ro") }
    else if ($2 == "/work" || $2 == "/tmp" || $2 == "/dev/shm") {
        if ($3 != "tmpfs") failed = 1
        require_option("rw"); require_option("nosuid"); require_option("nodev"); require_option("noexec")
    } else if ($2 == "/dev") { if ($3 != "tmpfs") failed = 1; require_option("nosuid") }
    else if ($2 == "/dev/pts") { if ($3 != "devpts") failed = 1; require_option("nosuid"); require_option("noexec") }
    else if ($2 == "/dev/mqueue") { if ($3 != "mqueue") failed = 1; require_option("nosuid"); require_option("nodev"); require_option("noexec") }
    else if ($2 == "/proc") { if ($3 != "proc") failed = 1; require_option("nosuid"); require_option("nodev"); require_option("noexec") }
    else if ($2 ~ /^\/proc\/(bus|fs|irq|sys|sysrq-trigger)$/) { if ($3 != "proc") failed = 1; require_option("ro") }
    else if ($2 == "/sys") { if ($3 != "sysfs") failed = 1; require_option("ro") }
    else if ($2 == "/sys/fs/cgroup") { if ($3 != "cgroup2") failed = 1; require_option("ro") }
    else if ($2 ~ /^\/etc\/(hostname|hosts|resolv\.conf)$/) { require_option("ro") }
    else if ($2 ~ /^\/proc\/(acpi|scsi)$/ || $2 == "/sys/firmware") { if ($3 != "tmpfs") failed = 1; require_option("ro") }
    else if ($2 ~ /^\/proc\/(interrupts|kcore|keys|latency_stats|timer_list)$/) { if ($3 != "tmpfs") failed = 1 }
    else failed = 1
    if (failed) { print "Unexpected mount: " $2 > "/dev/stderr"; exit 1 }
}
END {
    split("/ /work /tmp /dev/shm /dev /dev/pts /dev/mqueue /proc /sys /sys/fs/cgroup /etc/hostname /etc/hosts /etc/resolv.conf", required)
    for (i in required) if (seen[required[i]] != 1) {
        print "Missing or duplicate mount: " required[i] > "/dev/stderr"; failed = 1
    }
    exit failed ? 1 : 0
}' /proc/mounts

for directory in /work /tmp /dev/shm; do
    test "$(stat -c '%a:%u:%g' "$directory")" = 700:10001:10001
done
test "$(stat -c '%a:%u:%g' /dev)" = 755:0:0
test ! -w /dev
# Do not follow /dev/fd or other symlinks into procfs or approved storage.
writable_files=$(find /dev -xdev -type f -writable -print)
test -z "$writable_files"
writable_directories=$(find /dev -xdev -type d -writable ! -path /dev/shm ! -path /dev/mqueue -print)
test -z "$writable_directories"
for masked in /proc/interrupts /proc/kcore /proc/keys /proc/latency_stats /proc/timer_list; do
    if test -e "$masked"; then
        test -c "$masked"
        test "$(stat -c '%t:%T' "$masked")" = 1:3
    fi
done
for path in /dev/codeviz-mount-probe /dev/mqueue/codeviz-mount-probe; do
    test ! -e "$path"
    if (set -C; printf blocked > "$path") 2>/dev/null; then
        echo "Unexpected regular-file storage: $path" >&2
        exit 1
    fi
    if test -e "$path"; then
        # mqueue may create an entry before rejecting ordinary byte writes.
        test "$path" = /dev/mqueue/codeviz-mount-probe
        rm "$path"
    fi
    test ! -e "$path"
done
test ! -e /var/run/docker.sock
test ! -e /run/podman/podman.sock
printf 'mounts-ok\n'
