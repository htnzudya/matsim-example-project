package org.matsim.project;

import org.matsim.project.model.segmentClassifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unit-Tests des Naive-Bayes-Segmentklassifikators (segmentClassifier) - ohne
 * MATSim, ohne Simulation. Prueft die aus der Aufgabenstellung geforderten
 * Eigenschaften:
 *   - realisierte Segmentanteile konvergieren durch die Alpha-Kalibrierung
 *     (SCHRITT 5) gegen die publizierten pi-Anteile.
 *   - die Ziehung ist eine echte multinomiale Ziehung, KEIN argmax (identische
 *     Agenten landen nicht alle im selben Segment).
 *   - alle Segmente werden bei ausreichender Populationsgroesse besetzt.
 *   - die Zuordnung ist deterministisch (fester Seed, unabhaengig von der
 *     Eingabereihenfolge).
 *
 * Bewusst ohne JUnit geschrieben, analog zu {@link InducedDemandTripTest}.
 */
public class SegmentClassifierTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testCalibrationRecoversTargetShares();
        testAllSegmentsOccupied();
        testNotArgmax();
        testDeterministicRegardlessOfInputOrder();
        testMeanMaxPosteriorBetweenZeroAndOne();

        System.out.println();
        System.out.println("Bestanden: " + passed + " / Fehlgeschlagen: " + failed);
        if (failed > 0) {
            throw new AssertionError("Es sind Tests fehlgeschlagen.");
        }
    }

    // ---------------------------------------------------------------- Tests

    static void testCalibrationRecoversTargetShares() {
        List<segmentClassifier.clusterCenter> clusters = threeAgeClusters();
        List<segmentClassifier.agentCovariates> agents = uniformAgePopulation(6000);

        segmentClassifier.result result = segmentClassifier.classify(agents, clusters, segmentClassifier.weights.defaults());

        for (segmentClassifier.clusterCenter c : clusters) {
            double realized = result.validationReport().realizedShare().get(c.segmentId());
            double target = c.pi();
            check("realizedShare(" + c.segmentId() + ") close to pi=" + target + ", got " + realized,
                    Math.abs(realized - target) < 0.02);
        }
    }

    static void testAllSegmentsOccupied() {
        List<segmentClassifier.clusterCenter> clusters = threeAgeClusters();
        List<segmentClassifier.agentCovariates> agents = uniformAgePopulation(6000);

        segmentClassifier.result result = segmentClassifier.classify(agents, clusters, segmentClassifier.weights.defaults());

        check("allSegmentsOccupied", result.validationReport().allSegmentsOccupied());
    }

    static void testNotArgmax() {
        List<segmentClassifier.clusterCenter> clusters = threeAgeClusters();

        // 200 Agenten mit IDENTISCHEN Rohwerten, exakt zwischen Cluster A (mu=30)
        // und B (mu=50) - argmax wuerde ALLE 200 in dasselbe Segment stecken.
        List<segmentClassifier.agentCovariates> agents = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            agents.add(new segmentClassifier.agentCovariates("p" + i, 40.0, i % 2 == 0, 2.0, 5.0, -113.0, 2));
        }

        segmentClassifier.result result = segmentClassifier.classify(agents, clusters, segmentClassifier.weights.defaults());

        Set<String> distinctSegments = new HashSet<>();
        for (segmentClassifier.assignment a : result.assignments()) {
            distinctSegments.add(a.segmentId());
        }
        check("identische Agenten landen NICHT alle im selben Segment (argmax waere verboten), distinct=" + distinctSegments,
                distinctSegments.size() >= 2);
    }

    static void testDeterministicRegardlessOfInputOrder() {
        List<segmentClassifier.clusterCenter> clusters = threeAgeClusters();
        List<segmentClassifier.agentCovariates> agents = uniformAgePopulation(300);

        List<segmentClassifier.agentCovariates> shuffled = new ArrayList<>(agents);
        java.util.Collections.reverse(shuffled);

        segmentClassifier.result resultA = segmentClassifier.classify(agents, clusters, segmentClassifier.weights.defaults());
        segmentClassifier.result resultB = segmentClassifier.classify(shuffled, clusters, segmentClassifier.weights.defaults());

        Map<String, String> segmentsA = new java.util.HashMap<>();
        for (segmentClassifier.assignment a : resultA.assignments()) {
            segmentsA.put(a.personId(), a.segmentId());
        }
        boolean allMatch = true;
        for (segmentClassifier.assignment b : resultB.assignments()) {
            if (!b.segmentId().equals(segmentsA.get(b.personId()))) {
                allMatch = false;
                break;
            }
        }
        check("Zuordnung ist unabhaengig von der Eingabereihenfolge (fester Seed)", allMatch);
    }

    static void testMeanMaxPosteriorBetweenZeroAndOne() {
        List<segmentClassifier.clusterCenter> clusters = threeAgeClusters();
        List<segmentClassifier.agentCovariates> agents = uniformAgePopulation(1000);

        segmentClassifier.result result = segmentClassifier.classify(agents, clusters, segmentClassifier.weights.defaults());
        double meanMaxPosterior = result.validationReport().meanMaxPosterior();

        check("meanMaxPosterior in (1/3, 1]: " + meanMaxPosterior,
                meanMaxPosterior > 1.0 / clusters.size() && meanMaxPosterior <= 1.0);
    }

    // ---------------------------------------------------------------- Fixtures

    /**
     * Drei Cluster, die sich NUR im Alter unterscheiden (alle anderen Achsen
     * identisch parametrisiert -> deren Log-Likelihood-Beitrag ist fuer jeden
     * Cluster gleich und kuerzt sich im Softmax heraus). pi bewusst NICHT
     * gleichverteilt (0.5/0.3/0.2), um die Alpha-Kalibrierung echt zu fordern.
     */
    private static List<segmentClassifier.clusterCenter> threeAgeClusters() {
        List<segmentClassifier.clusterCenter> clusters = new ArrayList<>();
        clusters.add(new segmentClassifier.clusterCenter("A", "A", 0.5, 30.0, 8.0, 0.5, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0));
        clusters.add(new segmentClassifier.clusterCenter("B", "B", 0.3, 50.0, 8.0, 0.5, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0));
        clusters.add(new segmentClassifier.clusterCenter("C", "C", 0.2, 70.0, 8.0, 0.5, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0));
        return clusters;
    }

    /** n Agenten mit ueber [20,80] gleichmaessig verteiltem Alter - bewusst NICHT im pi-Mischverhaeltnis der Cluster. */
    private static List<segmentClassifier.agentCovariates> uniformAgePopulation(int n) {
        List<segmentClassifier.agentCovariates> agents = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double age = 20.0 + (i % 121) * 0.5; // deckt [20,80] ab
            agents.add(new segmentClassifier.agentCovariates("p" + i, age, i % 2 == 0, 2.0, 5.0, -113.0, i % 3));
        }
        return agents;
    }

    // ---------------------------------------------------------------- Hilfsfunktion

    private static void check(String description, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("OK   " + description);
        } else {
            failed++;
            System.out.println("FAIL " + description);
        }
    }
}
