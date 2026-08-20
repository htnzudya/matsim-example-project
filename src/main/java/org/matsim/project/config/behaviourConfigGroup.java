package org.matsim.project.config;

import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ReflectiveConfigGroup;
import org.matsim.project.model.alternatives;
import org.matsim.project.model.agentProfile;
import org.matsim.project.model.modeParams;
import org.matsim.project.model.segmentClassifier;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * Steuert die Grundmenge der Modusalternativen fuer die Moduswahl UND fuer
     * die Nullalternative-Ziehung (behaviourCandidateTripInserter): true (Default)
     * = alle fuenf Alternativen (CA/AV/PT/PSAV/SSAV, siehe alternatives.java),
     * false = nur CA/PT, AV/PSAV/SSAV werden aus dem Choice-Set entfernt.
     *
     * Grund: der "Basis"-Lauf des Basis-vs-AVM-Vergleichs (siehe Abschnitt
     * "Latente Nachfrage" der Auftraggeber-Spezifikation, Gl. \ref{eq:latent})
     * unterscheidet sich vom AVM-Lauf EINZIG darin, dass AVM-Modi nicht im
     * Choice-Set stehen - alles andere (Population, Kandidatenweg-Vorlagen,
     * randomSeed, ascNull) bleibt identisch, sonst waere eine Differenz der
     * outcome-Indikatoren nicht mehr eindeutig auf das erweiterte Choice-Set
     * zurueckzufuehren. Siehe behaviourDiscreteModeChoiceExtension.
     * provideModeAvailability() fuer die Anwendung.
     */
    private boolean avmModesEnabled = true;

    /**
     * ASC_0 der Nullalternative ("kein Kandidatenweg") im erweiterten
     * Choice-Set C_n+ = C_n ∪ {0} - siehe behaviourCandidateTripInserter-
     * Klassen-Javadoc. Die Nullalternative traegt bewusst KEINE weiteren
     * Terme (kein beta, kein gamma, kein delta) - nur diese eine Konstante.
     * PLATZHALTER-Default 0.0: zu kalibrieren, sodass die simulierte
     * Wegerate/Person/Tag im Basisszenario die erhobene Wegerate trifft,
     * danach fuer das AVM-Szenario fixiert (siehe Vorgabe der
     * Auftraggeber-Zusammenfassung).
     */
    private double ascNull = 0.0;

    /**
     * Name des Person-Attributs, das ein bereits vorhandenes Abo/Zeitkarte
     * anzeigt (Oberlausitz/Dresden liefert das als "ptTicket" mit, siehe
     * output_persons.csv eines echten Laufs: Werte "none"/"full", ~14 % der
     * Population "full"). Wird zusammen mit ticketOwnedValue genutzt, um
     * modeParams.effectiveCostPerKm(...) zu bestimmen (siehe dortigen Javadoc:
     * Grenzkosten einer Fahrt sind fuer Abo-Inhaber effektiv 0, nicht der volle
     * lineare Tarif). Fehlt das Attribut in einer Population, liefert
     * getAttribute(...) null -> hasTicket=false -> unveraendertes Verhalten
     * (normaler costPerKm), kein Absturz.
     */
    private String ticketAttribute = "ptTicket";

    /** Attributwert, der "hat Abo/Zeitkarte" bedeutet - siehe ticketAttribute-Javadoc. */
    private String ticketOwnedValue = "full";

    /**
     * Gewichte wA/wG/wH/wS/wR/wM und Temperatur T des Naive-Bayes-
     * Segmentklassifikators (segmentClassifier.classify), siehe dortigen
     * Klassen-Javadoc SCHRITT 3/4. wMotorisation=0.0 per Default: die
     * Motorisierungskomponente der Clusterzentren misst Haushaltsebene,
     * carAvail misst Personenebene (Cluster "oepnv_gebundener" ist
     * gegenlaeufig) - wMotorisation=1.0 nur als Sensitivitaetslauf.
     */
    private double classifierWeightAge = 1.0;
    private double classifierWeightGender = 1.0;
    private double classifierWeightHousehold = 1.0;
    private double classifierWeightIncome = 1.0;
    private double classifierWeightRegion = 1.0;
    private double classifierWeightMotorisation = 0.0;
    private double classifierTemperature = 1.0;

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

    /** Siehe avmModesEnabled-Feld-Javadoc. */
    @StringGetter("avmModesEnabled")
    public boolean getAvmModesEnabled() {
        return avmModesEnabled;
    }

    @StringSetter("avmModesEnabled")
    public void setAvmModesEnabled(boolean avmModesEnabled) {
        this.avmModesEnabled = avmModesEnabled;
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

    @StringGetter("ticketAttribute")
    public String getTicketAttribute() {
        return ticketAttribute;
    }

    @StringSetter("ticketAttribute")
    public void setTicketAttribute(String ticketAttribute) {
        this.ticketAttribute = ticketAttribute;
    }

    @StringGetter("ticketOwnedValue")
    public String getTicketOwnedValue() {
        return ticketOwnedValue;
    }

    @StringSetter("ticketOwnedValue")
    public void setTicketOwnedValue(String ticketOwnedValue) {
        this.ticketOwnedValue = ticketOwnedValue;
    }

    /** true, wenn person das konfigurierte ticketAttribute mit Wert ticketOwnedValue traegt. */
    public boolean hasTicket(Person person) {
        Object value = person.getAttributes().getAttribute(ticketAttribute);
        return ticketOwnedValue.equals(value == null ? null : value.toString());
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

    /** ASC_0 der Nullalternative, siehe Feld-Javadoc. */
    @StringGetter("ascNull")
    public double getAscNull() {
        return ascNull;
    }

    @StringSetter("ascNull")
    public void setAscNull(double ascNull) {
        this.ascNull = ascNull;
    }

    @StringGetter("classifierWeightAge")
    public double getClassifierWeightAge() { return classifierWeightAge; }

    @StringSetter("classifierWeightAge")
    public void setClassifierWeightAge(double classifierWeightAge) { this.classifierWeightAge = classifierWeightAge; }

    @StringGetter("classifierWeightGender")
    public double getClassifierWeightGender() { return classifierWeightGender; }

    @StringSetter("classifierWeightGender")
    public void setClassifierWeightGender(double classifierWeightGender) { this.classifierWeightGender = classifierWeightGender; }

    @StringGetter("classifierWeightHousehold")
    public double getClassifierWeightHousehold() { return classifierWeightHousehold; }

    @StringSetter("classifierWeightHousehold")
    public void setClassifierWeightHousehold(double classifierWeightHousehold) { this.classifierWeightHousehold = classifierWeightHousehold; }

    @StringGetter("classifierWeightIncome")
    public double getClassifierWeightIncome() { return classifierWeightIncome; }

    @StringSetter("classifierWeightIncome")
    public void setClassifierWeightIncome(double classifierWeightIncome) { this.classifierWeightIncome = classifierWeightIncome; }

    @StringGetter("classifierWeightRegion")
    public double getClassifierWeightRegion() { return classifierWeightRegion; }

    @StringSetter("classifierWeightRegion")
    public void setClassifierWeightRegion(double classifierWeightRegion) { this.classifierWeightRegion = classifierWeightRegion; }

    @StringGetter("classifierWeightMotorisation")
    public double getClassifierWeightMotorisation() { return classifierWeightMotorisation; }

    @StringSetter("classifierWeightMotorisation")
    public void setClassifierWeightMotorisation(double classifierWeightMotorisation) { this.classifierWeightMotorisation = classifierWeightMotorisation; }

    @StringGetter("classifierTemperature")
    public double getClassifierTemperature() { return classifierTemperature; }

    @StringSetter("classifierTemperature")
    public void setClassifierTemperature(double classifierTemperature) { this.classifierTemperature = classifierTemperature; }

    /** Baut aus der Config die Gewichte fuer segmentClassifier.classify(...). */
    public segmentClassifier.weights buildClassifierWeights() {
        return new segmentClassifier.weights(classifierWeightAge, classifierWeightGender, classifierWeightHousehold,
                classifierWeightIncome, classifierWeightRegion, classifierWeightMotorisation, classifierTemperature);
    }

    /**
     * Baut aus der Config die Clusterzentren fuer segmentClassifier.classify(...),
     * in der Reihenfolge der segmentParams-Bloecke in der Config-XML - Index 0
     * ist damit der Referenzcluster fuer die Alpha-Normierung dort (SCHRITT 5).
     */
    public List<segmentClassifier.clusterCenter> buildClusterCenters() {
        List<segmentClassifier.clusterCenter> result = new ArrayList<>();
        for (ConfigGroup cg : getParameterSets(SegmentParamSet.SET_NAME)) {
            result.add(((SegmentParamSet) cg).toClusterCenter());
        }
        return result;
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

        /** costPerKm-Override fuer Abo-Inhaber, siehe modeParams.costPerKmWithTicket-Javadoc. */
        private double costPerKmWithTicket = modeParams.NO_TICKET_OVERRIDE;

        /**
         * Traegheits-/Gewohnheitsbonus je vorherigem Modus, als kommaseparierte
         * Liste "vormodus=wert" (Schluessel = alternatives-Name, z. B. "CA").
         * Beispiel: "CA=1.73,PT=0,AV=0.95,PSAV=0.63,SSAV=0.95". Der Eintrag mit
         * demselben Modus wie dieses ModeParamSet ist der Bonus bei Beibehaltung
         * des Modus; alle anderen Eintraege erlauben asymmetrische Uebergaenge.
         */
        private String deltaByPreviousMode = "";

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

        @StringGetter("costPerKmWithTicket")
        public double getCostPerKmWithTicket() { return costPerKmWithTicket; }

        @StringSetter("costPerKmWithTicket")
        public void setCostPerKmWithTicket(double costPerKmWithTicket) { this.costPerKmWithTicket = costPerKmWithTicket; }

        @StringGetter("deltaByPreviousMode")
        public String getDeltaByPreviousMode() { return deltaByPreviousMode; }

        @StringSetter("deltaByPreviousMode")
        public void setDeltaByPreviousMode(String deltaByPreviousMode) { this.deltaByPreviousMode = deltaByPreviousMode; }

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
                    costPerKm, costPerKmWithTicket,
                    parseMap(deltaByPreviousMode),
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

        /**
         * Sprechender Name des Segments fuer den Output (persons_segment.csv),
         * siehe segmentClassifier.clusterCenter-Javadoc. Fehlt er (aeltere
         * Configs), wird segmentId auch als Name verwendet. Heisst NICHT
         * "name" - das ist bereits final auf ConfigGroup vergeben
         * (Parametersatz-Typname).
         */
        private String displayName = "";

        // Naive-Bayes-Clusterzentren (Hauslbauer/Schade/Petzoldt 2022, Tab. 2-2) -
        // Profilschicht fuer segmentClassifier.classify(...), siehe dortigen
        // Klassen-Javadoc. Unabhaengig von constructs/constructsSd oben (das sind
        // die latenten SLR-Konstrukte der Nutzenfunktion, hier dagegen
        // demografische Clusterzentren fuer die Segment-Zuordnung selbst).
        private double ageMu = 0.0;
        private double ageSd = 1.0;
        private double pFemale = 0.5;
        private double hhMu = 0.0;
        private double hhSd = 1.0;
        private double sesMu = 0.0;
        private double sesSd = 1.0;
        private double regMu = 0.0;
        private double regSd = 1.0;
        private double motMu = 0.0;
        private double motSd = 1.0;

        public SegmentParamSet() {
            super(SET_NAME);
        }

        @StringGetter("displayName")
        public String getDisplayName() { return displayName; }

        @StringSetter("displayName")
        public void setDisplayName(String displayName) { this.displayName = displayName; }

        @StringGetter("ageMu")
        public double getAgeMu() { return ageMu; }

        @StringSetter("ageMu")
        public void setAgeMu(double ageMu) { this.ageMu = ageMu; }

        @StringGetter("ageSd")
        public double getAgeSd() { return ageSd; }

        @StringSetter("ageSd")
        public void setAgeSd(double ageSd) { this.ageSd = ageSd; }

        @StringGetter("pFemale")
        public double getPFemale() { return pFemale; }

        @StringSetter("pFemale")
        public void setPFemale(double pFemale) { this.pFemale = pFemale; }

        @StringGetter("hhMu")
        public double getHhMu() { return hhMu; }

        @StringSetter("hhMu")
        public void setHhMu(double hhMu) { this.hhMu = hhMu; }

        @StringGetter("hhSd")
        public double getHhSd() { return hhSd; }

        @StringSetter("hhSd")
        public void setHhSd(double hhSd) { this.hhSd = hhSd; }

        @StringGetter("sesMu")
        public double getSesMu() { return sesMu; }

        @StringSetter("sesMu")
        public void setSesMu(double sesMu) { this.sesMu = sesMu; }

        @StringGetter("sesSd")
        public double getSesSd() { return sesSd; }

        @StringSetter("sesSd")
        public void setSesSd(double sesSd) { this.sesSd = sesSd; }

        @StringGetter("regMu")
        public double getRegMu() { return regMu; }

        @StringSetter("regMu")
        public void setRegMu(double regMu) { this.regMu = regMu; }

        @StringGetter("regSd")
        public double getRegSd() { return regSd; }

        @StringSetter("regSd")
        public void setRegSd(double regSd) { this.regSd = regSd; }

        @StringGetter("motMu")
        public double getMotMu() { return motMu; }

        @StringSetter("motMu")
        public void setMotMu(double motMu) { this.motMu = motMu; }

        @StringGetter("motSd")
        public double getMotSd() { return motSd; }

        @StringSetter("motSd")
        public void setMotSd(double motSd) { this.motSd = motSd; }

        public segmentClassifier.clusterCenter toClusterCenter() {
            String resolvedName = displayName.isBlank() ? segmentId : displayName;
            return new segmentClassifier.clusterCenter(segmentId, resolvedName, probability,
                    ageMu, ageSd, pFemale, hhMu, hhSd, sesMu, sesSd, regMu, regSd, motMu, motSd);
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