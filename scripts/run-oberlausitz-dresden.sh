#!/usr/bin/env bash
# Baut das Projekt und startet RunOberlausitzDresdenTest.
# Aufruf vom Repo-Root aus: bash scripts/run-oberlausitz-dresden.sh
set -euo pipefail
cd "$(dirname "$0")/.."

./mvnw -q compile
CP=$(./mvnw -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout | tail -1)
java -cp "target/classes:$CP" org.matsim.project.RunOberlausitzDresdenTest
