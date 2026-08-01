#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <gradle-task> <plugin-zip>" >&2
  exit 2
fi

task_name="$1"
plugin_archive="$2"

if [[ ! -f "$plugin_archive" ]]; then
  echo "plugin archive does not exist: $plugin_archive" >&2
  exit 2
fi

plugin_archive="$(cd "$(dirname "$plugin_archive")" && pwd)/$(basename "$plugin_archive")"
if ! dry_run="$(./gradlew --no-daemon "$task_name" --dry-run "-PpluginArchive=$plugin_archive" 2>&1)"; then
  printf '%s\n' "$dry_run" >&2
  echo "$task_name dry run failed for $plugin_archive" >&2
  exit 1
fi
printf '%s\n' "$dry_run"

if grep -Eq '(^|[[:space:]]):?(buildPlugin|composedJar|instrumentedJar|jar)([[:space:]]|$)' <<<"$dry_run"; then
  echo "$task_name would rebuild plugin bytes instead of consuming $plugin_archive" >&2
  exit 1
fi
