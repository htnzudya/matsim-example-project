package org.matsim.project.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Nutzenschicht: alle Koeffizienten einer Alternative.
 *
 * Jeder generische Koeffizient wird als Mittelwert UND Streuung gehalten
 * (Struktur analog zu Aggregationen mehrerer SLR-Quellen: Mean beta + SD beta).
 * Ist die Streuung 0, verhaelt sich das Modell wie ein Multinomiales Logit;
 * ist sie groesser 0, entsteht ueber agentenindividuelle Ziehungen ein
 * Mixed Logit mit Praeferenzheterogenitaet.
 *
 * gamma enthaelt die Koeffizienten auf die latenten Konstrukte des Agenten
 * (Schluessel identisch zu denen in {@link agentProfile}), gammaSd die
 * jeweilige Streuung dieser Koeffizienten.
 */
public final class modeParams {

    private final alternatives mode;

    private final double asc;
    private final double ascSd;

    /** Grenznutzen der Im-Fahrzeug-Zeit, in Nutzeneinheiten pro Stunde (i. d. R. negativ). */
    private final double betaInVehicleTime;
    private final double betaInVehicleTimeSd;

    /** Grenznutzen der Wartezeit, in Nutzeneinheiten pro Stunde (i. d. R. negativ). */
    private final double betaWaitTime;
    private final double betaWaitTimeSd;

    /** Grenznutzen der Kosten, in Nutzeneinheiten pro Euro (i. d. R. negativ). */
    private final double betaCost;
    private final double betaCostSd;

    /**
     * Tarif-/LOS-Parameter, KEIN Verhaltenskoeffizient: Kosten pro Kilometer in
     * Euro, mit dem die Simulation aus der tatsaechlich gerouteten Distanz die
     * Kosten der Alternative berechnet (siehe behaviourUtilityEstimator). Bewusst
     * OHNE Streuung - das ist ein realer Tarif/Betriebskostensatz, keine
     * individuelle Praeferenz (die Praeferenzheterogenitaet steckt in betaCost,
     * nicht hier). Default 0.0.
     */
    private final double costPerKm;

    /** Traegheits-/Gewohnheitsbonus, wenn dieser Modus zuletzt gewaehlt wurde. */
    private final double delta;

    private final Map<String, Double> gamma;
    private final Map<String, Double> gammaSd;

    public modeParams(alternatives mode,
                      double asc, double ascSd,
                      double betaInVehicleTime, double betaInVehicleTimeSd,
                      double betaWaitTime, double betaWaitTimeSd,
                      double betaCost, double betaCostSd,
                      double costPerKm,
                      double delta,
                      Map<String, Double> gamma,
                      Map<String, Double> gammaSd) {
        this.mode = mode;
        this.asc = asc;
        this.ascSd = ascSd;
        this.betaInVehicleTime = betaInVehicleTime;
        this.betaInVehicleTimeSd = betaInVehicleTimeSd;
        this.betaWaitTime = betaWaitTime;
        this.betaWaitTimeSd = betaWaitTimeSd;
        this.betaCost = betaCost;
        this.betaCostSd = betaCostSd;
        this.costPerKm = costPerKm;
        this.delta = delta;
        this.gamma = Collections.unmodifiableMap(new LinkedHashMap<>(gamma));
        this.gammaSd = Collections.unmodifiableMap(new LinkedHashMap<>(gammaSd));
    }

    /** Bequemlichkeits-Konstruktor ohne gammaSd (alle Gamma-Streuungen = 0). */
    public modeParams(alternatives mode,
                      double asc, double ascSd,
                      double betaInVehicleTime, double betaInVehicleTimeSd,
                      double betaWaitTime, double betaWaitTimeSd,
                      double betaCost, double betaCostSd,
                      double costPerKm,
                      double delta,
                      Map<String, Double> gamma) {
        this(mode, asc, ascSd, betaInVehicleTime, betaInVehicleTimeSd,
                betaWaitTime, betaWaitTimeSd, betaCost, betaCostSd, costPerKm, delta, gamma, Map.of());
    }

    public alternatives getMode() {
        return mode;
    }

    public double getAsc() {
        return asc;
    }

    public double getBetaInVehicleTime() {
        return betaInVehicleTime;
    }

    public double getBetaWaitTime() {
        return betaWaitTime;
    }

    public double getBetaCost() {
        return betaCost;
    }

    public double getCostPerKm() {
        return costPerKm;
    }

    public double getDelta() {
        return delta;
    }

    public double getGamma(String construct) {
        return gamma.getOrDefault(construct, 0.0);
    }

    public Map<String, Double> getGamma() {
        return gamma;
    }

    public double getGammaSd(String construct) {
        return gammaSd.getOrDefault(construct, 0.0);
    }

    public Map<String, Double> getGammaSd() {
        return gammaSd;
    }

    /**
     * Zieht agentenindividuelle Koeffizienten aus N(mean, sd).
     * Pro Agent EINMAL aufrufen und das Ergebnis festhalten - nicht pro Weg,
     * sonst waere die Praeferenz eines Agenten nicht stabil.
     *
     * Bei allen Streuungen = 0 gibt die Methode identische Werte zurueck
     * (das Modell degeneriert dann sauber zum MNL). delta und costPerKm werden
     * nicht gezogen (costPerKm ist ein Tarif-/LOS-Parameter, keine Praeferenz).
     */
    public modeParams draw(Random random) {
        Map<String, Double> drawnGamma = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : gamma.entrySet()) {
            double sd = getGammaSd(entry.getKey());
            drawnGamma.put(entry.getKey(), entry.getValue() + sd * random.nextGaussian());
        }
        return new modeParams(
                mode,
                asc + ascSd * random.nextGaussian(), 0.0,
                betaInVehicleTime + betaInVehicleTimeSd * random.nextGaussian(), 0.0,
                betaWaitTime + betaWaitTimeSd * random.nextGaussian(), 0.0,
                betaCost + betaCostSd * random.nextGaussian(), 0.0,
                costPerKm,
                delta,
                drawnGamma,
                Map.of()
        );
    }

    /**
     * Value of Travel Time Savings (Im-Fahrzeug-Zeit) in Euro pro Stunde:
     * das Verhaeltnis der beiden generischen Koeffizienten. Nuetzlich zur
     * Plausibilitaetspruefung der eingesetzten Parameter.
     */
    public double getInVehicleVot() {
        if (betaCost == 0.0) {
            return Double.NaN;
        }
        return betaInVehicleTime / betaCost;
    }

    /** Value of Travel Time Savings (Wartezeit) in Euro pro Stunde. */
    public double getWaitTimeVot() {
        if (betaCost == 0.0) {
            return Double.NaN;
        }
        return betaWaitTime / betaCost;
    }
}