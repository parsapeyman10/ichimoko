#!/bin/sh
# Trading — CI diagnostics, second stage.
#
# A failed Actions job reaches GitHub as one generic annotation, while the job log itself
# is served from storage that is not reachable from every environment. The gradlew wrapper
# already republishes compiler messages as annotations, but CI invokes an installed Gradle
# binary, so when a task fails the build calls this script: it rebuilds a scratch copy of
# the project through ./gradlew, captures the output and publishes the compiler errors as
# annotations again. That is how a failing build explains itself.
#
# Usage: tools/ci-diagnose.sh <failed-task-path>     (always exits 0)

task="${1:-}"
[ -n "$task" ] || exit 0

here=$(cd "$(dirname "$0")/.." 2>/dev/null && pwd) || exit 0
[ -f "$here/settings.gradle.kts" ] || exit 0

work="${TMPDIR:-/tmp}/aurum-edge-diagnose"
rm -rf "$work" 2>/dev/null
mkdir -p "$work/src" 2>/dev/null || exit 0

# A scratch copy avoids fighting the build that is still holding the project lock, and
# skipping previous build output keeps the compile genuine.
command -v tar >/dev/null 2>&1 || exit 0
( cd "$here" && tar -cf - --exclude=./build --exclude=./app/build --exclude=./.gradle . ) 2>/dev/null \
    | ( cd "$work/src" && tar -xf - ) 2>/dev/null || exit 0

log="$work/diagnose.log"
runner_command() {
    if [ -x "$work/src/gradlew" ]; then
        ( cd "$work/src" && AURUM_DIAGNOSE=1 timeout 900 ./gradlew "$task" --no-daemon --console=plain --stacktrace )
    else
        ( cd "$work/src" && AURUM_DIAGNOSE=1 timeout 900 gradle "$task" --no-daemon --console=plain --stacktrace )
    fi
}

runner_command > "$log" 2>&1
status=$?
printf '::warning::Aurum diagnostics: scratch build of %s exited with %s — real compiler messages follow\n' "$task" "$status"

sh "$(dirname "$0")/ci-annotate.sh" "$log"

# If the extractor found nothing recognisable, publish the tail so the failure still has a face.
if ! grep -qE '^e: file:|What went wrong:' "$log" 2>/dev/null; then
    grep -v '^::' "$log" 2>/dev/null | tail -n 6 | while IFS= read -r line; do
        [ -n "$line" ] && printf '::error::%s\n' "$(printf '%s' "$line" | sed -e 's/%/%25/g')"
    done
fi

rm -rf "$work" 2>/dev/null
exit 0
