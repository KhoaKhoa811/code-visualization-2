#!/bin/sh
# Fixed trusted probe; exhaust inode slots without filling data blocks.
set -eu
for directory in /work /tmp /dev/shm; do
    case "$directory" in
        /work) expected=4096 ;;
        *) expected=1024 ;;
    esac
    awk -v target="$directory" '$2 == target && $3 == "tmpfs" && $4 ~ /(^|,)rw(,|$)/ { found++ } END { exit found != 1 }' /proc/mounts
    if test "$(stat -f -c %c "$directory")" -ne "$expected"; then
        echo "Unexpected inode capacity: $directory" >&2
        exit 1
    fi
    baseline=$(stat -f -c %d "$directory")
    test "$baseline" -gt 2
    probe_directory="$directory/codeviz-inode-probe"
    test ! -e "$probe_directory"
    mkdir "$probe_directory"
    available=$(stat -f -c %d "$directory")
    test "$available" -eq "$((baseline - 1))"
    index=0
    while test "$index" -lt "$available"; do
        : > "$probe_directory/$index"
        index=$((index + 1))
    done
    test "$(stat -f -c %d "$directory")" -eq 0
    test "$(stat -f -c %a "$directory")" -gt 0
    if (: > "$probe_directory/overflow") 2>/dev/null; then
        echo "Creation beyond inode capacity unexpectedly succeeded: $directory" >&2
        exit 1
    fi
    test ! -e "$probe_directory/overflow"
    if mkdir "$probe_directory/overflow-directory" 2>/dev/null; then
        echo "Directory creation beyond inode capacity unexpectedly succeeded" >&2
        exit 1
    fi
    test ! -e "$probe_directory/overflow-directory"
    # Only fixed numeric files inside this newly created, probe-owned directory.
    rm "$probe_directory"/*
    rmdir "$probe_directory"
    test "$(stat -f -c %d "$directory")" -eq "$baseline"
    mkdir "$probe_directory"
    : > "$probe_directory/reused"
    rm "$probe_directory/reused"
    rmdir "$probe_directory"
    test ! -e "$probe_directory"
    test "$(stat -f -c %d "$directory")" -eq "$baseline"
done
printf 'storage-inodes-ok\n'
