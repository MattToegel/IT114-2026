#!/usr/bin/env bash
set -eu
project="${1:-Project}"
repo_root="$(pwd -P)"
target="$(cd "$project" && pwd -P)"
case "$target/" in
    "$repo_root/"*) [[ "$target" != "$repo_root" ]] || { echo "Choose a project subfolder"; exit 1; } ;;
    *) echo "Project must be inside this repository"; exit 1 ;;
esac
find "$target" -type f -name '*.class' -delete
