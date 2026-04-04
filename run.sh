#!/bin/bash

# Stop the script immediately if a command fails.
# This helps avoid confusing follow-up errors.
set -e

# Choose the minimum JRE version required to run this script.
# Change this number later if you want to require a different minimum version.
minimum_jre_version=23

# Make sure the user gave us at least the package root.
# Example: ./run.sh Project server
if [ "$#" -lt 1 ]; then
    echo "Usage: $0 <package_root> [server|client|ui] [port] [-d]"
    exit 1
fi

# The first argument is the Java package root.
# Example: Project
package_root="$1"

# The second argument chooses what to run.
# If omitted, default to the client.
mode=$(echo "${2:-client}" | tr '[:upper:]' '[:lower:]')

# The third argument is the port.
# It is only used when launching the server.
port="${3:-3000}"

# Start with debug mode turned off.
debug=false
debug_arg=""

# Check whether -d was included anywhere in the command.
# If it was, enable Java remote debugging on port 5005.
if [[ " $* " == *" -d "* ]]; then
    debug=true
fi

if [ "$debug" = true ]; then
    # This tells Java to start in debug mode so VS Code can attach to it.
    debug_arg="-agentlib:jdwp=transport=dt_socket,server=y,address=5005"
    echo "Debug mode is ON"
fi

# Make sure Java is installed and available on the command line.
if ! command -v java >/dev/null 2>&1; then
    echo "Error: java was not found in your PATH."
    exit 1
fi

# Ask java for its version text.
# The version text is usually printed to stderr, so we redirect stderr to stdout.
java_version_output=$(java -version 2>&1 | head -n 1)

# Pull the numeric version part out of the version text.
# Example: 'java version "23.0.1"' becomes '23.0.1'.
java_version_string=$(echo "$java_version_output" | cut -d'"' -f2)

# Extract the major version number.
# For modern Java versions this is the number before the first dot.
# For older Java 8 style versions like 1.8, we use the second number instead.
java_major_version=$(echo "$java_version_string" | cut -d'.' -f1)
if [ "$java_major_version" = "1" ]; then
    java_major_version=$(echo "$java_version_string" | cut -d'.' -f2)
fi

# Stop if the installed JRE is older than the required version.
if [ "$java_major_version" -lt "$minimum_jre_version" ]; then
    echo "Error: This script requires JRE $minimum_jre_version or newer."
    echo "Found: $java_version_output"
    exit 1
fi

# Run the requested program.
if [ "$mode" = "server" ]; then
    java $debug_arg "$package_root.Server.Server" "$port"
elif [ "$mode" = "client" ]; then
    java $debug_arg "$package_root.Client.Client"
elif [ "$mode" = "ui" ]; then
    # Milestone 3 uses ClientUI as the UI entry point.
    java $debug_arg "$package_root.Client.ClientUI"
else
    echo "Error: mode must be one of: server, client, ui"
    echo "Example: $0 Project server 3000"
    exit 1
fi