package org.matsim.project.model;

import java.util.EnumMap;
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
 *   delta                        - Habit Theory / Verhaltenstraegheit; volle
 *                                  Uebergangsmatrix (vorheriger Modus -> j),
 *                                  nicht nur ein Bonus bei Modusbeibehaltung
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
     */
    public Map<alternatives, Double> probabilities(Map<alternatives, Double> utility) {

        if (utility.isEmpty()) {
            throw new IllegalArgumentException("Leeres Choice-Set.");
        }

        double max = Double.NEGATIVE_INFINITY;
        for (double v : utility.values()) {
            max = Math.max(max, v);
        }

        Map<alternatives, Double> exp = new EnumMap<>(alternatives.class);
        double sum = 0.0;
        for (Map.Entry<alternatives, Double> e : utility.entrySet()) {
            double x = Math.exp(scaleParameter * (e.getValue() - max));
            exp.put(e.getKey(), x);
            sum += x;
        }

        Map<alternatives, Double> p = new EnumMap<>(alternatives.class);
        for (Map.Entry<alternatives, Double> e : exp.entrySet()) {
            p.put(e.getKey(), e.getValue() / sum);
        }
        return p;
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