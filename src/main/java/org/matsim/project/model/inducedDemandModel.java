package org.matsim.project.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Induzierte Nachfrage ueber den Logsum-Zugewinn der AV-Welt gegenueber der
 * heutigen Welt - EIN Gesamteffekt auf die Personenkilometer, aufgeteilt auf
 * die beiden aus der Literatur bestaetigten Teileffekte (mehr Wege, laengere
 * Wege), statt zwei unabhaengiger Formeln.
 *
 * Der Logsum ist unter der Annahme des Logit-Modells das Mass der
 * Konsumrente und wird in der Verkehrswissenschaft explizit als Bewertung
 * von Erreichbarkeit genutzt (siehe {@link behaviourUtilityFunction#logsum}):
 *
 *   Lambda_n = ln SUM_{i in C_n} exp(V_ni)
 *   DeltaLambda_n = Lambda_n^AVM - Lambda_n^base
 *
 * Lambda_n^AVM ist der Logsum der AV-Welt (volles/tatsaechlich verfuegbares
 * Choice-Set), Lambda_n^base der Logsum der heutigen Welt (nur CA und PT).
 *
 * WICHTIG - keine Doppelzaehlung: die Wegeanzahl und die Weglaenge duerfen
 * NICHT unabhaengig voneinander mit demselben DeltaLambda_n skaliert werden
 * (T_ind * D_ind waere dann ein VIEL zu grosses Personenkilometer-Wachstum,
 * z. B. 1,38 * 1,38 = +90% statt der beabsichtigten +38%). Stattdessen
 * bestimmt EIN theta das Wachstum der Personenkilometer (VKM) insgesamt,
 * und tripShare (alpha, in [0,1]) teilt dieses Wachstum im Log-Raum auf die
 * beiden Teileffekte auf:
 *
 *   VKM_n^base = T_n^base * D_n^base
 *   VKM_n^ind  = VKM_n^base * exp(theta * DeltaLambda_n)
 *   T_n^ind    = T_n^base  * exp(alpha       * theta * DeltaLambda_n)
 *   D_n^ind    = D_n^base  * exp((1 - alpha) * theta * DeltaLambda_n)
 *
 * Per Konstruktion gilt T_n^ind * D_n^ind = VKM_n^ind exakt (die beiden
 * Exponenten addieren sich zu theta * DeltaLambda_n). alpha = 1 bildet reinen
 * Generierungseffekt ab (nur mehr Wege, D_ind = D_base), alpha = 0 reinen
 * Zielwahleffekt (nur laengere Wege, T_ind = T_base).
 *
 * theta und tripShare sind aus den AV-Welt-Ergebnissen zu schaetzende
 * Groessen. THETA_PLACEHOLDER (+20%) und TRIP_SHARE_PLACEHOLDER (hälftige
 * Aufteilung) sind noch nicht aus eigenen Ergebnissen kalibriert und werden
 * spaeter aktualisiert.
 *
 * HINWEIS Zielwahl/Distanz: D_n^ind ist weiterhin eine reine Kennzahl
 * (Schaetzung "wie viel weiter wuerde X_n reisen"), KEINE tatsaechliche
 * Zielverlegung. Ohne eine echte Zielwahl (die eine andere, tatsaechlich
 * weiter entfernte Facility waehlt) wird diese laengere Distanz nicht im
 * Netz simuliert (kein zusaetzliches Stauaufkommen, keine laengere reale
 * Fahrzeit) - siehe Diskussion zu locationchoice-Contrib fuer den naechsten
 * Ausbauschritt.
 */
public final class inducedDemandModel {

    /** Platzhalter fuer theta (+20% Personenkilometer je Logsum-Einheit) - noch nicht kalibriert. */
    public static final double THETA_PLACEHOLDER = 0.20;

    /** Platzhalter fuer tripShare (haelftige Aufteilung mehr Wege vs. laengere Wege) - noch nicht kalibriert. */
    public static final double TRIP_SHARE_PLACEHOLDER = 0.5;

    /** "Heutige Welt": Choice-Set ohne AV-Modi, nur CA und PT. */
    public static final Set<alternatives> BASELINE_CHOICE_SET = EnumSet.of(alternatives.CA, alternatives.PT);

    private final behaviourUtilityFunction utilityFunction;
    private final double theta;
    private final double tripShare;

    public inducedDemandModel(behaviourUtilityFunction utilityFunction) {
        this(utilityFunction, THETA_PLACEHOLDER, TRIP_SHARE_PLACEHOLDER);
    }

    public inducedDemandModel(behaviourUtilityFunction utilityFunction, double theta, double tripShare) {
        if (tripShare < 0.0 || tripShare > 1.0) {
            throw new IllegalArgumentException("tripShare muss in [0, 1] liegen, war " + tripShare);
        }
        this.utilityFunction = utilityFunction;
        this.theta = theta;
        this.tripShare = tripShare;
    }

    public double getTheta() {
        return theta;
    }

    /** Anteil des Personenkilometer-Wachstums, der auf mehr Wege entfaellt (Rest auf laengere Wege). */
    public double getTripShare() {
        return tripShare;
    }

    /** Lambda_n^base: Logsum der heutigen Welt (nur CA und PT). */
    public double baselineLogsum(agentProfile profile, Map<alternatives, modeParams> params,
                                  Map<alternatives, TripContext> trips, alternatives previousMode) {
        Map<alternatives, Double> utilities =
                utilityFunction.utilityAll(profile, params, trips, previousMode, BASELINE_CHOICE_SET);
        return utilityFunction.logsum(utilities);
    }

    /** Lambda_n^AVM: Logsum der AV-Welt (das uebergebene, tatsaechlich verfuegbare Choice-Set). */
    public double avmLogsum(agentProfile profile, Map<alternatives, modeParams> params,
                             Map<alternatives, TripContext> trips, alternatives previousMode,
                             Iterable<alternatives> available) {
        Map<alternatives, Double> utilities =
                utilityFunction.utilityAll(profile, params, trips, previousMode, available);
        return utilityFunction.logsum(utilities);
    }

    /** DeltaLambda_n = Lambda_n^AVM - Lambda_n^base. */
    public double deltaLogsum(double avmLogsum, double baselineLogsum) {
        return avmLogsum - baselineLogsum;
    }

    /** T_n^ind = T_n^base * exp(tripShare * theta * DeltaLambda_n). */
    public double inducedTrips(double baseTrips, double deltaLogsum) {
        return baseTrips * Math.exp(tripShare * theta * deltaLogsum);
    }

    /** D_n^ind = D_n^base * exp((1 - tripShare) * theta * DeltaLambda_n) - siehe Klassen-Javadoc (reine Kennzahl, keine Zielverlegung). */
    public double inducedDistanceKm(double baseDistanceKm, double deltaLogsum) {
        return baseDistanceKm * Math.exp((1.0 - tripShare) * theta * deltaLogsum);
    }

    /** VKM_n^ind = VKM_n^base * exp(theta * DeltaLambda_n), mit VKM_n^base = T_n^base * D_n^base. */
    public double inducedPersonKm(double baseTrips, double baseDistanceKm, double deltaLogsum) {
        return baseTrips * baseDistanceKm * Math.exp(theta * deltaLogsum);
    }

    /**
     * Volle Berechnung in einem Aufruf: aus den beiden Choice-Sets (heutige
     * Welt vs. tatsaechlich verfuegbare AV-Welt) sowie Basiswegeanzahl und
     * Basisdistanz alle drei induzierten Kennzahlen - konsistent, da
     * inducedTrips() * inducedDistanceKm() == inducedPersonKm() per Konstruktion.
     */
    public Result compute(agentProfile profile, Map<alternatives, modeParams> params,
                           Map<alternatives, TripContext> trips, alternatives previousMode,
                           Iterable<alternatives> availableAvm, double baseTrips, double baseDistanceKm) {

        double lambdaBase = baselineLogsum(profile, params, trips, previousMode);
        double lambdaAvm = avmLogsum(profile, params, trips, previousMode, availableAvm);
        double delta = deltaLogsum(lambdaAvm, lambdaBase);
        double induced = inducedTrips(baseTrips, delta);
        double inducedDistance = inducedDistanceKm(baseDistanceKm, delta);
        double inducedPersonKm = inducedPersonKm(baseTrips, baseDistanceKm, delta);

        return new Result(lambdaBase, lambdaAvm, delta,
                baseTrips, induced, baseDistanceKm, inducedDistance,
                baseTrips * baseDistanceKm, inducedPersonKm);
    }

    /**
     * Ergebnis einer induzierten-Nachfrage-Berechnung fuer einen Agenten/ein Segment.
     *
     * @param lambdaBase        Logsum der heutigen Welt (nur CA, PT)
     * @param lambdaAvm         Logsum der AV-Welt (verfuegbares Choice-Set)
     * @param deltaLogsum       lambdaAvm - lambdaBase
     * @param baseTrips         Wegeanzahl der heutigen Welt (T_n^base)
     * @param inducedTrips      Wegeanzahl der AV-Welt inkl. induzierter Nachfrage (T_n^ind)
     * @param baseDistanceKm    Wegedistanz der heutigen Welt in km (D_n^base)
     * @param inducedDistanceKm Wegedistanz der AV-Welt inkl. induzierter Nachfrage in km (D_n^ind)
     * @param basePersonKm      Personenkilometer der heutigen Welt (T_n^base * D_n^base)
     * @param inducedPersonKm   Personenkilometer der AV-Welt inkl. induzierter Nachfrage (VKM_n^ind)
     */
    public record Result(double lambdaBase, double lambdaAvm, double deltaLogsum,
                          double baseTrips, double inducedTrips,
                          double baseDistanceKm, double inducedDistanceKm,
                          double basePersonKm, double inducedPersonKm) {

        /** Additiver Wegezuwachs T_n^ind - T_n^base. */
        public double additionalTrips() {
            return inducedTrips - baseTrips;
        }

        /** Additiver Distanzzuwachs D_n^ind - D_n^base in km. */
        public double additionalDistanceKm() {
            return inducedDistanceKm - baseDistanceKm;
        }

        /** Additiver Personenkilometer-Zuwachs VKM_n^ind - VKM_n^base. */
        public double additionalPersonKm() {
            return inducedPersonKm - basePersonKm;
        }
    }
}
