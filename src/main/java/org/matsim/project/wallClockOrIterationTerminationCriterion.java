package org.matsim.project;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.core.controler.TerminationCriterion;

/**
 * Beendet den Lauf, sobald ENTWEDER lastIteration erreicht ist (Standard-
 * verhalten, siehe MATSims eingebautes TerminateAtFixedIterationNumber) ODER
 * eine Wanduhr-Frist ueberschritten wurde.
 *
 * WICHTIG: mayTerminateAfterIteration(...)/doTerminate(...) werden von
 * AbstractController IMMER NUR ZWISCHEN abgeschlossenen Iterationen
 * aufgerufen, nie mittendrin - die gerade laufende Iteration wird also
 * IMMER sauber zu Ende gefuehrt (inkl. aller regulaeren Output-Schreibvorgaenge),
 * bevor der Lauf stoppt. Das ist der entscheidende Unterschied zu einem
 * externen SIGTERM/SIGKILL zu einem festen Zeitpunkt (siehe
 * scripts/run-oberlausitz-dresden-with-timeout.sh), das JEDERZEIT treffen
 * kann, auch mitten in einer Iteration, und dabei Outputs unvollstaendig
 * hinterlassen kann - dieses Skript-Zeitlimit bleibt nur als Sicherheitsnetz
 * fuer den Fall, dass der Prozess komplett haengt und nie wieder eine
 * Iterationsgrenze erreicht.
 */
public final class wallClockOrIterationTerminationCriterion implements TerminationCriterion {

    private static final Logger log = LogManager.getLogger(wallClockOrIterationTerminationCriterion.class);

    private final int lastIteration;
    private final long deadlineEpochMillis;
    private boolean deadlineLogged = false;

    public wallClockOrIterationTerminationCriterion(int lastIteration, long deadlineEpochMillis) {
        this.lastIteration = lastIteration;
        this.deadlineEpochMillis = deadlineEpochMillis;
    }

    private boolean shouldTerminate(int iteration) {
        if (iteration >= lastIteration) {
            return true;
        }
        if (System.currentTimeMillis() >= deadlineEpochMillis) {
            if (!deadlineLogged) {
                log.warn("Wanduhr-Zeitlimit erreicht (Iteration " + iteration + " von konfigurierten "
                        + lastIteration + ") - Lauf wird NACH Abschluss dieser Iteration regulaer beendet.");
                deadlineLogged = true;
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mayTerminateAfterIteration(int iteration) {
        return shouldTerminate(iteration);
    }

    @Override
    public boolean doTerminate(int iteration) {
        return shouldTerminate(iteration);
    }
}
