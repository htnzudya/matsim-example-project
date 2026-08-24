package org.matsim.project;

import org.matsim.project.model.*;

import java.util.*;

/**
 * Unit-Tests der Nutzenfunktion - ohne MATSim, ohne Simulation.
 *
 * Diese Tests sind der Funktionsnachweis des Add-ons: Sie zeigen, dass der
 * Mechanismus korrekt rechnet, ohne dass ein Szenario gelaufen sein muss.
 *
 * Bewusst ohne JUnit geschrieben, damit die Datei ohne zusaetzliche
 * Dependencies laeuft. In IntelliJ kann sie 1:1 nach JUnit 5 uebersetzt
 * werden (@Test statt der check-Aufrufe).
 */
public class UtilityFunctionTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        testHigherCostLowersUtility();
        testHigherTimeLowersUtility();
        testDeltaCurrentlyDisabled();
        testSegmentInfluencesAvUtility();
        testProbabilitiesSumToOne();
        testChoiceSetRestriction();
        testZeroSdGivesDeterministicResult();
        testIncomeTierThresholds();
        testDrawSelectsIncomeTierBetaCost();

        System.out.println();
        System.out.println("Bestanden: " + passed + " / Fehlgeschlagen: " + failed);
        if (failed > 0) {
            throw new AssertionError("Es sind Tests fehlgeschlagen.");
        }
    }

    // ---------------------------------------------------------------- Tests

    static void testHigherCostLowersUtility() {
        behaviourUtilityFunction f = new behaviourUtilityFunction();
        agentProfile a = neutralAgent();
        modeParams p = avParams();

        double cheap = f.utility(a, p, new TripContext(0.5, 0.0, 3.0, 10), null);
        double expensive = f.utility(a, p, new TripContext(0.5, 0.0, 8.0, 10), null);

        check("Hoehere Kosten senken den Nutzen", expensive < cheap);
    }

    static void testHigherTimeLowersUtility() {
        behaviourUtilityFunction f = new behaviourUtilityFunction();
        agentProfile a = neutralAgent();
        modeParams p = avParams();

        double fast = f.utility(a, p, new TripContext(0.3, 0.0, 4.0, 10), null);
        double slow = f.utility(a, p, new TripContext(0.9, 0.0, 4.0, 10), null);

        check("Laengere Reisezeit senkt den Nutzen", slow < fast);
    }

    /**
     * delta (Habit) ist in behaviourUtilityFunction.utility(...) derzeit bewusst
     * auskommentiert (Auftraggeber-Vorgabe: erste Laeufe ohne Habit-Effekt) - dieser
     * Test dokumentiert genau das (previousMode hat aktuell KEINE Wirkung), statt
     * die urspruengliche "delta wirkt"-Erwartung stillschweigend fehlschlagen zu
     * lassen. Sobald der Term dort wieder einkommentiert wird, muss dieser Test
     * zurueck auf die auskommentierte Variante darunter.
     */
    static void testDeltaCurrentlyDisabled() {
        behaviourUtilityFunction f = new behaviourUtilityFunction();
        agentProfile a = neutralAgent();
        modeParams p = avParams();
        TripContext t = new TripContext(0.5, 0.0, 4.0, 10);

        double withHabit = f.utility(a, p, t, alternatives.AV);
        double withoutHabit = f.utility(a, p, t, alternatives.CA);

        check("delta ist aktuell auskommentiert - previousMode aendert den Nutzen nicht",
                near(withHabit, withoutHabit));

        // Urspruengliche Erwartung bei aktivem delta-Term - siehe Klassen-Javadoc oben:
        // check("Traegheit erhoeht den Nutzen des zuletzt gewaehlten Modus",
        //         withHabit > withoutHabit);
        // check("Traegheitsbonus entspricht delta",
        //         near(withHabit - withoutHabit, p.getDelta(alternatives.AV)));
    }

    static void testSegmentInfluencesAvUtility() {
        behaviourUtilityFunction f = new behaviourUtilityFunction();
        modeParams p = avParams();
        TripContext t = new TripContext(0.5, 0.0, 4.0, 10);

        agentProfile techSavvy = new agentProfile("affin", 0.0,
                Map.of("techAffinity", 1.5, "attitude", 1.0, "perceivedRisk", -0.5));
        agentProfile skeptic = new agentProfile("skeptisch", 0.0,
                Map.of("techAffinity", -1.5, "attitude", -1.0, "perceivedRisk", 1.5));

        double vTechSavvy = f.utility(techSavvy, p, t, null);
        double vSkeptic = f.utility(skeptic, p, t, null);

        check("Technikaffines Segment zieht mehr Nutzen aus AV als das skeptische",
                vTechSavvy > vSkeptic);
    }

    static void testProbabilitiesSumToOne() {
        behaviourUtilityFunction f = new behaviourUtilityFunction();
        Map<alternatives, Double> v = new EnumMap<>(alternatives.class);
        v.put(alternatives.CA, -2.0);
        v.put(alternatives.AV, -1.5);
        v.put(alternatives.PT, -6.0);
        v.put(alternatives.PSAV, -3.5);
        v.put(alternatives.SSAV, -4.0);

        double sum = f.probabilities(v).values().stream()
                .mapToDouble(Double::doubleValue).sum();

        check("Wahlwahrscheinlichkeiten summieren auf 1", near(sum, 1.0));
    }

    static void testChoiceSetRestriction() {
        behaviourUtilityFunction f = new behaviourUtilityFunction();
        agentProfile a = neutralAgent();

        Map<alternatives, modeParams> params = new EnumMap<>(alternatives.class);
        params.put(alternatives.AV, avParams());
        params.put(alternatives.PT, ptParams());

        Map<alternatives, TripContext> trips = new EnumMap<>(alternatives.class);
        trips.put(alternatives.AV, new TripContext(0.5, 0.0, 4.0, 10));
        trips.put(alternatives.PT, new TripContext(0.5, 0.2, 3.0, 10));

        // Agent ohne Pkw-Verfuegbarkeit: CA ist nicht im Choice-Set
        Map<alternatives, Double> v = f.utilityAll(
                a, params, trips, null, List.of(alternatives.AV, alternatives.PT));

        check("Nicht verfuegbare Alternativen erscheinen nicht im Choice-Set",
                v.size() == 2 && !v.containsKey(alternatives.CA));
    }

    static void testZeroSdGivesDeterministicResult() {
        modeParams withoutSd = new modeParams(alternatives.AV,
                -0.5, 0.0, -4.5, 0.0, 0.0, 0.0, -0.2, 0.0, 0.0, Map.of("AV", 0.4), Map.of());

        modeParams z1 = withoutSd.draw(new Random(1));
        modeParams z2 = withoutSd.draw(new Random(999));

        check("Bei SD=0 liefern alle Ziehungen identische Koeffizienten (MNL-Grenzfall)",
                near(z1.getAsc(), z2.getAsc()) && near(z1.getBetaInVehicleTime(), z2.getBetaInVehicleTime()));
    }

    static void testIncomeTierThresholds() {
        check("hhIncome=3 (Grenzfall) ist NIEDRIG",
                incomeTier.fromHhIncome(3, 3, 8) == incomeTier.NIEDRIG);
        check("hhIncome=4 ist MITTEL",
                incomeTier.fromHhIncome(4, 3, 8) == incomeTier.MITTEL);
        check("hhIncome=7 ist MITTEL",
                incomeTier.fromHhIncome(7, 3, 8) == incomeTier.MITTEL);
        check("hhIncome=8 (Grenzfall) ist HOCH",
                incomeTier.fromHhIncome(8, 3, 8) == incomeTier.HOCH);
    }

    static void testDrawSelectsIncomeTierBetaCost() {
        // SD=0 fuer alle drei Stufen, damit draw(...) den Mittelwert unveraendert durchreicht.
        modeParams p = new modeParams(alternatives.AV,
                -0.5, 0.0, -4.5, 0.0, 0.0, 0.0,
                -0.806, 0.0, -0.991, 0.0, -0.561, 0.0,
                0.2, modeParams.NO_TICKET_OVERRIDE,
                Map.of(), Map.of("AV", 0.4), Map.of());

        double niedrig = p.draw(new Random(1), incomeTier.NIEDRIG).getBetaCost();
        double mittel = p.draw(new Random(1), incomeTier.MITTEL).getBetaCost();
        double hoch = p.draw(new Random(1), incomeTier.HOCH).getBetaCost();

        check("draw(NIEDRIG) liefert betaCostNiedrig", near(niedrig, -0.991));
        check("draw(MITTEL) liefert betaCost", near(mittel, -0.806));
        check("draw(HOCH) liefert betaCostHoch", near(hoch, -0.561));
        check("Niedriges Einkommen ist preissensitiver (betragsmaessig groesser) als hohes",
                Math.abs(niedrig) > Math.abs(hoch));
        check("draw(Random) ohne incomeTier verhaelt sich wie draw(Random, MITTEL)",
                near(p.draw(new Random(1)).getBetaCost(), mittel));
    }

    // ------------------------------------------------------------ Fixtures

    static agentProfile neutralAgent() {
        return new agentProfile("neutral", Map.of());
    }

    static modeParams avParams() {
        return new modeParams(alternatives.AV,
                -0.50, 0.0,
                -4.50, 0.0,
                0.0, 0.0,
                -0.20, 0.0,
                0.0,
                Map.of("AV", 0.40),
                Map.of("techAffinity", 0.45,
                        "attitude", 0.70,
                        "perceivedRisk", -0.55));
    }

    static modeParams ptParams() {
        return new modeParams(alternatives.PT,
                -1.20, 0.0,
                -7.50, 0.0,
                0.0, 0.0,
                -0.25, 0.0,
                0.0,
                Map.of("PT", 0.60),
                Map.of("ptAffinity", 0.50));
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