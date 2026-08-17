package org.matsim.project;

import org.matsim.project.model.*;

import java.util.*;

/**
 * Unit-Tests des generischen Softmax/Ziehungs-Mechanismus, den die
 * Nullalternative im DCM nutzt (behaviourUtilityFunction.softmax/
 * drawFromCumulative) - ohne MATSim, ohne Simulation.
 *
 * Modelliert das erweiterte Choice-Set C_n+ = C_n ∪ {0} ueber
 * Optional&lt;alternatives&gt; (Optional.empty() = Nullalternative), genau wie
 * behaviourCandidateTripInserter es tatsaechlich tut.
 *
 * Bewusst ohne JUnit geschrieben, analog zu {@link UtilityFunctionTest}.
 */
public class NullAlternativeTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testProbabilitiesSumToOne();
        testVeryNegativeAscNullMakesRealModeAlmostCertain();
        testVeryPositiveAscNullMakesNullAlmostCertain();
        testDrawBelowNullCumulativeChoosesNull();
        testDrawAboveNullCumulativeChoosesRealMode();
        testCumulativeOrderMatchesInsertionOrder();
        testDrawIsDeterministicForSameSeed();
        testEmptyChoiceSetIsRejected();

        System.out.println();
        System.out.println("Bestanden: " + passed + " / Fehlgeschlagen: " + failed);
        if (failed > 0) {
            throw new AssertionError("Es sind Tests fehlgeschlagen.");
        }
    }

    // ---------------------------------------------------------------- Tests

    static void testProbabilitiesSumToOne() {
        Map<Optional<alternatives>, Double> v = choiceSet(-0.7, 0.4, -0.2);
        Map<Optional<alternatives>, Double> p = behaviourUtilityFunction.softmax(v, 1.0);

        double sum = p.values().stream().mapToDouble(Double::doubleValue).sum();
        check("Wahrscheinlichkeiten (inkl. Nullalternative) summieren zu 1", near(sum, 1.0));
    }

    static void testVeryNegativeAscNullMakesRealModeAlmostCertain() {
        Map<Optional<alternatives>, Double> v = choiceSet(-20.0, 0.5, 0.3);
        Map<Optional<alternatives>, Double> p = behaviourUtilityFunction.softmax(v, 1.0);

        check("Stark negatives ASC_0 macht die Nullalternative praktisch unmoeglich",
                p.get(Optional.<alternatives>empty()) < 1e-6);
    }

    static void testVeryPositiveAscNullMakesNullAlmostCertain() {
        Map<Optional<alternatives>, Double> v = choiceSet(20.0, 0.5, 0.3);
        Map<Optional<alternatives>, Double> p = behaviourUtilityFunction.softmax(v, 1.0);

        check("Stark positives ASC_0 macht die Nullalternative praktisch sicher",
                p.get(Optional.<alternatives>empty()) > 1.0 - 1e-6);
    }

    static void testDrawBelowNullCumulativeChoosesNull() {
        // Nullalternative zuerst eingefuegt (wie in behaviourCandidateTripInserter) -> erste Position der kumulierten Verteilung.
        Map<Optional<alternatives>, Double> p = new LinkedHashMap<>();
        p.put(Optional.empty(), 0.3);
        p.put(Optional.of(alternatives.CA), 0.7);

        Optional<alternatives> chosen = behaviourUtilityFunction.drawFromCumulative(p, 0.1);
        check("Ziehung unterhalb der Nullalternativen-Wahrscheinlichkeit waehlt die Nullalternative",
                chosen.isEmpty());
    }

    static void testDrawAboveNullCumulativeChoosesRealMode() {
        Map<Optional<alternatives>, Double> p = new LinkedHashMap<>();
        p.put(Optional.empty(), 0.3);
        p.put(Optional.of(alternatives.CA), 0.7);

        Optional<alternatives> chosen = behaviourUtilityFunction.drawFromCumulative(p, 0.5);
        check("Ziehung oberhalb der Nullalternativen-Wahrscheinlichkeit waehlt den Modus",
                chosen.equals(Optional.of(alternatives.CA)));
    }

    static void testCumulativeOrderMatchesInsertionOrder() {
        // Reihenfolge der kumulierten Verteilung folgt der Map-Einfuegereihenfolge
        // (LinkedHashMap): CA zuerst (kumuliert bis 0.4), dann die Nullalternative
        // (kumuliert bis 1.0) - ein draw von 0.5 faellt also in das Null-Segment
        // [0.4, 1.0), obwohl CA zuerst in der Map steht. behaviourCandidateTripInserter
        // fuegt in der Praxis IMMER zuerst Optional.empty() ein (siehe Klassen-Javadoc
        // dort); dieser Test zeigt, dass drawFromCumulative(...) selbst unabhaengig
        // von dieser Konvention strikt der uebergebenen Reihenfolge folgt.
        Map<Optional<alternatives>, Double> p = new LinkedHashMap<>();
        p.put(Optional.of(alternatives.CA), 0.4);
        p.put(Optional.empty(), 0.6);

        Optional<alternatives> chosen = behaviourUtilityFunction.drawFromCumulative(p, 0.5);
        check("Kumulierte Verteilung folgt strikt der Einfuegereihenfolge der uebergebenen Map",
                chosen.isEmpty());
    }

    static void testDrawIsDeterministicForSameSeed() {
        long seed = 4711L;
        double draw1 = new Random(seed).nextDouble();
        double draw2 = new Random(seed).nextDouble();
        check("Derselbe Seed liefert dieselbe Ziehung (Voraussetzung fuer Basis-/AVM-Vergleichbarkeit)",
                near(draw1, draw2));
    }

    static void testEmptyChoiceSetIsRejected() {
        boolean threw;
        try {
            behaviourUtilityFunction.softmax(Map.of(), 1.0);
            threw = false;
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("Leeres Choice-Set wird abgelehnt", threw);
    }

    // ------------------------------------------------------------ Fixtures

    /** ascNull + zwei Modi mit gegebenen Nutzenwerten, Reihenfolge wie im Inserter (Null zuerst). */
    static Map<Optional<alternatives>, Double> choiceSet(double ascNull, double vCa, double vPt) {
        Map<Optional<alternatives>, Double> v = new LinkedHashMap<>();
        v.put(Optional.empty(), ascNull);
        v.put(Optional.of(alternatives.CA), vCa);
        v.put(Optional.of(alternatives.PT), vPt);
        return v;
    }

    // --------------------------------------------------------- Mini-Runner

    static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  OK    " + name);
        } else {
            failed++;
            System.out.println("  FEHLT " + name);
        }
    }

    static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }
}
