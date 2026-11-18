#!/bin/bash

# Stop the script immediately if a command fails.
# This helps prevent later steps from running after an earlier problem.
set -e

# Choose the minimum JDK version required to run this build.
# Change this number later if you want to require a different minimum version.
minimum_jdk_version=23

# Make sure the user gave us exactly one argument.
# The argument should be the folder we want to build.
if [ "$#" -ne 1 ]; then
	echo "Usage: $0 <directory>"
	exit 1
fi

# Store the folder path from the first argument in a clearly named variable.
target_dir="$1"

# Prevent obviously dangerous targets.
# An empty path is never valid, and using the filesystem root would be too risky.
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

# Make sure javac is installed and available on the command line.
# The build cannot continue without the Java compiler.
if ! command -v javac >/dev/null 2>&1; then
	echo "Error: javac was not found in your PATH."
	echo "Please install JDK $minimum_jdk_version or newer, or update your PATH."
	exit 1
fi

# Ask javac for its version text.
# Some javac versions print to stderr, so we redirect stderr to stdout.
javac_version_output=$(javac -version 2>&1)

# Pull the numeric version part out of the javac version text.
# Example: 'javac 23.0.1' becomes '23.0.1'.
javac_version_string=$(echo "$javac_version_output" | awk '{print $2}')

# Extract the major version number.
# For modern Java versions this is the number before the first dot.
# For older Java 8 style versions like 1.8, we use the second number instead.
javac_major_version=$(echo "$javac_version_string" | cut -d'.' -f1)
if [ "$javac_major_version" = "1" ]; then
	javac_major_version=$(echo "$javac_version_string" | cut -d'.' -f2)
fi

# Stop if the installed JDK is older than the required version.
if [ "$javac_major_version" -lt "$minimum_jdk_version" ]; then
	echo "Error: This build requires JDK $minimum_jdk_version or newer."
	echo "Found: $javac_version_output"
	exit 1
fi

# If the user wants to build the current folder, ask for confirmation first.
# This adds a quick safety pause before deleting old .class files in the current tree.
if [ "$target_dir" = "." ]; then
	read -r -p "You chose the current directory. Delete old .class files and build all .java files here and below? (y/n) " response
	case "$response" in
		y|Y)
			;;
		*)
			echo "Operation cancelled."
			exit 0
			;;
	esac
fi

# Create a temporary file to hold the list of Java source files.
# Using a temporary file avoids leaving a permanent sources.txt behind.
source_list=$(mktemp)

# Always remove the temporary file when the script exits,
# even if the build fails partway through.
trap 'rm -f "$source_list"' EXIT

# Count Java files first so we can give helpful feedback.
java_count=$(find "$target_dir" -type f -name "*.java" | wc -l | tr -d ' ')

# If no Java files were found, there is nothing to compile.
if [ "$java_count" -eq 0 ]; then
	echo "No .java files found."
	exit 0
fi

# Delete old compiled class files before rebuilding.
# This helps ensure the build starts from a clean state.
find "$target_dir" -type f -name "*.class" -print -delete

# Build a file list for javac.
# javac can read source file paths from a file when prefixed with @.
find "$target_dir" -type f -name "*.java" > "$source_list"

# Compile every Java file listed in the temporary source list.
# We do not use --release here because you asked to compile with the highest
# available JDK on the machine. That means javac uses its own current version.
javac @"$source_list"

# Print a final summary so the user knows how many source files were compiled.
echo "Compiled $java_count .java file(s) using installed JDK $javac_major_version (minimum required: $minimum_jdk_version)."