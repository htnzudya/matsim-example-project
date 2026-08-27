#!/usr/bin/env bash
# Baut das Projekt und startet RunOberlausitzDresdenTest.
# Aufruf vom Repo-Root aus: bash scripts/run-oberlausitz-dresden.sh
#
# JVM-Heap ueber Umgebungsvariablen anpassbar, z. B. bei anderem WSL-RAM-Limit:
#   JAVA_XMX=20g JAVA_XMS=4g bash scripts/run-oberlausitz-dresden.sh
# Default (22g/4g): bei 14g lief die 10pct-Population mit den SAV-DRT-Flotten
# (Schritt 8/9) nicht zuverlaessig durch (OOM zuletzt sogar erst in der
# Analyse-/Output-Phase, nicht nur beim Matrix-Aufbau - siehe Commit-Historie).
# ACHTUNG: braucht entsprechend mehr System-RAM als Puffer fuers Betriebssystem
# obendrauf - bei WSL muss .wslconfig memory= entsprechend hoeher als Xmx
# liegen, sonst genau dasselbe Problem wie vorher, nur bei einer groesseren
# Zahl. 22g gewaehlt fuer ein 32GB-Gesamtsystem mit .wslconfig memory=27-28g
# (WSL-eigener Overhead: Metaspace/Thread-Stacks/Direct-Memory/Linux selbst)
# UND 4GB fest fuer Windows reserviert - bei mehr Gesamt-RAM entsprechend
# hochsetzbar.
set -euo pipefail
cd "$(dirname "$0")/.."

JAVA_XMX="${JAVA_XMX:-22g}"
JAVA_XMS="${JAVA_XMS:-4g}"

./mvnw -q compile
CP=$(./mvnw -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout | tail -1)
java -Djava.awt.headless=true -Xmx"$JAVA_XMX" -Xms"$JAVA_XMS" -cp "target/classes:$CP" org.matsim.project.RunOberlausitzDresdenTest
