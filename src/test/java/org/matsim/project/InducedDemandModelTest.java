package org.matsim.project;

import org.matsim.project.model.*;

import java.util.*;

/**
 * Unit-Tests des induzierte-Nachfrage-Modells - ohne MATSim, ohne Simulation.
 *
 * Prueft die Formeln
 *   Lambda_n^base = ln SUM_{i in {CA,PT}} exp(V_ni)
 *   Lambda_n^AVM  = ln SUM_{i in verfuegbares Choice-Set} exp(V_ni)
 *   VKM_n^ind     = VKM_n^base * exp(theta * DeltaLambda_n)
 *   T_n^ind       = T_n^base   * exp(tripShare       * theta * DeltaLambda_n)
 *   D_n^ind       = D_n^base   * exp((1 - tripShare) * theta * DeltaLambda_n)
 * gegen von Hand nachgerechnete Werte, ihre qualitativen Eigenschaften
 * (mehr/bessere Alternativen -> hoeherer Logsum -> mehr induzierte Nachfrage)
 * sowie insbesondere die Konsistenzeigenschaft T_ind * D_ind = VKM_ind (KEINE
 * Doppelzaehlung von Wegeanzahl- und Distanzeffekt).
 *
 * Bewusst ohne JUnit geschrieben, analog zu {@link UtilityFunctionTest}.
 */
public class InducedDemandModelTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testDeltaLogsumZeroWhenAvmEqualsBaseline();
        testMoreAlternativesIncreaseLogsum();
        testComputeMatchesClosedFormFormula();
        testTripsTimesDistanceEqualsPersonKm();
        testTripShareOneGivesPureGenerationEffect();
        testTripShareZeroGivesPureDestinationEffect();
        testZeroThetaGivesNoInducedDemand();
        testNegativeDeltaLogsumReducesDemand();
        testInvalidTripShareIsRejected();

        System.out.println();
        System.out.println("Bestanden: " + passed + " / Fehlgeschlagen: " + failed);
        if (failed > 0) {
            throw new AssertionError("Es sind Tests fehlgeschlagen.");
        }
    }

    // ---------------------------------------------------------------- Tests

    static void testDeltaLogsumZeroWhenAvmEqualsBaseline() {
        inducedDemandModel model = new inducedDemandModel(new behaviourUtilityFunction());
        agentProfile a = neutralAgent();

        Map<alternatives, modeParams> params = baseParams();
        Map<alternatives, TripContext> trips = baseTrips();

        double lambdaBase = model.baselineLogsum(a, params, trips, null);
        double lambdaAvm = model.avmLogsum(a, params, trips, null, List.of(alternatives.CA, alternatives.PT));

        check("Gleiches Choice-Set liefert DeltaLambda = 0",
                near(model.deltaLogsum(lambdaAvm, lambdaBase), 0.0));
    }

    static void testMoreAlternativesIncreaseLogsum() {
        inducedDemandModel model = new inducedDemandModel(new behaviourUtilityFunction());
        agentProfile a = neutralAgent();

        Map<alternatives, modeParams> params = baseParams();
        Map<alternatives, TripContext> trips = baseTrips();

        double lambdaBase = model.baselineLogsum(a, params, trips, null);
        double lambdaAvm = model.avmLogsum(a, params, trips, null,
                List.of(alternatives.CA, alternatives.PT, alternatives.AV));

        check("Ein zusaetzlicher Modus (AV) erhoeht den Logsum gegenueber der heutigen Welt",
                lambdaAvm > lambdaBase);
    }

    static void testComputeMatchesClosedFormFormula() {
        double theta = 0.20;
        double tripShare = 0.7;
        inducedDemandModel model = new inducedDemandModel(new behaviourUtilityFunction(), theta, tripShare);
        agentProfile a = neutralAgent();

        Map<alternatives, modeParams> params = baseParams();
        Map<alternatives, TripContext> trips = baseTrips();
        double baseTripCount = 3.0;
        double baseDistanceKm = 12.0;

        inducedDemandModel.Result result = model.compute(
                a, params, trips, null, List.of(alternatives.CA, alternatives.PT, alternatives.AV),
                baseTripCount, baseDistanceKm);

        double expectedDelta = result.lambdaAvm() - result.lambdaBase();
        double expectedInducedTrips = baseTripCount * Math.exp(tripShare * theta * expectedDelta);
        double expectedInducedDistance = baseDistanceKm * Math.exp((1 - tripShare) * theta * expectedDelta);
        double expectedInducedPersonKm = baseTripCount * baseDistanceKm * Math.exp(theta * expectedDelta);

        check("DeltaLambda entspricht Lambda_AVM - Lambda_base",
                near(result.deltaLogsum(), expectedDelta));
        check("T_n^ind entspricht T_n^base * exp(tripShare * theta * DeltaLambda_n)",
                near(result.inducedTrips(), expectedInducedTrips));
        check("D_n^ind entspricht D_n^base * exp((1-tripShare) * theta * DeltaLambda_n)",
                near(result.inducedDistanceKm(), expectedInducedDistance));
        check("VKM_n^ind entspricht VKM_n^base * exp(theta * DeltaLambda_n)",
                near(result.inducedPersonKm(), expectedInducedPersonKm));
        check("Additiver Wegezuwachs entspricht T_n^ind - T_n^base",
                near(result.additionalTrips(), expectedInducedTrips - baseTripCount));
        check("Additiver Distanzzuwachs entspricht D_n^ind - D_n^base",
                near(result.additionalDistanceKm(), expectedInducedDistance - baseDistanceKm));
    }

    static void testTripsTimesDistanceEqualsPersonKm() {
        // Kernaussage der Umstellung: T_ind * D_ind darf NICHT groesser als
        // VKM_ind sein (keine unabhaengige Doppelanwendung von DeltaLambda_n).
        inducedDemandModel model = new inducedDemandModel(new behaviourUtilityFunction(), 0.30, 0.35);

        double baseTripCount = 5.0;
        double baseDistanceKm = 12.0;
        double deltaLogsum = 1.6;

        double tInd = model.inducedTrips(baseTripCount, deltaLogsum);
        double dInd = model.inducedDistanceKm(baseDistanceKm, deltaLogsum);
        double vkmInd = model.inducedPersonKm(baseTripCount, baseDistanceKm, deltaLogsum);

        check("T_ind * D_ind entspricht exakt VKM_ind (keine Doppelzaehlung)",
                near(tInd * dInd, vkmInd));
    }

    static void testTripShareOneGivesPureGenerationEffect() {
        inducedDemandModel model = new inducedDemandModel(new behaviourUtilityFunction(), 0.30, 1.0);

        double baseTripCount = 5.0;
        double baseDistanceKm = 12.0;
        double deltaLogsum = 0.8;

        check("tripShare=1: die Distanz bleibt unveraendert (reiner Generierungseffekt)",
                near(model.inducedDistanceKm(baseDistanceKm, deltaLogsum), baseDistanceKm));
        check("tripShare=1: die Wegeanzahl traegt das gesamte VKM-Wachstum",
                near(model.inducedTrips(baseTripCount, deltaLogsum),
                        model.inducedPersonKm(baseTripCount, baseDistanceKm, deltaLogsum) / baseDistanceKm));
    }

    static void testTripShareZeroGivesPureDestinationEffect() {
        inducedDemandModel model = new inducedDemandModel(new behaviourUtilityFunction(), 0.30, 0.0);

        double baseTripCount = 5.0;
        double baseDistanceKm = 12.0;
        double deltaLogsum = 0.8;

        check("tripShare=0: die Wegeanzahl bleibt unveraendert (reiner Zielwahleffekt)",
                near(model.inducedTrips(baseTripCount, deltaLogsum), baseTripCount));
        check("tripShare=0: die Distanz traegt das gesamte VKM-Wachstum",
                near(model.inducedDistanceKm(baseDistanceKm, deltaLogsum),
                        model.inducedPersonKm(baseTripCount, baseDistanceKm, deltaLogsum) / baseTripCount));
    }

    static void testZeroThetaGivesNoInducedDemand() {
        inducedDemandModel model = new inducedDemandModel(new behaviourUtilityFunction(), 0.0, 0.5);

        double baseTripCount = 4.0;
        double baseDistanceKm = 9.0;

        check("theta = 0 laesst die Wegeanzahl unveraendert, unabhaengig von DeltaLambda",
                near(model.inducedTrips(baseTripCount, 1.5), baseTripCount));
        check("theta = 0 laesst die Distanz unveraendert, unabhaengig von DeltaLambda",
                near(model.inducedDistanceKm(baseDistanceKm, 1.5), baseDistanceKm));
        check("theta = 0 laesst die Personenkilometer unveraendert, unabhaengig von DeltaLambda",
                near(model.inducedPersonKm(baseTripCount, baseDistanceKm, 1.5), baseTripCount * baseDistanceKm));
    }

    static void testNegativeDeltaLogsumReducesDemand() {
        inducedDemandModel model = new inducedDemandModel(new behaviourUtilityFunction(),
                inducedDemandModel.THETA_PLACEHOLDER, inducedDemandModel.TRIP_SHARE_PLACEHOLDER);

        double baseTripCount = 5.0;
        double baseDistanceKm = 12.0;

        check("Negatives DeltaLambda (schlechteres Choice-Set) senkt die Wegeanzahl",
                model.inducedTrips(baseTripCount, -0.5) < baseTripCount);
        check("Negatives DeltaLambda (schlechteres Choice-Set) senkt die Distanz",
                model.inducedDistanceKm(baseDistanceKm, -0.5) < baseDistanceKm);
        check("Negatives DeltaLambda (schlechteres Choice-Set) senkt die Personenkilometer",
                model.inducedPersonKm(baseTripCount, baseDistanceKm, -0.5) < baseTripCount * baseDistanceKm);
    }

    static void testInvalidTripShareIsRejected() {
        boolean threw;
        try {
            new inducedDemandModel(new behaviourUtilityFunction(), 0.2, 1.5);
            threw = false;
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("tripShare ausserhalb [0,1] wird abgelehnt", threw);
    }

    // ------------------------------------------------------------ Fixtures

    static agentProfile neutralAgent() {
        return new agentProfile("neutral", Map.of());
    }

    static Map<alternatives, modeParams> baseParams() {
        Map<alternatives, modeParams> params = new EnumMap<>(alternatives.class);
        params.put(alternatives.CA, new modeParams(alternatives.CA,
                0.0, 0.0, -6.0, 0.0, 0.0, 0.0, -0.20, 0.0, 0.0, Map.of(), Map.of()));
        params.put(alternatives.PT, new modeParams(alternatives.PT,
                -1.20, 0.0, -7.50, 0.0, 0.0, 0.0, -0.25, 0.0, 0.0, Map.of(), Map.of()));
        params.put(alternatives.AV, new modeParams(alternatives.AV,
                -0.50, 0.0, -4.50, 0.0, 0.0, 0.0, -0.20, 0.0, 0.0, Map.of(), Map.of()));
        return params;
    }

    static Map<alternatives, TripContext> baseTrips() {
        Map<alternatives, TripContext> trips = new EnumMap<>(alternatives.class);
        trips.put(alternatives.CA, new TripContext(0.4, 0.0, 4.0, 10));
        trips.put(alternatives.PT, new TripContext(0.6, 0.1, 3.0, 10));
        trips.put(alternatives.AV, new TripContext(0.4, 0.0, 4.0, 10));
        return trips;
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
