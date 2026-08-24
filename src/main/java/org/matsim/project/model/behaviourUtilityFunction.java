package org.matsim.project.model;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * KERN DES MODELLS.
 *
 * Berechnet den systematischen Nutzen V(i,j) eines Agenten i fuer eine
 * Alternative j und daraus die Wahlwahrscheinlichkeiten (Logit).
 *
 * Spezifikation:
 *
 *   V(i,j) = ASC_j
 *          + beta_inVehicleTime_j * ImFahrzeugZeit(i,j)
 *          + beta_waitTime_j      * Wartezeit(i,j)
 *          + beta_cost_j          * Kosten(i,j)
 *          + delta_{prevMode,j}   * 1[vorheriger Modus gewaehlt]
 *          + SUM_k gamma_jk       * X*_ik
 *
 * Die Bloecke entsprechen den theoretischen Saeulen der Arbeit:
 *   ASC                          - modusspezifische Grundpraeferenz (z. B. AV-Akzeptanz)
 *   beta_inVehicleTime/waitTime/cost - klassische DCM-Level-of-Service-Terme (RUM)
 *   delta                        - Habit Theory / Verhaltenstraegheit. AKTUELL: nur der
 *                                  Diagonaleintrag der Uebergangsmatrix ist besetzt -
 *                                  ein einheitlicher Bonus 0.831, wenn der vorherige
 *                                  Modus mit j identisch war, sonst 0 (keine
 *                                  asymmetrischen Uebergaenge mehr) - siehe
 *                                  modeParams.deltaByPreviousMode/config.xml.
 *   gamma * X*                   - TPB (Einstellung, subjektive Norm, PBC),
 *                                  TAM (PEOU, wahrgenommener Nutzen) und
 *                                  Protection Motivation Theory (Risiko-/Sicherheitswahrnehmung, Vertrauen)
 *
 * Diese Klasse hat bewusst KEINE MATSim-Abhaengigkeit: Sie ist damit ohne
 * Simulation testbar und bleibt das eigenstaendige, wiederverwendbare
 * Ergebnis der Arbeit.
 */
public final class behaviourUtilityFunction {

    /** Skalenparameter mu des Logit-Modells. 1.0 = Standardnormierung. */
    private final double scaleParameter;

    public behaviourUtilityFunction() {
        this(1.0);
    }

    public behaviourUtilityFunction(double scaleParameter) {
        if (scaleParameter <= 0.0) {
            throw new IllegalArgumentException("scaleParameter muss groesser 0 sein.");
        }
        this.scaleParameter = scaleParameter;
    }

    /**
     * Systematischer Nutzen V(i,j) einer einzelnen Alternative.
     *
     * @param profile      Agentenprofil (Segment + latente Konstrukte)
     * @param params       Nutzenparameter der Alternative
     * @param trip         Level-of-Service-Attribute der Alternative
     * @param previousMode zuletzt gewaehlter Modus (fuer delta); darf null sein
     */
    public double utility(agentProfile profile,
                         modeParams params,
                         TripContext trip,
                         alternatives previousMode) {

        double v = params.getAsc();

        v += params.getBetaInVehicleTime() * trip.inVehicleTimeHours();
        v += params.getBetaWaitTime() * trip.waitTimeHours();
        v += params.getBetaCost() * trip.costEuro();

        // delta (Habit Theory) REAKTIVIERT - Auftraggeber-Vorgabe: einheitlicher
        // Traegheitsbonus 0.831, NUR wenn der vorherige Modus mit diesem hier
        // identisch war (der "bisherige Spezialfall" aus dem modeParams-
        // Klassen-Javadoc, nicht mehr die volle asymmetrische Vormodus-Matrix).
        // Technisch unveraendert ueber params.getDelta(previousMode) - jedes
        // modeParams-deltaByPreviousMode traegt jetzt NUR noch seinen eigenen
        // Diagonaleintrag (Modus -> sich selbst = 0.831), alle anderen Eintraege
        // fehlen und liefern damit ueber getOrDefault(...) 0.0 (siehe
        // generate_config.py/config.xml).
        if (previousMode != null) {
            v += params.getDelta(previousMode);
        }

        for (Map.Entry<String, Double> entry : params.getGamma().entrySet()) {
            v += entry.getValue() * profile.get(entry.getKey());
        }

        return v;
    }

    /**
     * Systematischer Nutzen aller verfuegbaren Alternativen.
     *
     * @param available Alternativen, die dem Agenten ueberhaupt offenstehen
     *                  (Choice-Set-Restriktion, z. B. kein CA ohne Pkw-Verfuegbarkeit)
     */
    public Map<alternatives, Double> utilityAll(agentProfile profile,
                                               Map<alternatives, modeParams> params,
                                               Map<alternatives, TripContext> trips,
                                               alternatives previousMode,
                                               Iterable<alternatives> available) {

        Map<alternatives, Double> result = new EnumMap<>(alternatives.class);
        for (alternatives j : available) {
            modeParams p = params.get(j);
            TripContext t = trips.get(j);
            if (p == null || t == null) {
                throw new IllegalArgumentException(
                        "Fehlende Parameter oder LOS-Attribute fuer Alternative " + j);
            }
            result.put(j, utility(profile, p, t, previousMode));
        }
        return result;
    }

    /**
     * Wahlwahrscheinlichkeiten nach dem Multinomialen Logit:
     *
     *   P(j) = exp(mu * V_j) / SUM_m exp(mu * V_m)
     *
     * Numerisch stabilisiert ueber Abzug des Maximums (Log-Sum-Exp-Trick).
     * Delegiert an {@link #softmax}, siehe dortigen Javadoc.
     */
    public Map<alternatives, Double> probabilities(Map<alternatives, Double> utility) {
        return softmax(utility, scaleParameter);
    }

    /**
     * Generische MNL-Softmax ueber einen beliebigen Alternativen-Schluessel T:
     *
     *   P(j) = exp(mu * V_j) / SUM_m exp(mu * V_m)
     *
     * Numerisch stabilisiert ueber Abzug des Maximums (Log-Sum-Exp-Trick).
     * Generisch (nicht auf das {@link alternatives}-Enum beschraenkt), damit
     * dieselbe, einzige Softmax-Implementierung auch fuer die Nullalternative
     * (Choice-Set = Optional&lt;alternatives&gt;, siehe behaviourCandidateTripInserter)
     * genutzt werden kann - EIN Ort, an dem die Wahlwahrscheinlichkeitsformel
     * steht, nicht zwei potenziell auseinanderlaufende Implementierungen.
     */
    public static <T> Map<T, Double> softmax(Map<T, Double> utility, double scaleParameter) {

        if (utility.isEmpty()) {
            throw new IllegalArgumentException("Leeres Choice-Set.");
        }

        double max = Double.NEGATIVE_INFINITY;
        for (double v : utility.values()) {
            max = Math.max(max, v);
        }

        Map<T, Double> exp = new LinkedHashMap<>();
        double sum = 0.0;
        for (Map.Entry<T, Double> e : utility.entrySet()) {
            double x = Math.exp(scaleParameter * (e.getValue() - max));
            exp.put(e.getKey(), x);
            sum += x;
        }

        Map<T, Double> p = new LinkedHashMap<>();
        for (Map.Entry<T, Double> e : exp.entrySet()) {
            p.put(e.getKey(), e.getValue() / sum);
        }
        return p;
    }

    /**
     * Zieht eine Alternative aus einer Wahlwahrscheinlichkeitsverteilung ueber
     * die kumulierte Verteilung: iteriert die Eintraege in Map-Reihenfolge
     * (daher LinkedHashMap-Eingabe fuer eine STABILE, reproduzierbare
     * Reihenfolge - siehe softmax(...), das bereits eine LinkedHashMap liefert)
     * und liefert die erste Alternative, deren kumulierte Wahrscheinlichkeit
     * draw ueberschreitet. draw MUSS deterministisch (personen-/salt-gebunden)
     * erzeugt werden, siehe behaviourCandidateTripInserter-Klassen-Javadoc
     * ("Nullalternative: u_n deterministisch aus der Agenten-ID").
     *
     * @param draw gleichverteilte Zufallszahl in [0, 1)
     */
    public static <T> T drawFromCumulative(Map<T, Double> probabilities, double draw) {
        if (probabilities.isEmpty()) {
            throw new IllegalArgumentException("Leeres Choice-Set.");
        }
        double cumulative = 0.0;
        T last = null;
        for (Map.Entry<T, Double> e : probabilities.entrySet()) {
            cumulative += e.getValue();
            last = e.getKey();
            if (draw < cumulative) {
                return e.getKey();
            }
        }
        // Rundungsfehler (cumulative minimal < 1.0 bei draw sehr nah an 1.0):
        // letzte Alternative der (stabilen) Reihenfolge liefern statt eine
        // Exception zu riskieren.
        return last;
    }

    /**
     * Logsum (erwarteter Maximalnutzen) des Choice-Sets. Standardmass fuer
     * Erreichbarkeit / Wohlfahrt und Voraussetzung fuer spaetere Nested-Logit-
     * Erweiterungen.
     */
    public double logsum(Map<alternatives, Double> utility) {
        double max = Double.NEGATIVE_INFINITY;
        for (double v : utility.values()) {
            max = Math.max(max, v);
        }
        double sum = 0.0;
        for (double v : utility.values()) {
            sum += Math.exp(scaleParameter * (v - max));
        }
        return max + Math.log(sum) / scaleParameter;
    }
}