#!/usr/bin/env bash
# Runs ./gradlew with the arguments given.
#
# Maven Central, where the build tools are downloaded from, now and then answers
# "429 Too Many Requests" to GitHub's shared build machines. On 23-24 Nov 2026 that
# failed four of six builds before a line of code was compiled. So: when a run
# fails BECAUSE A DOWNLOAD WAS REFUSED, wait and try again (what was already
# downloaded is kept, so each try needs less). A real failure - a test, a compile
# error - is never retried.
set -uo pipefail
log="${RUNNER_TEMP:-/tmp}/gradle-attempt.log"
tries=6
for attempt in $(seq 1 "$tries"); do
  ./gradlew "$@" 2>&1 | tee "$log"
  rc=${PIPESTATUS[0]}
  [ "$rc" -eq 0 ] && exit 0
  if ! grep -qE "status code 429|Too Many Requests|Could not GET |Read timed out|Connection reset" "$log"; then
    exit "$rc"                                   # not a download problem: fail at once
  fi
  [ "$attempt" -eq "$tries" ] && break
  wait=$((attempt * 45))
  echo "::warning::A download was refused (attempt $attempt of $tries). Waiting ${wait}s, then trying again."
  sleep "$wait"
done
echo "::error::Downloads were still refused after $tries attempts. Nothing is wrong with the code; run this job again later."
exit "$rc"
