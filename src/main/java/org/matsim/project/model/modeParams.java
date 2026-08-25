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
 * Mixed Logit mit Praeferenzheterogenitaet. Alle Koeffizienten AUSSER dem
 * Traegheits-/Gewohnheitsbonus (deltaByPreviousMode) werden aus N(mean, sd)
 * gezogen; der Gewohnheitsbonus hat mangels SD-Daten stattdessen eine
 * Dreiecksverteilung (siehe deltaTriangularHalfWidthRight-Feld-Javadoc) -
 * beide Mechanismen degenerieren bei Streuung/Breite 0 sauber zum
 * deterministischen MNL-Fall.
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

    /**
     * Grenznutzen der Kosten fuer die MITTLERE Einkommensklasse, in
     * Nutzeneinheiten pro Euro (i. d. R. negativ) - siehe incomeTier-Klassen-
     * Javadoc und fertigeabmparameter.xlsx Zeile 13 ("mittleres Einkommen").
     * Bleibt der Feldname ohne "Mittel"-Suffix (Abwaertskompatibilitaet: vor
     * der Einkommensdifferenzierung war das der einzige betaCost-Wert).
     */
    private final double betaCost;
    private final double betaCostSd;

    /**
     * Grenznutzen der Kosten fuer NIEDRIGE/HOHE Einkommensklasse (Excel-Zeilen
     * 15/14) - siehe incomeTier-Klassen-Javadoc. draw(Random, incomeTier)
     * waehlt je nach hhIncome-Klasse des Agenten eines der drei Paare
     * (betaCost/-Sd, betaCostNiedrig/-Sd, betaCostHoch/-Sd) fuer die
     * Mixed-Logit-Ziehung aus - betaCost selbst bleibt dabei unveraendert
     * MITTEL, kein Ersetzen/Ueberschreiben.
     */
    private final double betaCostNiedrig;
    private final double betaCostNiedrigSd;
    private final double betaCostHoch;
    private final double betaCostHochSd;

    /**
     * Tarif-/LOS-Parameter, KEIN Verhaltenskoeffizient: Kosten pro Kilometer in
     * Euro, mit dem die Simulation aus der tatsaechlich gerouteten Distanz die
     * Kosten der Alternative berechnet (siehe behaviourUtilityEstimator). Bewusst
     * OHNE Streuung - das ist ein realer Tarif/Betriebskostensatz, keine
     * individuelle Praeferenz (die Praeferenzheterogenitaet steckt in betaCost,
     * nicht hier). Default 0.0.
     */
    private final double costPerKm;

    /**
     * Sentinel fuer costPerKmWithTicket: "kein Override konfiguriert" - es gilt
     * dann immer costPerKm, unabhaengig davon, ob die Person ein Abo/Zeitkarte
     * hat. Verhindert, dass allein das Vorhandensein dieses Feldes (mit einem
     * technischen Java-Default wie 0.0) unbemerkt das Verhalten aendert, bevor
     * es explizit in der Config gesetzt wurde.
     */
    public static final double NO_TICKET_OVERRIDE = -1.0;

    /**
     * costPerKm-Override fuer Personen, die fuer diesen Modus bereits ein
     * Abo/Zeitkarte besitzen (z. B. PT-Ticket, siehe behaviourConfigGroup.
     * ticketAttribute/ticketOwnedValue) - deckt den Grenzkosten-Fall ab: eine
     * zusaetzliche Fahrt kostet einen Abo-Inhaber effektiv nichts (der Fixpreis
     * ist bereits bezahlt), waehrend Nicht-Inhaber weiterhin den vollen linearen
     * Distanztarif (costPerKm) zahlen. Ohne diesen Override wuerde das Modell
     * PT-Kosten fuer JEDE Person gleich hoch ansetzen, obwohl real ~14 % der
     * Oberlausitz/Dresden-Population laut ptTicket-Attribut bereits eine
     * Zeitkarte hat (siehe output_persons.csv eines echten Laufs). Default
     * {@link #NO_TICKET_OVERRIDE} = kein Override, siehe dortigen Javadoc.
     */
    private final double costPerKmWithTicket;

    /**
     * Traegheits-/Gewohnheitsbonus je vorherigem Modus (Schluessel =
     * {@link alternatives#name()}), wenn dieser Modus zuletzt gewaehlt wurde.
     * Der bisherige Spezialfall "Bonus nur bei identischem Vor-/Zielmodus"
     * ist der Diagonaleintrag dieser Matrix; andere Eintraege erlauben
     * asymmetrische Uebergaenge (z. B. CA -> PAV != PAV -> CA).
     */
    private final Map<String, Double> deltaByPreviousMode;

    /**
     * Rechte Halbbreite der Dreiecksverteilung fuer den Traegheits-/
     * Gewohnheitsbonus (Auftraggeber-Vorgabe): draw(Random, incomeTier) zieht
     * fuer jeden NICHT-null-Eintrag von deltaByPreviousMode aus
     * Dreieck(min=0, modus=deltaByPreviousMode-Wert, max=Wert+diese
     * Halbbreite) - links IMMER bis 0 ("Dreieck bis 0", unabhaengig vom
     * Modus-Wert selbst), rechts um diese konfigurierte Breite ueber den
     * Modus hinaus (z. B. Modus 0.831 + Breite 0.8 -&gt; Dreieck(0, 0.831,
     * 1.631)). Default 0.0 = keine Streuung (deterministischer Passthrough
     * wie zuvor) - Abwaertskompatibilitaet fuer bestehende Aufrufer/Tests,
     * die dieses Feld nicht kennen.
     */
    private final double deltaTriangularHalfWidthRight;

    private final Map<String, Double> gamma;
    private final Map<String, Double> gammaSd;

    public modeParams(alternatives mode,
                      double asc, double ascSd,
                      double betaInVehicleTime, double betaInVehicleTimeSd,
                      double betaWaitTime, double betaWaitTimeSd,
                      double betaCost, double betaCostSd,
                      double costPerKm,
                      Map<String, Double> deltaByPreviousMode,
                      Map<String, Double> gamma,
                      Map<String, Double> gammaSd) {
        this(mode, asc, ascSd, betaInVehicleTime, betaInVehicleTimeSd,
                betaWaitTime, betaWaitTimeSd, betaCost, betaCostSd, costPerKm, NO_TICKET_OVERRIDE,
                deltaByPreviousMode, gamma, gammaSd);
    }

    /** Bequemlichkeits-Konstruktor ohne gammaSd (alle Gamma-Streuungen = 0). */
    public modeParams(alternatives mode,
                      double asc, double ascSd,
                      double betaInVehicleTime, double betaInVehicleTimeSd,
                      double betaWaitTime, double betaWaitTimeSd,
                      double betaCost, double betaCostSd,
                      double costPerKm,
                      Map<String, Double> deltaByPreviousMode,
                      Map<String, Double> gamma) {
        this(mode, asc, ascSd, betaInVehicleTime, betaInVehicleTimeSd,
                betaWaitTime, betaWaitTimeSd, betaCost, betaCostSd, costPerKm,
                deltaByPreviousMode, gamma, Map.of());
    }

    /**
     * Voller Konstruktor inkl. costPerKmWithTicket, siehe Feld-Javadoc. Ohne
     * explizite betaCostNiedrig/-Hoch-Werte - delegiert mit betaCost/betaCostSd
     * fuer alle drei Einkommensstufen (Abwaertskompatibilitaet: keine
     * Einkommensdifferenzierung, wie vor incomeTier).
     */
    public modeParams(alternatives mode,
                      double asc, double ascSd,
                      double betaInVehicleTime, double betaInVehicleTimeSd,
                      double betaWaitTime, double betaWaitTimeSd,
                      double betaCost, double betaCostSd,
                      double costPerKm, double costPerKmWithTicket,
                      Map<String, Double> deltaByPreviousMode,
                      Map<String, Double> gamma,
                      Map<String, Double> gammaSd) {
        this(mode, asc, ascSd, betaInVehicleTime, betaInVehicleTimeSd,
                betaWaitTime, betaWaitTimeSd, betaCost, betaCostSd,
                betaCost, betaCostSd, betaCost, betaCostSd,
                costPerKm, costPerKmWithTicket,
                deltaByPreviousMode, gamma, gammaSd);
    }

    /**
     * Voller Konstruktor inkl. Einkommensdifferenzierung der Kosten (siehe
     * incomeTier-Klassen-Javadoc und betaCostNiedrig/-Hoch-Feld-Javadoc).
     */
    public modeParams(alternatives mode,
                      double asc, double ascSd,
                      double betaInVehicleTime, double betaInVehicleTimeSd,
                      double betaWaitTime, double betaWaitTimeSd,
                      double betaCost, double betaCostSd,
                      double betaCostNiedrig, double betaCostNiedrigSd,
                      double betaCostHoch, double betaCostHochSd,
                      double costPerKm, double costPerKmWithTicket,
                      Map<String, Double> deltaByPreviousMode,
                      Map<String, Double> gamma,
                      Map<String, Double> gammaSd) {
        this(mode, asc, ascSd, betaInVehicleTime, betaInVehicleTimeSd,
                betaWaitTime, betaWaitTimeSd, betaCost, betaCostSd,
                betaCostNiedrig, betaCostNiedrigSd, betaCostHoch, betaCostHochSd,
                costPerKm, costPerKmWithTicket,
                deltaByPreviousMode, gamma, gammaSd, 0.0);
    }

    /**
     * Voller Konstruktor inkl. deltaTriangularHalfWidthRight, siehe dortigen
     * Feld-Javadoc.
     */
    public modeParams(alternatives mode,
                      double asc, double ascSd,
                      double betaInVehicleTime, double betaInVehicleTimeSd,
                      double betaWaitTime, double betaWaitTimeSd,
                      double betaCost, double betaCostSd,
                      double betaCostNiedrig, double betaCostNiedrigSd,
                      double betaCostHoch, double betaCostHochSd,
                      double costPerKm, double costPerKmWithTicket,
                      Map<String, Double> deltaByPreviousMode,
                      Map<String, Double> gamma,
                      Map<String, Double> gammaSd,
                      double deltaTriangularHalfWidthRight) {
        this.mode = mode;
        this.asc = asc;
        this.ascSd = ascSd;
        this.betaInVehicleTime = betaInVehicleTime;
        this.betaInVehicleTimeSd = betaInVehicleTimeSd;
        this.betaWaitTime = betaWaitTime;
        this.betaWaitTimeSd = betaWaitTimeSd;
        this.betaCost = betaCost;
        this.betaCostNiedrig = betaCostNiedrig;
        this.betaCostNiedrigSd = betaCostNiedrigSd;
        this.betaCostHoch = betaCostHoch;
        this.betaCostHochSd = betaCostHochSd;
        this.betaCostSd = betaCostSd;
        this.costPerKm = costPerKm;
        this.costPerKmWithTicket = costPerKmWithTicket;
        this.deltaByPreviousMode = Collections.unmodifiableMap(new LinkedHashMap<>(deltaByPreviousMode));
        this.deltaTriangularHalfWidthRight = deltaTriangularHalfWidthRight;
        this.gamma = Collections.unmodifiableMap(new LinkedHashMap<>(gamma));
        this.gammaSd = Collections.unmodifiableMap(new LinkedHashMap<>(gammaSd));
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

    /** Grenznutzen der Kosten (Mittelwert der Ziehung, VOR Mixed-Logit-Streuung) fuer die gegebene Einkommensstufe - siehe incomeTier-Klassen-Javadoc. */
    public double getBetaCost(incomeTier tier) {
        return switch (tier) {
            case NIEDRIG -> betaCostNiedrig;
            case HOCH -> betaCostHoch;
            case MITTEL -> betaCost;
        };
    }

    private double getBetaCostSd(incomeTier tier) {
        return switch (tier) {
            case NIEDRIG -> betaCostNiedrigSd;
            case HOCH -> betaCostHochSd;
            case MITTEL -> betaCostSd;
        };
    }

    public double getCostPerKm() {
        return costPerKm;
    }

    public double getCostPerKmWithTicket() {
        return costPerKmWithTicket;
    }

    /**
     * Effektiver costPerKm fuer eine konkrete Person: costPerKmWithTicket, wenn
     * hasTicket UND ein Override konfiguriert ist (siehe NO_TICKET_OVERRIDE),
     * sonst der normale costPerKm. Siehe costPerKmWithTicket-Feld-Javadoc.
     */
    public double effectiveCostPerKm(boolean hasTicket) {
        return (hasTicket && costPerKmWithTicket != NO_TICKET_OVERRIDE) ? costPerKmWithTicket : costPerKm;
    }

    /** Traegheitsbonus fuer diesen Modus, gegeben den vorherigen Modus (0.0, falls kein Eintrag). */
    public double getDelta(alternatives previousMode) {
        return deltaByPreviousMode.getOrDefault(previousMode.name(), 0.0);
    }

    public Map<String, Double> getDeltaByPreviousMode() {
        return deltaByPreviousMode;
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
     * Zieht agentenindividuelle Koeffizienten aus N(mean, sd), betaCost fuer
     * die MITTLERE Einkommensstufe (siehe incomeTier-Klassen-Javadoc) - Bequem-
     * lichkeitsueberladung fuer Aufrufer, die (noch) keine Einkommensstufe
     * kennen. Ansonsten identisch zu draw(Random, incomeTier), siehe dortigen
     * Javadoc.
     */
    public modeParams draw(Random random) {
        return draw(random, incomeTier.MITTEL);
    }

    /**
     * Zieht agentenindividuelle Koeffizienten aus N(mean, sd).
     * Pro Agent EINMAL aufrufen und das Ergebnis festhalten - nicht pro Weg,
     * sonst waere die Praeferenz eines Agenten nicht stabil.
     *
     * betaCost wird aus dem zu tier passenden Mittelwert/Streuung-Paar gezogen
     * (siehe getBetaCost(incomeTier)/getBetaCostSd(incomeTier)) - alle anderen
     * Koeffizienten sind von der Einkommensstufe unabhaengig, wie in der
     * fertigeabmparameter.xlsx vorgegeben (nur beta_Kosten ist dort nach
     * Einkommen differenziert).
     *
     * Bei allen Streuungen = 0 gibt die Methode identische Werte zurueck
     * (das Modell degeneriert dann sauber zum MNL). costPerKm wird nicht
     * gezogen (Tarif-/LOS-Parameter, keine Praeferenz). deltaByPreviousMode
     * wird - sofern deltaTriangularHalfWidthRight &gt; 0 konfiguriert ist -
     * PRO NICHT-NULL-EINTRAG aus einer Dreiecksverteilung gezogen (siehe
     * deltaTriangularHalfWidthRight-Feld-Javadoc); Eintraege mit Wert 0.0
     * bleiben unveraendert 0.0 (kein Bonus, keine Streuung noetig - der Bonus
     * gilt nur beim Diagonaleintrag/identischem Vormodus). Bei
     * deltaTriangularHalfWidthRight=0.0 (Default) bleibt deltaByPreviousMode
     * unveraendert (deterministisch, wie vor Einfuehrung der Dreiecksverteilung).
     */
    public modeParams draw(Random random, incomeTier tier) {
        Map<String, Double> drawnGamma = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : gamma.entrySet()) {
            double sd = getGammaSd(entry.getKey());
            drawnGamma.put(entry.getKey(), entry.getValue() + sd * random.nextGaussian());
        }
        double drawnBetaCost = getBetaCost(tier) + getBetaCostSd(tier) * random.nextGaussian();

        Map<String, Double> drawnDelta = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : deltaByPreviousMode.entrySet()) {
            double value = entry.getValue();
            if (value == 0.0 || deltaTriangularHalfWidthRight <= 0.0) {
                drawnDelta.put(entry.getKey(), value);
            } else {
                drawnDelta.put(entry.getKey(), drawTriangular(random, 0.0, value, value + deltaTriangularHalfWidthRight));
            }
        }

        return new modeParams(
                mode,
                asc + ascSd * random.nextGaussian(), 0.0,
                betaInVehicleTime + betaInVehicleTimeSd * random.nextGaussian(), 0.0,
                betaWaitTime + betaWaitTimeSd * random.nextGaussian(), 0.0,
                drawnBetaCost, 0.0,
                costPerKm, costPerKmWithTicket,
                drawnDelta,
                drawnGamma,
                Map.of()
        );
    }

    /**
     * Dreiecksverteilung(min, modus, max) per Standard-Inverstransformation
     * (Ziehung u~U(0,1), Fallunterscheidung an der Stelle des Modus). Bei
     * max&lt;=min (keine Breite konfiguriert) wird der Modus unveraendert
     * zurueckgegeben - degeneriert sauber zum deterministischen Fall, analog
     * zu den anderen SD=0-Faellen dieser Klasse.
     */
    private static double drawTriangular(Random random, double min, double mode, double max) {
        if (max <= min) {
            return mode;
        }
        double u = random.nextDouble();
        double modeFraction = (mode - min) / (max - min);
        if (u < modeFraction) {
            return min + Math.sqrt(u * (max - min) * (mode - min));
        }
        return max - Math.sqrt((1 - u) * (max - min) * (max - mode));
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