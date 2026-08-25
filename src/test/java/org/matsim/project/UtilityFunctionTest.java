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
        testDeltaAppliesOnlyForSamePreviousMode();
        testSegmentInfluencesAvUtility();
        testProbabilitiesSumToOne();
        testChoiceSetRestriction();
        testZeroSdGivesDeterministicResult();
        testIncomeTierThresholds();
        testDrawSelectsIncomeTierBetaCost();
        testHabitTriangularDistributionDisabledByDefault();
        testHabitTriangularDistributionRange();
        testHabitTriangularDistributionMean();
        testHabitTriangularDistributionLeavesZeroEntriesUntouched();

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
     * delta (Habit) ist wieder aktiv (siehe behaviourUtilityFunction-Klassen-
     * Javadoc): nur noch der Diagonaleintrag (Modus -> sich selbst), kein
     * Bonus bei unterschiedlichem Vor-/Zielmodus mehr. avParams() traegt
     * genau das (deltaByPreviousMode = {"AV": 0.40}, siehe Fixture unten).
     */
    static void testDeltaAppliesOnlyForSamePreviousMode() {
        behaviourUtilityFunction f = new behaviourUtilityFunction();
        agentProfile a = neutralAgent();
        modeParams p = avParams();
        TripContext t = new TripContext(0.5, 0.0, 4.0, 10);

        double withHabit = f.utility(a, p, t, alternatives.AV);
        double withoutHabit = f.utility(a, p, t, alternatives.CA);

        check("Traegheit erhoeht den Nutzen, wenn der vorherige Modus mit dem bewerteten identisch war",
                withHabit > withoutHabit);
        check("Traegheitsbonus entspricht delta (Diagonaleintrag)",
                near(withHabit - withoutHabit, p.getDelta(alternatives.AV)));
        check("Kein Bonus, wenn der vorherige Modus ein ANDERER war (kein Diagonaleintrag)",
                near(withoutHabit, f.utility(a, p, t, null)));
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

    static void testHabitTriangularDistributionDisabledByDefault() {
        // deltaTriangularHalfWidthRight nicht angegeben (bestehender Konstruktor) -> 0.0 Default.
        modeParams p = new modeParams(alternatives.AV,
                -0.5, 0.0, -4.5, 0.0, 0.0, 0.0, -0.2, 0.0, 0.0,
                Map.of("AV", 0.831), Map.of());

        boolean allIdentical = true;
        double first = p.draw(new Random(1)).getDelta(alternatives.AV);
        for (long seed = 2; seed <= 20; seed++) {
            if (!near(first, p.draw(new Random(seed)).getDelta(alternatives.AV))) {
                allIdentical = false;
                break;
            }
        }
        check("Ohne konfigurierte Dreiecksbreite bleibt der Gewohnheitsbonus deterministisch",
                allIdentical && near(first, 0.831));
    }

    static void testHabitTriangularDistributionRange() {
        modeParams p = habitParams(0.831, 0.8);

        boolean allInRange = true;
        for (long seed = 1; seed <= 200; seed++) {
            double drawn = p.draw(new Random(seed)).getDelta(alternatives.AV);
            if (drawn < 0.0 || drawn > 1.631 + 1e-9) {
                allInRange = false;
                break;
            }
        }
        check("Dreiecksverteilter Gewohnheitsbonus liegt immer in [0, modus+breite] (hier [0, 1.631])",
                allInRange);
    }

    static void testHabitTriangularDistributionMean() {
        modeParams p = habitParams(0.831, 0.8);

        double sum = 0.0;
        int n = 20000;
        for (long seed = 1; seed <= n; seed++) {
            sum += p.draw(new Random(seed)).getDelta(alternatives.AV);
        }
        double mean = sum / n;
        double expectedMean = (0.0 + 0.831 + 1.631) / 3.0;
        check("Mittelwert vieler Ziehungen naehert sich dem theoretischen Dreiecksverteilungs-Mittel (min+modus+max)/3 an",
                Math.abs(mean - expectedMean) < 0.02);
    }

    static void testHabitTriangularDistributionLeavesZeroEntriesUntouched() {
        modeParams p = new modeParams(alternatives.AV,
                -0.5, 0.0, -4.5, 0.0, 0.0, 0.0,
                -0.2, 0.0, -0.2, 0.0, -0.2, 0.0,
                0.0, modeParams.NO_TICKET_OVERRIDE,
                Map.of("AV", 0.831, "CA", 0.0), Map.of(), Map.of(), 0.8);

        boolean caAlwaysZero = true;
        for (long seed = 1; seed <= 50; seed++) {
            if (p.draw(new Random(seed)).getDelta(alternatives.CA) != 0.0) {
                caAlwaysZero = false;
                break;
            }
        }
        check("Eintraege mit Wert 0.0 (kein Diagonaltreffer) bleiben trotz konfigurierter Dreiecksbreite exakt 0.0",
                caAlwaysZero);
    }

    // ------------------------------------------------------------ Fixtures

    static modeParams habitParams(double mode, double triangularHalfWidthRight) {
        return new modeParams(alternatives.AV,
                -0.5, 0.0, -4.5, 0.0, 0.0, 0.0,
                -0.2, 0.0, -0.2, 0.0, -0.2, 0.0,
                0.0, modeParams.NO_TICKET_OVERRIDE,
                Map.of("AV", mode), Map.of(), Map.of(), triangularHalfWidthRight);
    }

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