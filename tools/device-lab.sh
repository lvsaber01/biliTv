#!/usr/bin/env bash
# Select exactly one of the project's local Android test profiles.
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
target="${1:-}"
case "$target" in
    tv) script=tv-lab.sh ;;
    tablet) script=tablet-lab.sh ;;
    phone) script=phone-lab.sh ;;
    *)
        echo 'Choose a device profile: tv | tablet | phone' >&2
        echo 'Usage: bash tools/device-lab.sh <tv|tablet|phone> <prepare|launch|...>' >&2
        exit 2
        ;;
esac
shift
if [[ $# == 0 ]]; then set -- help; fi
exec bash "$PROJECT_ROOT/tools/$script" "$@"
