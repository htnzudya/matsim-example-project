package org.matsim.project;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigReader;
import org.matsim.core.config.ConfigUtils;
import org.matsim.project.config.behaviourConfigGroup;
import org.matsim.project.model.*;

import java.util.*;

/**
 * DEMO / TEST-HARNESS - gehoert NICHT zum Add-on, sondern dient nur dazu,
 * die Nutzenfunktion ausserhalb einer vollen MATSim-Simulation vorzufuehren.
 *
 * Segmente und modeParams kommen jetzt aus scenarios/testszenario/config.xml -
 * denselben echten Koeffizienten, die auch der equil-Testlauf nutzt. Kein
 * einziger Zahlenwert dafuer hier im Code.
 *
 * ACHTUNG: Der Beispielweg (exampleTrip()) ist weiterhin illustrativ - es gibt
 * hier kein echtes Netzwerk/Routing, nur eine plausible 12-km-Wegannahme, um
 * die Nutzenfunktion ueberhaupt auswerten zu koennen.
 */
public class demo {

    // ---------- Ein Beispielweg: 12 km zur Arbeit (weiterhin illustrativ, kein Routing) ----------

    private static Map<alternatives, TripContext> exampleTrip() {
        Map<alternatives, TripContext> t = new EnumMap<>(alternatives.class);
        t.put(alternatives.CA,  new TripContext(0.40, 0.00, 3.60, 12.0));   // 24 min, 3.60 EUR
        t.put(alternatives.AV,  new TripContext(0.42, 0.00, 4.20, 12.0));   // 25 min, 4.20 EUR
        t.put(alternatives.PT,   new TripContext(0.50, 0.15, 2.90, 12.0));  // 30 min + 9 min Wartezeit, 2.90 EUR
        t.put(alternatives.PSAV, new TripContext(0.45, 0.08, 4.50, 12.0));  // 27 min (Pooling-Umweg) + 5 min On-Demand-Wartezeit, 4.50 EUR
        t.put(alternatives.SSAV, new TripContext(0.38, 0.10, 3.80, 12.0));  // 23 min (feste Route) + 6 min Umlauf-Wartezeit, 3.80 EUR
        return t;
    }

    public static void main(String[] args) {

        Config config = ConfigUtils.createConfig();
        new ConfigReader(config).readFile("scenarios/testszenario/config.xml");
        behaviourConfigGroup cfg = behaviourConfigGroup.getOrCreate(config);

        behaviourUtilityFunction f = new behaviourUtilityFunction(cfg.getScaleParameter());
        Map<alternatives, modeParams> params = cfg.buildModeParams();
        Map<String, agentProfile> segments = cfg.buildSegments();
        Map<alternatives, TripContext> trip = exampleTrip();
        List<alternatives> choiceSet = List.of(
                alternatives.CA, alternatives.AV, alternatives.PT, alternatives.PSAV, alternatives.SSAV);

        System.out.println("=".repeat(78));
        System.out.println("NUTZENFUNKTION - echte Koeffizienten aus testszenario/config.xml");
        System.out.println("Weg: 12 km (illustrativ, kein Routing). Vorheriger Modus (Gewohnheit): CA");
        System.out.println("=".repeat(78));

        for (agentProfile profile : segments.values()) {

            Map<alternatives, Double> v =
                    f.utilityAll(profile, params, trip, alternatives.CA, choiceSet);
            Map<alternatives, Double> p = f.probabilities(v);

            System.out.println();
            System.out.println("Segment: " + profile.getSegmentId()
                    + " (Anteil an der Grundgesamtheit: " + String.format("%.2f", 100 * profile.getProbability()) + " %)");
            System.out.printf("  %-5s %10s %12s%n", "Modus", "V(i,j)", "P(j)");
            System.out.println("  " + "-".repeat(29));
            for (alternatives j : choiceSet) {
                System.out.printf("  %-5s %10.3f %11.1f %%%n", j, v.get(j), 100.0 * p.get(j));
            }
            System.out.printf("  Logsum (erw. Maximalnutzen): %.3f%n", f.logsum(v));
        }

        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println("PLAUSIBILITAETSPRUEFUNG - Value of Travel Time (EUR/Std)");
        System.out.println("=".repeat(78));
        for (alternatives j : choiceSet) {
            System.out.printf("  %-5s InVehicle-VOT = %6.2f EUR/Std%n", j, params.get(j).getInVehicleVot());
        }

        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println("MIXED LOGIT - 5 gezogene Agenten aus demselben Segment");
        System.out.println("(gleiche Verteilung, unterschiedliche individuelle Praeferenzen)");
        System.out.println("=".repeat(78));

        String demoSegmentId = "pragmatischer_urbanist";
        agentProfile profile = segments.get(demoSegmentId);
        Random rnd = new Random(42);
        System.out.println("Segment: " + demoSegmentId);
        StringBuilder header = new StringBuilder(String.format("  %-8s", "Agent"));
        for (alternatives j : choiceSet) {
            header.append(String.format(" %8s", "P(" + j + ")"));
        }
        System.out.println(header);
        System.out.println("  " + "-".repeat(9 + 9 * choiceSet.size()));
        for (int i = 1; i <= 5; i++) {
            Map<alternatives, modeParams> drawn = new EnumMap<>(alternatives.class);
            for (Map.Entry<alternatives, modeParams> e : params.entrySet()) {
                drawn.put(e.getKey(), e.getValue().draw(rnd));
            }
            Map<alternatives, Double> v =
                    f.utilityAll(profile, drawn, trip, alternatives.CA, choiceSet);
            Map<alternatives, Double> p = f.probabilities(v);
            StringBuilder row = new StringBuilder(String.format("  %-8s", "#" + i));
            for (alternatives j : choiceSet) {
                row.append(String.format(" %7.1f%%", 100 * p.get(j)));
            }
            System.out.println(row);
        }
    }
}
