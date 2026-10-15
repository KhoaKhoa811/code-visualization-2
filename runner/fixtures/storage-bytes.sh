#!/bin/sh
# Fixed trusted capacity check; one mount is filled at a time.
set -eu
for directory in /work /tmp /dev/shm; do
    case "$directory" in
        /work) expected=67108864 ;;
        *) expected=16777216 ;;
    esac
    awk -v target="$directory" '$2 == target && $3 == "tmpfs" && $4 ~ /(^|,)rw(,|$)/ { found++ } END { exit found != 1 }' /proc/mounts
    block_size=$(stat -f -c %S "$directory")
    blocks=$(stat -f -c %b "$directory")
    available=$(stat -f -c %a "$directory")
    free_inodes=$(stat -f -c %d "$directory")
    if test "$((block_size * blocks))" -ne "$expected"; then
        echo "Unexpected capacity: $directory" >&2
        exit 1
    fi
    test "$available" -gt 0
    test "$free_inodes" -gt 2
    path="$directory/codeviz-storage-probe"
    test ! -e "$path"
    # Account for existing fixture files; write real pages, not sparse extents.
    dd if=/dev/zero of="$path" bs="$block_size" count="$available" status=none
    filled_bytes=$((block_size * available))
    test "$(stat -c %s "$path")" -eq "$filled_bytes"
    test "$(stat -f -c %a "$directory")" -eq 0
    test "$(stat -f -c %d "$directory")" -gt 0
    if (printf x >> "$path") 2>/dev/null; then
        echo "Write beyond capacity unexpectedly succeeded: $directory" >&2
        exit 1
    fi
    test "$(stat -c %s "$path")" -eq "$filled_bytes"
    rm "$path"
    test ! -e "$path"
    test "$(stat -f -c %a "$directory")" -eq "$available"
    printf reusable > "$path"
    printf reusable | cmp - "$path"
    rm "$path"
    test "$(stat -f -c %a "$directory")" -eq "$available"
done
printf 'storage-bytes-ok\n'
