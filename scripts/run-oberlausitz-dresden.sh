#!/usr/bin/env bash
# Baut das Projekt und startet RunOberlausitzDresdenTest.
# Aufruf vom Repo-Root aus: bash scripts/run-oberlausitz-dresden.sh
#
# JVM-Heap ueber Umgebungsvariablen anpassbar, z. B. bei anderem WSL-RAM-Limit:
#   JAVA_XMX=20g JAVA_XMS=4g bash scripts/run-oberlausitz-dresden.sh
# Default (14g/2g): das Maximum, das in Tests noch sicher lief, OHNE die
# SAV-DRT-Flotten (Schritt 8/9) an die Grenze zu bringen - mit DRT ist selbst
# 14g knapp (siehe Speicher-Diskussion zu Schritt 8/9 in der Commit-Historie).
# ACHTUNG: passt NICHT mehr zu einem WSL-Speicherlimit von 14 GB (.wslconfig
# memory=14GB) - das liesse dem Betriebssystem keinen Puffer. Bei WSL-Limit
# 14 GB eher JAVA_XMX=11g setzen, oder das WSL-Limit erhoehen.
set -euo pipefail
cd "$(dirname "$0")/.."

JAVA_XMX="${JAVA_XMX:-14g}"
JAVA_XMS="${JAVA_XMS:-2g}"

./mvnw -q compile
CP=$(./mvnw -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout | tail -1)
java -Djava.awt.headless=true -Xmx"$JAVA_XMX" -Xms"$JAVA_XMS" -cp "target/classes:$CP" org.matsim.project.RunOberlausitzDresdenTest
