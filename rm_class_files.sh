#!/bin/bash

# Stop the script immediately if a command fails.
# This helps prevent the script from continuing in a bad state.
set -e

# Make sure the user gave us exactly one argument.
# The argument should be the folder we want to clean.
if [ "$#" -ne 1 ]; then
	echo "Usage: $0 <directory>"
	exit 1
fi

# Store the folder path from the first argument in a clearly named variable.
target_dir="$1"

# Prevent obviously dangerous targets.
# An empty path is never valid, and deleting from the filesystem root is too risky.
if [ -z "$target_dir" ] || [ "$target_dir" = "/" ]; then
	echo "Error: Refusing to run on '$target_dir'. Please provide a specific project directory."
	exit 1
fi

# Make sure the path actually exists and is a directory.
# This prevents mistakes like passing a missing path or a regular file.
if [ ! -d "$target_dir" ]; then
	echo "Error: '$target_dir' is not a valid directory."
	exit 1
fi

# If the user wants to clean the current folder, ask for confirmation first.
# This adds a safety pause before deleting files from wherever the script was launched.
if [ "$target_dir" = "." ]; then
	read -r -p "You chose the current directory. Delete all .class files here and below? (y/n) " response
	case "$response" in
		y|Y)
			;;
		*)
			echo "Operation cancelled."
			exit 0
			;;
	esac
fi

# Count how many compiled Java class files exist before deleting them.
# We do this first so we can show a final summary after the delete is done.
count=$(find "$target_dir" -type f -name "*.class" | wc -l | tr -d ' ')

# If there are no class files, say so and stop.
if [ "$count" -eq 0 ]; then
	echo "No .class files found."
	exit 0
fi

# Find all compiled Java class files under the target directory, print each one,
# and then delete it.
# Using "$target_dir" directly means we do not need to change folders first.
find "$target_dir" -type f -name "*.class" -print -delete

# Print a final summary so the user knows how many files were removed.
echo "Deleted $count .class file(s)."