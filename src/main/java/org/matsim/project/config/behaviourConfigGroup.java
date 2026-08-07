package org.matsim.project.config;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ReflectiveConfigGroup;
import org.matsim.project.model.alternatives;
import org.matsim.project.model.agentProfile;
import org.matsim.project.model.modeParams;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Die Parameter-Schnittstelle des Add-ons.
 *
 * Liest den Block <module name="verhaltensmodell"> aus der MATSim-Config.
 * ALLE Koeffizienten kommen von hier - im Code steht kein einziger Zahlenwert.
 * Genau das macht das Modell parametrisierbar: Eine spaetere Simulation
 * rekalibriert ueber die XML, ohne den Code anzufassen.
 *
 * Struktur:
 *   modeParams    - je Alternative: ASC, beta InVehicleTime/WaitTime/Cost
 *                   (Mittelwert + SD), delta, gamma auf die latenten
 *                   Konstrukte (Mittelwert + SD)
 *   segmentParams - je Segment: Auspraegung der latenten Konstrukte
 *                   (Mittelwert + SD, z-Werte)
 *
 * HINWEIS: Diese Klasse haengt an der MATSim-API und muss in IntelliJ gegen
 * MATSim 2026.0 kompiliert werden. Der fachliche Kern (Paket model) ist
 * davon unabhaengig.
 */
public final class behaviourConfigGroup extends ReflectiveConfigGroup {

    public static final String GROUP_NAME = "verhaltensmodell";

    private double scaleParameter = 1.0;
    private long randomSeed = 4711L;
    private String segmentAttribute = "segment";

    /**
     * Aktivitaetstyp, der im jeweiligen Szenario die Heimataktivitaet markiert.
     * Wird an den TourFinder/HomeFinder des discrete_mode_choice-Contribs
     * durchgereicht (siehe behaviourModule.configureController). Default "home"
     * entspricht dem DMC-eigenen Default; Szenarien mit abweichender Aktivitaets-
     * Nomenklatur (z. B. equil: "h") setzen den Wert per Code oder XML.
     */
    private String homeActivityType = "home";

    public behaviourConfigGroup() {
        super(GROUP_NAME);
    }

    /**
     * Holt die Config-Gruppe aus der Config oder legt sie mit Default-Werten neu an.
     *
     * Falls der Block <module name="verhaltensmodell"> bereits aus einer XML-Datei
     * geparst wurde, liegt er zunaechst als generische ConfigGroup vor (MATSim kennt
     * unsere Klasse beim Parsen nicht) - ein einfacher Cast wuerde mit einer
     * ClassCastException abstuerzen. config.addModule(...) "materialisiert" diese
     * generische Gruppe stattdessen korrekt in unsere typisierte Klasse (kopiert alle
     * Params und Parametersets, siehe ConfigUtils.copyFromTo).
     */
    public static behaviourConfigGroup getOrCreate(Config config) {
        ConfigGroup existing = config.getModules().get(GROUP_NAME);
        if (existing instanceof behaviourConfigGroup typed) {
            return typed;
        }
        behaviourConfigGroup group = new behaviourConfigGroup();
        config.addModule(group);
        return group;
    }

    @StringGetter("homeActivityType")
    public String getHomeActivityType() {
        return homeActivityType;
    }

    @StringSetter("homeActivityType")
    public void setHomeActivityType(String homeActivityType) {
        this.homeActivityType = homeActivityType;
    }

    @StringGetter("scaleParameter")
    public double getScaleParameter() {
        return scaleParameter;
    }

    @StringSetter("scaleParameter")
    public void setScaleParameter(double scaleParameter) {
        this.scaleParameter = scaleParameter;
    }

    @StringGetter("randomSeed")
    public long getRandomSeed() {
        return randomSeed;
    }

    @StringSetter("randomSeed")
    public void setRandomSeed(long randomSeed) {
        this.randomSeed = randomSeed;
    }

    /** Name des Person-Attributs, in dem das Segment eines Agenten steht. */
    @StringGetter("segmentAttribute")
    public String getSegmentAttribute() {
        return segmentAttribute;
    }

    @StringSetter("segmentAttribute")
    public void setSegmentAttribute(String segmentAttribute) {
        this.segmentAttribute = segmentAttribute;
    }

    @Override
    public ConfigGroup createParameterSet(String type) {
        return switch (type) {
            case ModeParamSet.SET_NAME -> new ModeParamSet();
            case SegmentParamSet.SET_NAME -> new SegmentParamSet();
            default -> throw new IllegalArgumentException("Unbekannter Parametersatz: " + type);
        };
    }

    /** Baut aus der Config die Nutzenschicht (Modus -> Koeffizienten). */
    public Map<alternatives, modeParams> buildModeParams() {
        Map<alternatives, modeParams> result = new EnumMap<>(alternatives.class);
        for (ConfigGroup cg : getParameterSets(ModeParamSet.SET_NAME)) {
            ModeParamSet s = (ModeParamSet) cg;
            result.put(s.getAlternative(), s.toModeParams());
        }
        return result;
    }

    /** Baut aus der Config die Profilschicht (Segment -> latente Konstrukte). */
    public Map<String, agentProfile> buildSegments() {
        Map<String, agentProfile> result = new LinkedHashMap<>();
        for (ConfigGroup cg : getParameterSets(SegmentParamSet.SET_NAME)) {
            SegmentParamSet s = (SegmentParamSet) cg;
            result.put(s.getSegmentId(), s.toAgentProfile());
        }
        return result;
    }

    // ================= Parametersatz: ein Modus =================

    public static final class ModeParamSet extends ReflectiveConfigGroup {

        public static final String SET_NAME = "modeParams";

        private String mode;
        private double asc = 0.0;
        private double ascSd = 0.0;
        private double betaInVehicleTime = 0.0;
        private double betaInVehicleTimeSd = 0.0;
        private double betaWaitTime = 0.0;
        private double betaWaitTimeSd = 0.0;
        private double betaCost = 0.0;
        private double betaCostSd = 0.0;

        /** Tarif-/Betriebskostensatz in Euro/km, keine Streuung (siehe modeParams-Javadoc). */
        private double costPerKm = 0.0;

        private double delta = 0.0;

        /**
         * Die gamma-Koeffizienten auf die latenten Konstrukte als
         * kommaseparierte Liste "konstrukt=wert".
         * Beispiel: "techAffinity=0.45,carsharingAffinity=0.30"
         */
        private String gamma = "";

        /** Streuung der gamma-Koeffizienten, gleiches Format wie gamma. */
        private String gammaSd = "";

        public ModeParamSet() {
            super(SET_NAME);
        }

        @StringGetter("mode")
        public String getMode() {
            return mode;
        }

        @StringSetter("mode")
        public void setMode(String mode) {
            this.mode = mode;
        }

        @StringGetter("asc")
        public double getAsc() { return asc; }

        @StringSetter("asc")
        public void setAsc(double asc) { this.asc = asc; }

        @StringGetter("ascSd")
        public double getAscSd() { return ascSd; }

        @StringSetter("ascSd")
        public void setAscSd(double ascSd) { this.ascSd = ascSd; }

        @StringGetter("betaInVehicleTime")
        public double getBetaInVehicleTime() { return betaInVehicleTime; }

        @StringSetter("betaInVehicleTime")
        public void setBetaInVehicleTime(double betaInVehicleTime) { this.betaInVehicleTime = betaInVehicleTime; }

        @StringGetter("betaInVehicleTimeSd")
        public double getBetaInVehicleTimeSd() { return betaInVehicleTimeSd; }

        @StringSetter("betaInVehicleTimeSd")
        public void setBetaInVehicleTimeSd(double betaInVehicleTimeSd) { this.betaInVehicleTimeSd = betaInVehicleTimeSd; }

        @StringGetter("betaWaitTime")
        public double getBetaWaitTime() { return betaWaitTime; }

        @StringSetter("betaWaitTime")
        public void setBetaWaitTime(double betaWaitTime) { this.betaWaitTime = betaWaitTime; }

        @StringGetter("betaWaitTimeSd")
        public double getBetaWaitTimeSd() { return betaWaitTimeSd; }

        @StringSetter("betaWaitTimeSd")
        public void setBetaWaitTimeSd(double betaWaitTimeSd) { this.betaWaitTimeSd = betaWaitTimeSd; }

        @StringGetter("betaCost")
        public double getBetaCost() { return betaCost; }

        @StringSetter("betaCost")
        public void setBetaCost(double betaCost) { this.betaCost = betaCost; }

        @StringGetter("betaCostSd")
        public double getBetaCostSd() { return betaCostSd; }

        @StringSetter("betaCostSd")
        public void setBetaCostSd(double betaCostSd) { this.betaCostSd = betaCostSd; }

        @StringGetter("costPerKm")
        public double getCostPerKm() { return costPerKm; }

        @StringSetter("costPerKm")
        public void setCostPerKm(double costPerKm) { this.costPerKm = costPerKm; }

        @StringGetter("delta")
        public double getDelta() { return delta; }

        @StringSetter("delta")
        public void setDelta(double delta) { this.delta = delta; }

        @StringGetter("gamma")
        public String getGamma() { return gamma; }

        @StringSetter("gamma")
        public void setGamma(String gamma) { this.gamma = gamma; }

        @StringGetter("gammaSd")
        public String getGammaSd() { return gammaSd; }

        @StringSetter("gammaSd")
        public void setGammaSd(String gammaSd) { this.gammaSd = gammaSd; }

        public alternatives getAlternative() {
            return alternatives.valueOf(mode);
        }

        public modeParams toModeParams() {
            return new modeParams(
                    getAlternative(),
                    asc, ascSd,
                    betaInVehicleTime, betaInVehicleTimeSd,
                    betaWaitTime, betaWaitTimeSd,
                    betaCost, betaCostSd,
                    costPerKm,
                    delta,
                    parseMap(gamma),
                    parseMap(gammaSd));
        }
    }

    // ================= Parametersatz: ein Segment =================

    public static final class SegmentParamSet extends ReflectiveConfigGroup {

        public static final String SET_NAME = "segmentParams";

        private String segmentId;

        /** Anteil dieses Segments an der Grundgesamtheit (Summe ueber alle Segmente = 1,0). */
        private double probability = 0.0;

        /**
         * Auspraegung der latenten Konstrukte, kommasepariert "konstrukt=zWert".
         * Beispiel: "ptAffinity=0.68,techAffinity=0.61,carAffinity=-0.19"
         */
        private String constructs = "";

        /** Streuung der latenten Konstrukte, gleiches Format wie constructs. */
        private String constructsSd = "";

        public SegmentParamSet() {
            super(SET_NAME);
        }

        @StringGetter("segmentId")
        public String getSegmentId() { return segmentId; }

        @StringSetter("segmentId")
        public void setSegmentId(String segmentId) { this.segmentId = segmentId; }

        @StringGetter("probability")
        public double getProbability() { return probability; }

        @StringSetter("probability")
        public void setProbability(double probability) { this.probability = probability; }

        @StringGetter("constructs")
        public String getConstructs() { return constructs; }

        @StringSetter("constructs")
        public void setConstructs(String constructs) { this.constructs = constructs; }

        @StringGetter("constructsSd")
        public String getConstructsSd() { return constructsSd; }

        @StringSetter("constructsSd")
        public void setConstructsSd(String constructsSd) { this.constructsSd = constructsSd; }

        public agentProfile toAgentProfile() {
            return new agentProfile(segmentId, probability, parseMap(constructs), parseMap(constructsSd));
        }
    }

    // ================= Hilfsfunktion =================

    /** Parst "a=1.0,b=-0.5" zu einer Map. Leerstring ergibt eine leere Map. */
    static Map<String, Double> parseMap(String s) {
        Map<String, Double> map = new LinkedHashMap<>();
        if (s == null || s.isBlank()) {
            return map;
        }
        for (String teil : s.split(",")) {
            String[] kv = teil.trim().split("=");
            if (kv.length != 2) {
                throw new IllegalArgumentException(
                        "Ungueltiger Eintrag '" + teil + "'. Erwartet: konstrukt=wert");
            }
            map.put(kv[0].trim(), Double.parseDouble(kv[1].trim()));
        }
        return map;
    }
}