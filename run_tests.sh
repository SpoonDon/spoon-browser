#!/bin/bash

# Spoon Browser Test Runner Script
# This script runs all tests for the Spoon Browser project

set -e

echo "========================================="
echo "  Spoon Browser - Test Suite Runner"
echo "========================================="
echo ""

cd "$(dirname "$0")/android"

# Check if Gradle wrapper exists
if [ ! -f "./gradlew" ]; then
    echo "ERROR: Gradle wrapper not found!"
    echo "Please ensure you're in the correct directory."
    exit 1
fi

# Make gradlew executable
chmod +x ./gradlew

echo "Running Unit Tests (Local JVM)..."
echo "---------------------------------"
./gradlew testDebugUnitTest --stacktrace

echo ""
echo "Running Instrumented Tests (Android Device/Emulator)..."
echo "--------------------------------------------------------"
echo "NOTE: These tests require an Android device or emulator connected."
echo ""

# Check if any Android device/emulator is connected
if command -v adb &> /dev/null; then
    device_count=$(adb devices | grep -v "^$" | grep -v "List" | wc -l)
    if [ "$device_count" -gt 0 ]; then
        echo "Found $device_count Android device(s)/emulator(s)"
        ./gradlew connectedDebugAndroidTest --stacktrace
    else
        echo "WARNING: No Android device or emulator connected."
        echo "Skipping instrumented tests."
        echo "To run instrumented tests:"
        echo "  1. Connect an Android device via USB with debugging enabled"
        echo "  2. OR start an Android emulator"
        echo "  3. Re-run this script"
    fi
else
    echo "WARNING: Android Debug Bridge (adb) not found."
    echo "Skipping instrumented tests."
fi

echo ""
echo "========================================="
echo "  Test execution completed!"
echo "========================================="
echo ""
echo "Test reports can be found at:"
echo "  - Unit Tests: android/app/build/reports/tests/testDebugUnitTest/"
echo "  - Instrumented Tests: android/app/build/reports/androidTests/connectedDebugAndroidTest/"
echo ""
