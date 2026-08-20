#!/usr/bin/env bash
# Baut das Projekt und startet RunOberlausitzDresdenTest.
# Aufruf vom Repo-Root aus: bash scripts/run-oberlausitz-dresden.sh
#
# JVM-Heap ueber Umgebungsvariablen anpassbar, z. B. bei anderem WSL-RAM-Limit:
#   JAVA_XMX=20g JAVA_XMS=4g bash scripts/run-oberlausitz-dresden.sh
# Default (26g/4g): hochgesetzt, nachdem mehr RAM verfuegbar wurde - bei 14g
# lief die 10pct-Population mit den SAV-DRT-Flotten (Schritt 8/9) nicht
# zuverlaessig durch (OOM zuletzt sogar erst in der Analyse-/Output-Phase,
# nicht nur beim Matrix-Aufbau - siehe Commit-Historie). ACHTUNG: braucht
# entsprechend mehr System-RAM als Puffer fuers Betriebssystem obendrauf -
# bei WSL muss .wslconfig memory= entsprechend hoeher als 26g liegen, sonst
# genau dasselbe Problem wie vorher, nur bei einer groesseren Zahl.
set -euo pipefail
cd "$(dirname "$0")/.."

JAVA_XMX="${JAVA_XMX:-26g}"
JAVA_XMS="${JAVA_XMS:-4g}"

./mvnw -q compile
CP=$(./mvnw -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout | tail -1)
java -Djava.awt.headless=true -Xmx"$JAVA_XMX" -Xms"$JAVA_XMS" -cp "target/classes:$CP" org.matsim.project.RunOberlausitzDresdenTest
