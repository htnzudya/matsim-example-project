#!/usr/bin/env bash
# Basislauf des Basis-vs-AVM-Vergleichs (siehe behaviourConfigGroup.
# avmModesEnabled-Javadoc, Abschnitt "Latente Nachfrage" der Auftraggeber-
# Spezifikation): identisch zu run-oberlausitz-dresden.sh, nur mit
# AVM_MODES_ENABLED=false - AV/PSAV/SSAV stehen dann nicht im Choice-Set,
# alles andere (Population, randomSeed, ascNull, Kandidatenweg-Vorlagen)
# bleibt unveraendert. Output landet automatisch in einem eigenen
# "-basis"-Ordner (siehe RunOberlausitzDresdenTest.prepareConfig), ueberschreibt
# also nicht den AVM-Lauf.
#
# Aufruf vom Repo-Root aus: bash scripts/run-oberlausitz-dresden-basis.sh
# JVM-Heap-Optionen: siehe run-oberlausitz-dresden.sh.
set -euo pipefail
cd "$(dirname "$0")/.."

AVM_MODES_ENABLED=false exec bash scripts/run-oberlausitz-dresden.sh
