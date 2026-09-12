#!/bin/sh
# Aurum Edge — CI diagnostics.
#
# GitHub renders a failed build as one generic annotation ("Process completed with exit
# code 1") plus a job log. When that log cannot be read from where the automation sits,
# the build has to state what broke by itself. This script reads captured Gradle output
# and republishes the real compiler messages as actions annotations (workflow commands),
# which are readable through the checks API.
#
# Usage: tools/ci-annotate.sh <gradle-output.log>   (always exits 0)
# Nothing here changes the build result: the caller keeps the original exit code.

log="$1"
[ -n "$log" ] && [ -f "$log" ] || exit 0

awk '
    function esc(s) {
        gsub(/%/, "%25", s)
        gsub(/\r/, "%0D", s)
        gsub(/\n/, " ", s)
        return s
    }

    { lines[NR] = $0; seen[$0] = 1 }

    END {
        total = 0
        # 1) Kotlin / Java compiler diagnostics — the richest signal:
        #    e: file:///home/runner/work/repo/repo/android/app/src/main/java/.../Foo.kt:88:5 Unresolved reference: KEY
        for (i = 1; i <= NR && total < 6; i++) {
            line = lines[i]
            if (line !~ /^e: file:/) continue
            sub(/^e: file:\/\//, "", line)
            sub(/^file:/, "", line)
            if (!match(line, /:[0-9]+:[0-9]+ /)) continue
            path = substr(line, 1, RSTART - 1)
            rest = substr(line, RSTART + 1)
            split(rest, parts, " ")
            pos = parts[1]
            split(pos, where, ":")
            msg = substr(rest, length(pos) + 2)
            sub(/^.*\/android\//, "android/", path)
            printf "::error file=%s,line=%s,col=%s::%s\n", path, where[1], where[2], esc(msg)
            total++
        }

        # 2) Gradle failure text (task names, resolution failures, version mismatches).
        start = 0
        for (i = 1; i <= NR; i++) if (lines[i] ~ /What went wrong:/) { start = i; break }
        shown = 0
        if (start > 0) {
            for (j = start + 1; j <= NR && shown < 2 && total < 10; j++) {
                s = lines[j]
                if (s ~ /^\* Try:/ || s ~ /^\* Exception is:/ || s ~ /^FAILURE:/ || s ~ /^BUILD FAILED/) break
                sub(/^[> ]+/, "", s)
                if (length(s) == 0) continue
                printf "::error::%s\n", esc(s)
                shown++
                total++
            }
        }

        # 3) Last resort when nothing above matched: one line that names a failure.
        if (total == 0) {
            for (i = NR; i >= 1; i--) {
                s = lines[i]
                if (s ~ /e: / || s ~ /error: / || s ~ /Could not resolve/ || s ~ /Execution failed for task/) {
                    printf "::error::%s\n", esc(s)
                    break
                }
            }
        }
    }
' "$log"

exit 0
