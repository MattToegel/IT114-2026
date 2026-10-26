#!/usr/bin/env bash
set -eu
project="${1:-Project}"
mode="${2:-client}"
version="$(java -version 2>&1)"
if [[ ! "$version" =~ ([0-9]+) ]] || (( BASH_REMATCH[1] < 26 )); then
    echo "Use Java 26 or later"; exit 1
fi
case "$mode" in
    client) entry="Client.Client" ;;
    server) entry="Server.Server" ;;
    *) echo "Use client or server"; exit 1 ;;
esac
namespace="${project//\//.}"
java "$namespace.$entry" "${@:3}"
