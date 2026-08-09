#!/usr/bin/env bash
# Baut das Projekt und startet RunOberlausitzDresdenTest.
# Aufruf vom Repo-Root aus: bash scripts/run-oberlausitz-dresden.sh
#
# JVM-Heap ueber Umgebungsvariablen anpassbar, z. B. bei anderem WSL-RAM-Limit:
#   JAVA_XMX=16g JAVA_XMS=4g bash scripts/run-oberlausitz-dresden.sh
# Default (10g/2g) passt zu einem WSL-Speicherlimit von 14 GB (.wslconfig memory=14GB).
set -euo pipefail
cd "$(dirname "$0")/.."

JAVA_XMX="${JAVA_XMX:-10g}"
JAVA_XMS="${JAVA_XMS:-2g}"

./mvnw -q compile
CP=$(./mvnw -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout | tail -1)
java -Djava.awt.headless=true -Xmx"$JAVA_XMX" -Xms"$JAVA_XMS" -cp "target/classes:$CP" org.matsim.project.RunOberlausitzDresdenTest
