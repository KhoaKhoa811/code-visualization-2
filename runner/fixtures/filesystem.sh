#!/bin/sh
# Fixed trusted fixture; never accepts submitted paths or commands.
set -eu
test "$(id -u)" = 10001
test "$(id -g)" = 10001

# Check effective mount options independently of write-denial errors.
awk '$2 == "/" && $4 ~ /(^|,)ro(,|$)/ { found++ } END { exit found != 1 }' /proc/mounts
for directory in / /opt/java/openjdk; do
    test -d "$directory"
    path="${directory%/}/codeviz-filesystem-probe"
    test ! -e "$path"
    if (set -C; printf blocked > "$path") 2>/dev/null; then
        echo "Protected write unexpectedly succeeded: $path" >&2
        exit 1
    fi
    test ! -e "$path"
done

for directory in /work /tmp /dev/shm; do
    awk -v target="$directory" '$2 == target && $3 == "tmpfs" && $4 ~ /(^|,)rw(,|$)/ { found++ } END { exit found != 1 }' /proc/mounts
    path="$directory/codeviz-filesystem-probe"
    (set -C; printf 'filesystem-ok\n' > "$path")
    printf 'filesystem-ok\n' | cmp - "$path"
    rm "$path"
    test ! -e "$path"
done
printf 'filesystem-ok\n'
