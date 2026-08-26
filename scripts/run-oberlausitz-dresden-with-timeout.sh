#!/usr/bin/env bash
# Startet run-oberlausitz-dresden.sh mit einer Wanduhr-Obergrenze.
#
# ZWEISCHICHTIG:
#   1. PRIMAER: RunOberlausitzDresdenTest.prepareControler(...) registriert ein
#      wallClockOrIterationTerminationCriterion, das MATSim NUR ZWISCHEN
#      Iterationen prueft - die laufende Iteration wird also immer sauber zu
#      Ende gefuehrt (inkl. aller regulaeren Outputs), bevor der Prozess sich
#      SELBST regulaer beendet. Liest dieselbe Umgebungsvariable
#      MAX_RUNTIME_SECONDS (siehe unten, wird an den Java-Prozess durchgereicht).
#   2. SICHERHEITSNETZ (dieses Skript): falls der Prozess trotzdem haengt (z. B.
#      in der QSim-Simulation selbst haengen bleibt und nie wieder eine
#      Iterationsgrenze erreicht), wird die GESAMTE Prozessgruppe nach
#      MAX_RUNTIME_SECONDS + SAFETY_BUFFER_SECONDS hart per SIGTERM/SIGKILL
#      beendet - das kann mitten in einer Iteration treffen und Outputs
#      unvollstaendig hinterlassen, ist aber bewusst NUR der Fallback fuer
#      einen echten Haenger, nicht der Normalfall.
#
# Aufruf vom Repo-Root aus:
#   bash scripts/run-oberlausitz-dresden-with-timeout.sh [logdatei]
#
# Obergrenze anpassbar (Default: 3 Tage):
#   MAX_RUNTIME_SECONDS=3600 bash scripts/run-oberlausitz-dresden-with-timeout.sh
#
# Technischer Hintergrund zum Prozessgruppen-Kill: run-oberlausitz-dresden.sh
# startet "java ..." als GEWOEHNLICHEN Kindprozess von bash (kein exec) - ein
# simples "timeout N run-oberlausitz-dresden.sh" wuerde bei Ablauf nur die
# AEUSSERE bash beenden, der Java-Prozess liefe als verwaistes Kind einfach
# weiter. Deshalb: mit "set -m" (Bash-Job-Control-Modus, eingebaut - anders
# als setsid(1) auf macOS ohne Zusatzpaket nicht verfuegbar) bekommt der
# Hintergrundjob eine EIGENE Prozessgruppe, getrennt von der des Wrapper-
# Skripts selbst. Bei Timeout wird die GESAMTE Gruppe (negative PID) per
# SIGTERM, nach Karenzzeit per SIGKILL beendet - das erreicht garantiert
# auch den Java-Prozess, ohne den Wrapper-Prozess selbst mit zu treffen.
set -uo pipefail
cd "$(dirname "$0")/.."

export MAX_RUNTIME_SECONDS="${MAX_RUNTIME_SECONDS:-259200}"   # 3 Tage = 3*24*60*60
SAFETY_BUFFER_SECONDS=7200   # 2h Puffer, damit das Java-eigene Terminieren zwischen Iterationen normalerweise zuerst greift
KILL_AFTER_SECONDS=$((MAX_RUNTIME_SECONDS + SAFETY_BUFFER_SECONDS))
POLL_INTERVAL_SECONDS=60
KILL_GRACE_SECONDS=60
LOG_FILE="${1:-/tmp/oberlausitz-dresden-run.log}"

echo "Starte Lauf: Java stoppt selbst nach ${MAX_RUNTIME_SECONDS}s ($((MAX_RUNTIME_SECONDS / 3600))h) oder lastIteration, Sicherheitsnetz-Kill nach ${KILL_AFTER_SECONDS}s ($((KILL_AFTER_SECONDS / 3600))h). Log: $LOG_FILE"

set -m
bash scripts/run-oberlausitz-dresden.sh > "$LOG_FILE" 2>&1 &
RUN_PID=$!
set +m
echo "$RUN_PID" > /tmp/oberlausitz-dresden-run.pid
echo "Lauf gestartet, PID (=Prozessgruppe) $RUN_PID"

elapsed=0
while kill -0 "$RUN_PID" 2>/dev/null; do
    if [ "$elapsed" -ge "$KILL_AFTER_SECONDS" ]; then
        echo "SICHERHEITSNETZ: ${KILL_AFTER_SECONDS}s ueberschritten, obwohl der Prozess haette selbst terminieren sollen - vermutlich haengt er. Beende Prozessgruppe $RUN_PID (SIGTERM)." | tee -a "$LOG_FILE"
        kill -TERM -- "-$RUN_PID" 2>/dev/null
        sleep "$KILL_GRACE_SECONDS"
        if kill -0 "$RUN_PID" 2>/dev/null; then
            echo "Prozessgruppe reagiert nicht auf SIGTERM - erzwinge SIGKILL." | tee -a "$LOG_FILE"
            kill -KILL -- "-$RUN_PID" 2>/dev/null
        fi
        echo "ABGEBROCHEN: Lauf wegen Sicherheitsnetz-Zeitlimit beendet (haengender Prozess vermutet)." | tee -a "$LOG_FILE"
        exit 124
    fi
    sleep "$POLL_INTERVAL_SECONDS"
    elapsed=$((elapsed + POLL_INTERVAL_SECONDS))
done

wait "$RUN_PID"
EXIT_CODE=$?
if [ "$EXIT_CODE" -eq 0 ]; then
    echo "FERTIG: Lauf regulaer abgeschlossen nach ca. ${elapsed}s (entweder lastIteration erreicht oder Java-eigenes Zeitlimit nach abgeschlossener Iteration gegriffen - siehe Log fuer Details)." | tee -a "$LOG_FILE"
else
    echo "FEHLER: Lauf mit Exit-Code $EXIT_CODE beendet (kein Timeout, echter Fehler - siehe Log)." | tee -a "$LOG_FILE"
fi
exit "$EXIT_CODE"
