package org.matsim.project.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Agentenprofil = Profilschicht des Modells.
 *
 * Ein Agent traegt eine Segment-ID (z. B. eines der acht Mobilitaetsprofile)
 * und die daraus abgeleiteten Auspraegungen der latenten Konstrukte X*.
 *
 * Die Konstrukte sind bewusst NICHT fest verdrahtet, sondern als Map gehalten:
 * Ein spaeteres Szenario kann weitere Konstrukte ergaenzen, ohne dass Code
 * geaendert werden muss. Die Werte sind z-standardisiert (Mittelwert 0, SD 1).
 *
 * Die insgesamt 12 Konstrukte:
 *   "ptAffinity"                   - Affinitaet zum OEPNV
 *   "techAffinity"                 - mobilitaetsbezogene Technikaffinitaet
 *   "carsharingAffinity"           - Affinitaet zu Carsharing
 *   "carAffinity"                  - Affinitaet zum eigenen Auto
 *   "socialNorm"                   - soziale/subjektive Norm (TPB)
 *   "perceivedBehaviouralControl"  - wahrgenommene Verhaltenskontrolle (TPB, PBC)
 *   "attitude"                     - Einstellung (TPB)
 *   "safetyPerception"             - Sicherheitswahrnehmung
 *   "trust"                        - Vertrauen
 *   "perceivedRisk"                - wahrgenommenes Risiko (Protection Motivation Theory)
 *   "perceivedEaseOfUse"           - Nutzungsfreundlichkeit (TAM, PEOU)
 *   "perceivedUsefulness"          - wahrgenommener Nutzen (TAM)
 *
 * Jedes Konstrukt hat neben dem Mittelwert (constructs) auch eine Streuung
 * (constructsSd), analog zur Mean+SD-Struktur in {@link modeParams}.
 *
 * probability = Anteil dieses Segments an der Grundgesamtheit
 * (Clustergroesse einer Mobilitaetstypologie-Studie), Summe ueber alle
 * Segmente eines Szenarios = 1,0.
 */
public final class agentProfile {

    private final String segmentId;
    private final double probability;
    private final Map<String, Double> constructs;
    private final Map<String, Double> constructsSd;

    public agentProfile(String segmentId, Map<String, Double> constructs) {
        this(segmentId, 0.0, constructs, Map.of());
    }

    public agentProfile(String segmentId, double probability, Map<String, Double> constructs) {
        this(segmentId, probability, constructs, Map.of());
    }

    public agentProfile(String segmentId, double probability,
                         Map<String, Double> constructs, Map<String, Double> constructsSd) {
        this.segmentId = segmentId;
        this.probability = probability;
        this.constructs = Collections.unmodifiableMap(new LinkedHashMap<>(constructs));
        this.constructsSd = Collections.unmodifiableMap(new LinkedHashMap<>(constructsSd));
    }

    public String getSegmentId() {
        return segmentId;
    }

    /** Anteil dieses Segments an der Grundgesamtheit. */
    public double getProbability() {
        return probability;
    }

    /**
     * Wert eines latenten Konstrukts. Nicht gesetzte Konstrukte liefern 0.0,
     * wirken also im z-standardisierten Raum wie ein durchschnittlicher Agent.
     */
    public double get(String construct) {
        return constructs.getOrDefault(construct, 0.0);
    }

    /** Streuung eines latenten Konstrukts. Nicht gesetzte Konstrukte liefern 0.0. */
    public double getSd(String construct) {
        return constructsSd.getOrDefault(construct, 0.0);
    }

    public Map<String, Double> getConstructs() {
        return constructs;
    }

    public Map<String, Double> getConstructsSd() {
        return constructsSd;
    }

    /**
     * Zieht agentenindividuelle Konstruktwerte aus N(mean, sd).
     * Pro Agent EINMAL aufrufen und das Ergebnis festhalten - nicht pro Weg,
     * sonst waere das Profil eines Agenten nicht stabil.
     *
     * Bei allen Streuungen = 0 gibt die Methode identische Werte zurueck
     * (das Segment verhaelt sich dann wie ein einzelner Punktwert).
     */
    public agentProfile draw(Random random) {
        Map<String, Double> drawn = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : constructs.entrySet()) {
            double sd = getSd(entry.getKey());
            drawn.put(entry.getKey(), entry.getValue() + sd * random.nextGaussian());
        }
        return new agentProfile(segmentId, probability, drawn, Map.of());
    }

    @Override
    public String toString() {
        return "AgentProfile[" + segmentId + " p=" + probability + " " + constructs + "]";
    }
}