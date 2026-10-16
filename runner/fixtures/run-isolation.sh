#!/bin/sh
# Fixed marker paths and synthetic tokens only; no submitted source or real secrets.
set -eu
mode=$1
own=$2
other=$3
previous=$4
for directory in /work /tmp /dev/shm; do
    common="$directory/codeviz-run-data"
    unique="$directory/codeviz-$own"
    test ! -e "$directory/codeviz-$other"
    test ! -e "$directory/codeviz-$previous"
    if [ "$mode" = seed ]; then
        test ! -e "$common"
        test ! -e "$unique"
        printf %s "$own" > "$common"
        printf %s "$own" > "$unique"
    else
        test "$mode" = check
    fi
    printf %s "$own" | cmp - "$common"
    printf %s "$own" | cmp - "$unique"
done
printf 'run-isolation-ok\n'
