#!/usr/bin/env bash
set -eu
project="${1:-Project}"
[[ -d "$project" ]] || { echo "Project folder not found: $project"; exit 1; }
version="$(javac -version 2>&1)"
if [[ ! "$version" =~ ([0-9]+) ]] || (( BASH_REMATCH[1] < 26 )); then
    echo "Use JDK 26 or later"; exit 1
fi
bash rm_class_files.sh "$project"
sources="$(mktemp)"
trap 'rm -f "$sources"' EXIT
while IFS= read -r file; do
    printf '"%s"\n' "$file"
done < <(find "$project" -type f -name '*.java') > "$sources"
[[ -s "$sources" ]] || { echo "No Java source files found"; exit 1; }
javac -cp . @"$sources"
echo "Compiled $project"
