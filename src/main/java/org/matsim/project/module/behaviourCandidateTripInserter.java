package org.matsim.project.module;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.StartupListener;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.FacilitiesUtils;
import org.matsim.facilities.Facility;
import org.matsim.project.config.behaviourConfigGroup;
import org.matsim.project.model.TripContext;
import org.matsim.project.model.agentProfile;
import org.matsim.project.model.alternatives;
import org.matsim.project.model.behaviourUtilityFunction;
import org.matsim.project.model.modeParams;
import org.matsim.project.scoring.tripContextBuilder;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

import com.google.inject.Inject;

/**
 * NULLALTERNATIVE IM DCM (Auftraggeber-Vorgabe "Zusammenfassung fuer die
 * Entwickler-Agenten: Nullalternative im DCM").
 *
 * Ziel: jeder Agent bekommt zusaetzlich zu seinem erhobenen Wegeprogramm einen
 * KONSTRUIERTEN Kandidatenweg. Ob dieser Weg tatsaechlich stattfindet,
 * entscheidet dasselbe Nutzenmodell wie das laufende Moduswahl-DCM
 * (behaviourUtilityFunction/behaviourUtilityEstimator) - erweitert um eine
 * Nullalternative i=0 ("kein Weg"):
 *
 *   V_n0 = ASC_0                     (nur eine Konstante, kein beta/gamma/delta)
 *   C_n+ = C_n ∪ {0}                 (erweitertes Choice-Set)
 *   P(j) = softmax(V)_j              (UNVERAENDERTE Formel, siehe behaviourUtilityFunction.softmax)
 *
 * Realisierung ueber eine Ziehung u_n in [0,1], verglichen mit der kumulierten
 * Verteilung ueber C_n+ (behaviourUtilityFunction.drawFromCumulative) - kein
 * Schwellenwert-Vergleich. Faellt die Ziehung auf 0, bleibt der Plan
 * unveraendert; faellt sie auf einen Modus, wird der Kandidatenweg mit diesem
 * Modus eingefuegt (nur als Startloesung - die normale Moduswahl der
 * folgenden Iterationen kann ihn wie jeden anderen Weg weiter revidieren).
 *
 * WICHTIGE IMPLEMENTIERUNGSENTSCHEIDUNGEN (Auftraggeber-Vorgabe):
 *
 *  - Laeuft als StartupListener (notifyStartup), also VOR Iteration 0 und
 *    NICHT als Replanning-Strategie waehrend der Iterationen. notifyStartup
 *    feuert einmalig, bevor "ITERATION 0 BEGINS" geloggt wird, aber NACH dem
 *    Guice-Aufbau - wir bekommen also echten TripRouter/ActivityFacilities-
 *    Zugriff fuer realistische, geroutete LOS-Attribute (dieselbe
 *    Nutzenfunktion wie das laufende DCM, siehe Klassen-Javadoc oben).
 *
 *  - u_n UND die Kandidatenweg-Ziehung (Vorlage, Praeferenzkoeffizienten)
 *    werden deterministisch aus der Agenten-ID abgeleitet (siehe
 *    tripContextBuilder.personSeed, dasselbe Verfahren wie im laufenden DCM,
 *    behaviourUtilityEstimator-Klassen-Javadoc "FROZEN"), NICHT aus einem
 *    geteilten Random-Objekt - sonst waere das Ergebnis threadabhaengig UND
 *    die Differenz zwischen Basis- und AVM-Lauf teils Rauschen statt Effekt.
 *    Voraussetzung: randomSeed (verhaltensmodell-Config) ist in Basis- und
 *    AVM-Lauf IDENTISCH gesetzt - das liegt in der Verantwortung des
 *    Aufrufers (zwei Config-Dateien/Runs mit demselben randomSeed-Wert).
 *    Ueber die reine Vorgabe hinaus wird HIER auch die Ziehung des
 *    Kandidatenwegs selbst (Vorlage) sowie die gezogenen Mixed-Logit-
 *    Koeffizienten deterministisch aus derselben Seed-Quelle abgeleitet -
 *    sonst waere schon der Kandidatenweg zwischen Basis- und AVM-Lauf
 *    verschieden, und ein Unterschied im Ergebnis liesse sich nicht mehr
 *    eindeutig auf das unterschiedliche Choice-Set zurueckfuehren.
 *
 *  - ASC_0 kommt als einziger neuer Skalar aus der Config (verhaltensmodell.
 *    ascNull, siehe behaviourConfigGroup) - zu kalibrieren, bis die
 *    simulierte Wegerate/Person/Tag im Basisszenario die erhobene Wegerate
 *    trifft, danach fuer das AVM-Szenario fixiert. Dieses Add-on kalibriert
 *    NICHT selbst (kein Kalibrierungslauf hier) - das ist Aufgabe des
 *    Nutzers ueber die Config.
 *
 *  - Kandidatenweg-Attribute (Zweck, Ziel/Distanz, Startzeit, Dauer) werden
 *    NICHT unabhaengig gezogen, sondern als EIN gemeinsamer, real
 *    existierender Weg eines "vergleichbaren Agenten" (= gleiches Segment,
 *    siehe agentProfile/behaviourConfigGroup.buildSegments) aus der Population
 *    gezogen (collectTemplates(...)) - vermeidet unplausible Attribut-
 *    Kombinationen, die bei unabhaengigen Marginalziehungen entstehen
 *    koennten. Der Zweck wird dabei ZUFAELLIG aus allen tatsaechlich in der
 *    Population vorkommenden Nicht-Heim-Aktivitaeten gezogen, OHNE
 *    Einschraenkung auf diskretionaere Zwecke (Auftraggeber-Vorgabe "einfach
 *    immer random zuweisen" statt einer manuell zu pflegenden Zweck-Taxonomie
 *    je Szenario) - weicht damit von der urspruenglichen Spezifikation ("nur
 *    diskretionaere Zwecke, kein Pflichtweg") bewusst ab, siehe
 *    collectTemplates(...)-Javadoc.
 *    Hat das eigene Segment keine Vorlagen (bzw. hat die Person kein/ein
 *    unbekanntes Segment), wird auf den POPULATIONSWEITEN Vorlagen-Pool
 *    zurueckgegriffen - das deckt insbesondere Agenten OHNE jeden erhobenen
 *    Weg ab (0-Wege-Agenten koennen ohnehin keine eigene Verteilung liefern,
 *    bekommen ueber den Pool-Fallback aber trotzdem einen Kandidatenweg,
 *    siehe Anhang der Auftraggeber-Vorgabe "Agenten mit 0 Wegen sollen
 *    natuerlich auch einen Weg bekommen").
 *
 *  - Distanz wird NICHT separat gezogen: sie ergibt sich wie beim laufenden
 *    DCM implizit aus dem (Vorlagen-)Zielort nach dem Routing je Modus
 *    (siehe tripContextBuilder.buildTripContext) - konsistent mit
 *    behaviourUtilityEstimator, wo Distanz ebenfalls aus dem gerouteten Trip
 *    kommt statt aus einer eigenen Verteilung.
 *
 *  - Einfuegepunkt (ERSTE VERSION, bewusst eingeschraenkt statt riskant
 *    generisch): der Kandidatenweg wird als Hin-und-zurueck-Ausflug AN DAS
 *    ENDE DES TAGES angehaengt, d. h. NUR wenn die letzte Aktivitaet des
 *    Plans die Heimataktivitaet ist (cfg.getHomeActivityType()) - das deckt
 *    den ueblichen Fall (Tag endet zuhause) UND 0-Wege-Agenten (deren Plan
 *    aus genau einer, offenen Heimaktivitaet besteht) direkt ab, ohne
 *    bestehende, bereits terminierte Legs mitten im Tag zeitlich verschieben
 *    zu muessen (das wuerde bestehende Zeitfenster/Routen invalidieren).
 *    Endet der Tag NICHT zuhause, wird der Agent uebersprungen (gezaehlt,
 *    siehe Log-Zusammenfassung) - ein spaeterer Ausbauschritt koennte
 *    stattdessen mitten in eine passende Heimaktivitaet spleissen.
 *
 *  - Die neuen Legs werden UNGEROUTET eingefuegt (nur Modus + Abfahrtszeit) -
 *    MATSims PrepareForSim/PersonPrepareForSim routet sie automatisch vor
 *    Iteration 0 (laeuft nachweislich NACH allen notifyStartup-Listenern,
 *    siehe Log-Reihenfolge "all ControllerStartupListeners called" vor
 *    "PrepareForSimImpl"), exakt wie jeden anderen ungerouteten Leg einer
 *    frisch generierten Population. Die HIER berechneten, gerouteten
 *    Kandidaten (fuer die Nutzenbewertung je Modus) sind reine
 *    Zwischenergebnisse und werden verworfen - vermeidet die Komplexitaet,
 *    einen potenziell mehrelementigen gerouteten Trip (z. B. PT mit
 *    Umstiegen/Interaktionsaktivitaeten) an beliebiger Stelle in den Plan zu
 *    spleissen.
 */
public final class behaviourCandidateTripInserter implements StartupListener {

    private static final Logger log = LogManager.getLogger(behaviourCandidateTripInserter.class);

    /**
     * Ende des simulierten Tages in Sekunden, konsistent mit der bereits im
     * Projekt etablierten Konvention (z. B. RunOberlausitzDresdenTest/
     * behaviourDrtFleetSizeController: serviceEndTime(30*3600)).
     */
    static final double END_OF_DAY_SECONDS = 30 * 3600.0;

    /**
     * Personenattribut, in das das Ergebnis der Nullalternative-Entscheidung
     * geschrieben wird - landet dadurch automatisch als Spalte in
     * output_persons.csv (MATSim schreibt alle Personenattribute mit), ohne
     * eine eigene Output-Datei zu brauchen. Werte: "inserted:&lt;matsimMode&gt;"
     * (Kandidatenweg eingefuegt), "optedOut" (Nullalternative gewaehlt),
     * "skipped:notHomeEnd"/"skipped:noTemplate"/"skipped:noModeAvailable"
     * (siehe Log-Zusammenfassung fuer dieselben Kategorien).
     */
    static final String OUTCOME_ATTRIBUTE = "nullAlternativeOutcome";

    private final Population population;
    private final ActivityFacilities facilities;
    private final TripRouter tripRouter;
    private final TimeInterpretation timeInterpretation;
    private final behaviourUtilityFunction utilityFunction;
    private final behaviourModeAvailability modeAvailability;
    private final behaviourConfigGroup cfg;

    @Inject
    public behaviourCandidateTripInserter(Population population, ActivityFacilities facilities,
            TripRouter tripRouter, TimeInterpretation timeInterpretation, behaviourUtilityFunction utilityFunction,
            behaviourModeAvailability modeAvailability, behaviourConfigGroup cfg) {
        this.population = population;
        this.facilities = facilities;
        this.tripRouter = tripRouter;
        this.timeInterpretation = timeInterpretation;
        this.utilityFunction = utilityFunction;
        this.modeAvailability = modeAvailability;
        this.cfg = cfg;
    }

    /** Ein aus der Population gezogener, real existierender diskretionaerer Weg als Kandidatenweg-Vorlage. */
    private record candidateTripTemplate(Activity sourceActivity, double startTimeSeconds, double durationSeconds) {
    }

    /**
     * Bugfix: ein reiner homeType.equals(activity.getType())-Vergleich matcht bei
     * Oberlausitz/Dresden NIE (Aktivitaetstypen folgen der VSP-Konvention
     * "zweck_dauerInSekunden", z. B. "home_82200", niemals das nackte "home") -
     * die Folge war eine 100%ige "skipped:notHomeEnd"-Quote in echten Laeufen
     * (0 inserierte Zusatzwege). behaviourModule.parseActivityType(...) zerlegt
     * genau diese Konvention bereits an anderer Stelle (Scoring-Registrierung in
     * RunOberlausitzDresdenTest) - hier wiederverwendet statt eine zweite,
     * abweichende Parsing-Stelle zu schaffen. Funktioniert unveraendert fuer
     * Szenarien ohne Suffix-Konvention (z. B. Kelheim "home"): parseActivityType
     * liefert dort purpose()==type.
     */
    private static boolean isHomeActivity(String activityType, String homeType) {
        return homeType.equals(behaviourModule.parseActivityType(activityType).purpose());
    }

    @Override
    public void notifyStartup(StartupEvent event) {

        String homeType = cfg.getHomeActivityType();
        double ascNull = cfg.getAscNull();
        long randomSeed = cfg.getRandomSeed();
        String segmentAttribute = cfg.getSegmentAttribute();

        Map<alternatives, modeParams> modeParamsByAlternative = cfg.buildModeParams();
        Map<String, agentProfile> segmentsById = cfg.buildSegments();

        Map<String, List<candidateTripTemplate>> templatesBySegment = collectTemplates(homeType, segmentAttribute);
        List<candidateTripTemplate> allTemplates = templatesBySegment.values().stream()
                .flatMap(List::stream).toList();

        log.info("Nullalternative: " + allTemplates.size() + " Kandidatenweg-Vorlagen aus "
                + templatesBySegment.size() + " Segmenten gesammelt (zufaellig aus allen "
                + "Nicht-Heim-Aktivitaeten der Population, keine Zweck-Einschraenkung).");

        int total = 0, inserted = 0, skippedNotHomeEnd = 0, skippedNoTemplate = 0,
                skippedNoModeAvailable = 0, skippedNullChosen = 0;

        for (Person person : population.getPersons().values()) {
            total++;

            Plan plan = person.getSelectedPlan();
            List<PlanElement> elements = plan.getPlanElements();
            int lastIndex = elements.size() - 1;
            if (!(elements.get(lastIndex) instanceof Activity lastActivity) || !isHomeActivity(lastActivity.getType(), homeType)) {
                skippedNotHomeEnd++;
                person.getAttributes().putAttribute(OUTCOME_ATTRIBUTE, "skipped:notHomeEnd");
                continue;
            }

            List<candidateTripTemplate> pool = resolveTemplatePool(person, segmentAttribute, templatesBySegment, allTemplates);
            if (pool.isEmpty()) {
                skippedNoTemplate++;
                person.getAttributes().putAttribute(OUTCOME_ATTRIBUTE, "skipped:noTemplate");
                continue;
            }

            long templateSeed = tripContextBuilder.personSeed(randomSeed, person.getId(), "candidateTemplate");
            candidateTripTemplate template = pool.get(new Random(templateSeed).nextInt(pool.size()));

            double duration = template.durationSeconds();
            if (duration >= END_OF_DAY_SECONDS) {
                skippedNoTemplate++;
                person.getAttributes().putAttribute(OUTCOME_ATTRIBUTE, "skipped:noTemplate");
                continue;
            }
            double candidateStart = Math.max(0.0, Math.min(template.startTimeSeconds(), END_OF_DAY_SECONDS - duration));

            agentProfile profile = resolveProfile(person, segmentAttribute, segmentsById)
                    .draw(new Random(tripContextBuilder.personSeed(randomSeed, person.getId(), "profile")));

            Facility originFacility = FacilitiesUtils.toFacility(lastActivity, facilities);
            Activity destinationActivity = copyLocationAs(template.sourceActivity(), template.sourceActivity().getType());
            Facility destinationFacility = FacilitiesUtils.toFacility(destinationActivity, facilities);

            Map<Optional<alternatives>, Double> utilities = new LinkedHashMap<>();
            utilities.put(Optional.empty(), ascNull);

            Collection<String> availableModes = modeAvailability.getAvailableModes(person, List.of());
            for (alternatives alternative : modeParamsByAlternative.keySet()) {
                if (!availableModes.contains(alternative.getMatsimMode())) {
                    continue;
                }
                modeParams meanParams = modeParamsByAlternative.get(alternative);
                modeParams params = meanParams.draw(
                        new Random(tripContextBuilder.personSeed(randomSeed, person.getId(), alternative.name())));

                List<? extends PlanElement> routed;
                try {
                    routed = tripRouter.calcRoute(alternative.getMatsimMode(), originFacility, destinationFacility,
                            candidateStart, person, new AttributesImpl());
                } catch (RuntimeException e) {
                    log.debug("Nullalternative: Kandidatenweg fuer " + person.getId() + "/" + alternative
                            + " nicht routbar, Alternative wird uebersprungen.", e);
                    continue;
                }

                // Abo-/Zeitkarten-Inhaber wie im laufenden DCM (behaviourUtilityEstimator)
                // beruecksichtigen - dieselbe Nutzenfunktion, siehe Klassen-Javadoc.
                double costPerKm = params.effectiveCostPerKm(cfg.hasTicket(person));
                TripContext tripContext = tripContextBuilder.buildTripContext(
                        timeInterpretation, candidateStart, routed, costPerKm);
                utilities.put(Optional.of(alternative), utilityFunction.utility(profile, params, tripContext, null));
            }

            if (utilities.size() == 1) {
                // nur die Nullalternative selbst (kein Modus verfuegbar/routbar) - keine echte Wahl moeglich
                skippedNoModeAvailable++;
                person.getAttributes().putAttribute(OUTCOME_ATTRIBUTE, "skipped:noModeAvailable");
                continue;
            }

            Map<Optional<alternatives>, Double> probabilities = behaviourUtilityFunction.softmax(utilities, cfg.getScaleParameter());
            double draw = new Random(tripContextBuilder.personSeed(randomSeed, person.getId(), "nullAlternativeDraw")).nextDouble();
            Optional<alternatives> chosen = behaviourUtilityFunction.drawFromCumulative(probabilities, draw);

            if (chosen.isEmpty()) {
                skippedNullChosen++;
                person.getAttributes().putAttribute(OUTCOME_ATTRIBUTE, "optedOut");
                continue;
            }

            insertCandidateTrip(plan, lastIndex, lastActivity, chosen.get().getMatsimMode(),
                    destinationActivity, candidateStart, duration, homeType);
            person.getAttributes().putAttribute(OUTCOME_ATTRIBUTE, "inserted:" + chosen.get().getMatsimMode());
            inserted++;
        }

        log.info(String.format(
                "Nullalternative: %d/%d Agenten mit Kandidatenweg eingefuegt "
                        + "(Nullalternative gewaehlt: %d, Tag endet nicht zuhause: %d, "
                        + "keine Vorlage: %d, kein Modus verfuegbar/routbar: %d).",
                inserted, total, skippedNullChosen, skippedNotHomeEnd, skippedNoTemplate, skippedNoModeAvailable));
    }

    /**
     * Fuegt den Kandidatenweg als Hin-und-zurueck-Ausflug ans Ende des Tages
     * an, siehe Klassen-Javadoc "Einfuegepunkt". lastActivity wird verkuerzt
     * (endTime = candidateStart), danach folgen Leg -> Kandidatenaktivitaet ->
     * Leg -> neue, offene Heimaktivitaet (Kopie derselben Position wie
     * lastActivity).
     */
    private static void insertCandidateTrip(Plan plan, int lastIndex, Activity lastActivity, String mode,
            Activity destinationActivity, double candidateStart, double duration, String homeType) {

        lastActivity.setEndTime(candidateStart);

        Leg outboundLeg = PopulationUtils.createLeg(mode);
        outboundLeg.setDepartureTime(candidateStart);
        destinationActivity.setEndTime(candidateStart + duration);
        PopulationUtils.insertLegAct(plan, lastIndex + 1, outboundLeg, destinationActivity);

        Leg inboundLeg = PopulationUtils.createLeg(mode);
        inboundLeg.setDepartureTime(candidateStart + duration);
        Activity homeAgain = copyLocationAs(lastActivity, homeType);
        // homeAgain bleibt bewusst offen (kein setEndTime) - letzte Aktivitaet des Tages.
        PopulationUtils.insertLegAct(plan, lastIndex + 3, inboundLeg, homeAgain);
    }

    private static Activity copyLocationAs(Activity source, String type) {
        if (source.getFacilityId() != null) {
            return PopulationUtils.createActivityFromFacilityId(type, source.getFacilityId());
        } else if (source.getCoord() != null) {
            return PopulationUtils.createActivityFromCoord(type, source.getCoord());
        } else if (source.getLinkId() != null) {
            return PopulationUtils.createActivityFromLinkId(type, source.getLinkId());
        }
        throw new IllegalStateException(
                "Aktivitaet " + source + " hat weder facilityId noch coord noch linkId - "
                        + "kann daraus keinen Kandidatenweg-Ort ableiten.");
    }

    /** Segment-Aufloesung, identisch zu behaviourUtilityEstimator.resolveProfile (siehe dortigen Javadoc). */
    private static agentProfile resolveProfile(Person person, String segmentAttribute, Map<String, agentProfile> segmentsById) {
        Object value = person.getAttributes().getAttribute(segmentAttribute);
        agentProfile profile = value == null ? null : segmentsById.get(value.toString());
        return profile != null ? profile : new agentProfile("__neutral__", Map.of());
    }

    private static List<candidateTripTemplate> resolveTemplatePool(Person person, String segmentAttribute,
            Map<String, List<candidateTripTemplate>> templatesBySegment, List<candidateTripTemplate> allTemplates) {
        Object value = person.getAttributes().getAttribute(segmentAttribute);
        List<candidateTripTemplate> segmentPool = value == null ? null : templatesBySegment.get(value.toString());
        return (segmentPool == null || segmentPool.isEmpty()) ? allTemplates : segmentPool;
    }

    /**
     * Sammelt aus der GESAMTEN Population alle Nicht-Heim-Aktivitaeten mit
     * bekannter Ankunftszeit (vorangehender Leg mit definierter Abfahrtszeit -
     * die erste Aktivitaet eines Plans hat keine, liefert also keine Vorlage),
     * gruppiert nach dem Segment DES BEITRAGENDEN AGENTEN. "Vergleichbare
     * Agenten" = gleiches Segment, siehe Klassen-Javadoc.
     *
     * KEINE Zweck-Einschraenkung (z. B. auf diskretionaere Zwecke): der Zweck
     * wird zufaellig aus allen tatsaechlich vorkommenden Nicht-Heim-Aktivitaeten
     * gezogen (Auftraggeber-Vorgabe "einfach immer random zuweisen" statt
     * einer manuell zu pflegenden Zweck-Taxonomie je Szenario). Das weicht von
     * der urspruenglichen Spezifikation ("nur diskretionaere Zwecke, kein
     * Pflichtweg") bewusst ab - ein Kandidatenweg kann dadurch auch einen
     * Pflichtzweck (z. B. Arbeit) ziehen.
     */
    private Map<String, List<candidateTripTemplate>> collectTemplates(String homeType, String segmentAttribute) {
        Map<String, List<candidateTripTemplate>> result = new LinkedHashMap<>();

        for (Person person : population.getPersons().values()) {
            Object segmentValue = person.getAttributes().getAttribute(segmentAttribute);
            if (segmentValue == null) {
                continue;
            }
            String segmentId = segmentValue.toString();

            List<PlanElement> elements = person.getSelectedPlan().getPlanElements();
            for (int i = 1; i < elements.size(); i++) {
                if (!(elements.get(i) instanceof Activity activity) || isHomeActivity(activity.getType(), homeType)
                        || TripStructureUtils.isStageActivityType(activity.getType())) {
                    continue;
                }
                if (!(elements.get(i - 1) instanceof Leg precedingLeg) || precedingLeg.getDepartureTime().isUndefined()) {
                    continue;
                }
                double typicalDuration = behaviourModule.parseActivityType(activity.getType()).typicalDurationSeconds();
                candidateTripTemplate template = new candidateTripTemplate(
                        activity, precedingLeg.getDepartureTime().seconds(), typicalDuration);
                result.computeIfAbsent(segmentId, k -> new ArrayList<>()).add(template);
            }
        }
        return result;
    }
}
