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
import org.matsim.api.core.v01.Id;
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
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

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
 *    ascNull, siehe behaviourConfigGroup) - kalibriert auf eine Zusatzweg-
 *    Einfuegequote von ~4% der Population (Studienvorgabe, siehe dortigen
 *    XML-Kommentar in scenarios/testszenario/config.xml fuer die konkreten
 *    Messpunkte). Dieses Add-on kalibriert NICHT selbst (kein Kalibrierungslauf
 *    hier) - das ist Aufgabe des Nutzers ueber die Config, ablesbar an der
 *    "X/Y Agenten mit Kandidatenweg eingefuegt"-Lognachricht unten.
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
 *  - Einfuegepunkt (ZWEITE VERSION - Auftraggeber-Feedback "Zusatzwege duerfen
 *    nicht ausnahmslos abends stattfinden, das ist nicht realistisch"): der
 *    Kandidatenweg wird NICHT mehr zwingend ans Tagesende gehaengt, sondern an
 *    die erste Stelle der BESTEHENDEN Wegekette des Agenten, an der er laut
 *    Plan tatsaechlich frei ist - siehe findInsertionBoundary(...). Ist der
 *    Agent zur gewuenschten Uhrzeit noch in einer laufenden Aktivitaet (z. B.
 *    Arbeit), IST deren end_time automatisch der naechste freie Zeitpunkt -
 *    kein Sonderfall fuer "Arbeit" noetig, das gilt fuer jede Aktivitaet
 *    gleichermassen. Der Rest der Wegekette (alles nach dem Einfuegepunkt)
 *    bleibt inhaltlich unveraendert und verschiebt sich nur um die Dauer des
 *    Zusatzwegs nach hinten (siehe insertCandidateTrip-Javadoc) - dieselbe
 *    ABSOLUT gesetzte end_time-Kette wie im Original, nur um duration
 *    versetzt. Als Vorlagen-Zweck sind "work" und alle "educ_*"-Zwecke
 *    ausgeschlossen (siehe collectTemplates-Javadoc) - ein Zusatzweg soll
 *    keine Pflichttermine wie Arbeit/Schule simulieren.
 *    Nebeneffekt: "Tag endet nicht zuhause" ist damit KEIN Ausschlussgrund
 *    mehr - jede Wegekette hat mindestens eine (die letzte, offene)
 *    Aktivitaet als gueltigen Fallback-Ankerpunkt, das deckt weiterhin auch
 *    0-Wege-Agenten ab.
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
    private final Vehicles vehicles;

    @Inject
    public behaviourCandidateTripInserter(Population population, ActivityFacilities facilities,
            TripRouter tripRouter, TimeInterpretation timeInterpretation, behaviourUtilityFunction utilityFunction,
            behaviourModeAvailability modeAvailability, behaviourConfigGroup cfg, Vehicles vehicles) {
        this.population = population;
        this.facilities = facilities;
        this.tripRouter = tripRouter;
        this.timeInterpretation = timeInterpretation;
        this.utilityFunction = utilityFunction;
        this.modeAvailability = modeAvailability;
        this.cfg = cfg;
        this.vehicles = vehicles;
    }

    /**
     * Legt bei Bedarf die Fahrzeug-ID + das Vehicle-Objekt fuer mode/person an -
     * siehe Aufrufstelle in notifyStartup fuer die Begruendung (CA/AV brauchen
     * das fuer netzwerkbasiertes Routing, sonst scheitert tripRouter.calcRoute
     * IMMER mit "Could not retrieve vehicle id from person"). Repliziert exakt
     * PrepareForSimImpl.createAndAddVehiclesForEveryNetworkMode(): Id ueber
     * VehicleUtils.createVehicleId(...) (identische Namenskonvention, sonst
     * wuerde PersonPrepareForSim spaeter eine ZWEITE, andere ID vergeben),
     * Vehicle-Instanz ueber Vehicles.getFactory().createVehicle(...) + addVehicle(...)
     * (uebersprungen, falls schon vorhanden), Zuordnung ueber
     * VehicleUtils.insertVehicleIdsIntoPersonAttributes(...). Setzt voraus, dass
     * bereits ein VehicleType mit Id.create(mode, VehicleType.class) registriert
     * ist (siehe behaviourModule.addVehicleTypesForModes, laeuft in
     * prepareScenario VOR notifyStartup) - sonst No-Op (Szenario nutzt dann
     * vermutlich defaultVehicle statt modeVehicleTypesFromVehiclesData, siehe
     * dortigen Javadoc).
     */
    private void ensureVehicleId(Person person, String mode) {
        if (VehicleUtils.hasVehicleId(person, mode)) {
            return;
        }
        VehicleType type = vehicles.getVehicleTypes().get(Id.create(mode, VehicleType.class));
        if (type == null) {
            return;
        }
        Id<Vehicle> vehicleId = VehicleUtils.createVehicleId(person, mode);
        if (!vehicles.getVehicles().containsKey(vehicleId)) {
            vehicles.addVehicle(vehicles.getFactory().createVehicle(vehicleId, type));
        }
        VehicleUtils.insertVehicleIdsIntoPersonAttributes(person, Map.of(mode, vehicleId));
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

        int total = 0, inserted = 0, skippedNoTemplate = 0, skippedNoFreeSlot = 0,
                skippedNoModeAvailable = 0, skippedNullChosen = 0;

        for (Person person : population.getPersons().values()) {
            total++;

            Plan plan = person.getSelectedPlan();
            List<PlanElement> elements = plan.getPlanElements();

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

            insertionPoint boundary = findInsertionBoundary(elements, template.startTimeSeconds());
            if (boundary == null) {
                // Restlicher Tag komplett durch Arbeit/Bildung blockiert - inkl. Sonderfall
                // "letzte, offene Aktivitaet ist selbst Arbeit/Bildung" (siehe
                // findInsertionBoundary-Javadoc). Kein freier Zeitpunkt fuer einen Zusatzweg.
                skippedNoFreeSlot++;
                person.getAttributes().putAttribute(OUTCOME_ATTRIBUTE, "skipped:noFreeSlot");
                continue;
            }
            Activity boundaryActivity = boundary.activity();
            double candidateStart = boundary.candidateStart();
            if (candidateStart + duration >= END_OF_DAY_SECONDS) {
                skippedNoTemplate++;
                person.getAttributes().putAttribute(OUTCOME_ATTRIBUTE, "skipped:noTemplate");
                continue;
            }

            agentProfile profile = resolveProfile(person, segmentAttribute, segmentsById)
                    .draw(new Random(tripContextBuilder.personSeed(randomSeed, person.getId(), "profile")));

            Facility originFacility = FacilitiesUtils.toFacility(boundaryActivity, facilities);
            Activity destinationActivity = copyLocationAs(template.sourceActivity(), template.sourceActivity().getType());
            Facility destinationFacility = FacilitiesUtils.toFacility(destinationActivity, facilities);

            Map<Optional<alternatives>, Double> utilities = new LinkedHashMap<>();
            utilities.put(Optional.empty(), ascNull);

            Collection<String> availableModes = modeAvailability.getAvailableModes(person, List.of());
            for (alternatives alternative : modeParamsByAlternative.keySet()) {
                if (!availableModes.contains(alternative.getMatsimMode())) {
                    continue;
                }
                // Bugfix: CA/AV brauchen fuer netzwerkbasiertes Routing eine Fahrzeug-ID je
                // Person (VehicleUtils.getVehicleId(...)) - die legt MATSim normalerweise erst
                // in PersonPrepareForSim an, das NACH allen notifyStartup-Listenern laeuft
                // (siehe Klassen-Javadoc). Ohne diesen Vorgriff scheitert JEDES CA/AV-Routing
                // hier IMMER mit "Could not retrieve vehicle id from person" - unabhaengig von
                // Distanz/Nutzenwerten. ensureVehicleId(...) legt sie bei Bedarf schon jetzt an,
                // exakt nach demselben Verfahren wie PrepareForSimImpl.
                // createAndAddVehiclesForEveryNetworkMode() (Id ueber VehicleUtils.
                // createVehicleId, Vehicle-Instanz ueber Vehicles.createAndAddVehicleIfNecessary,
                // Zuordnung ueber VehicleUtils.insertVehicleIdsIntoPersonAttributes) - spaeter
                // findet PersonPrepareForSim dann bereits eine bestehende ID vor und legt keine
                // zweite an (siehe dortiges hasVehicleId-Guard).
                if (alternative == alternatives.CA || alternative == alternatives.AV) {
                    ensureVehicleId(person, alternative.getMatsimMode());
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

            insertCandidateTrip(plan, boundary.index(), boundaryActivity, boundary.appendAtDayEnd(),
                    chosen.get().getMatsimMode(), destinationActivity, candidateStart, duration);
            person.getAttributes().putAttribute(OUTCOME_ATTRIBUTE, "inserted:" + chosen.get().getMatsimMode());
            inserted++;
        }

        log.info(String.format(
                "Nullalternative: %d/%d Agenten mit Kandidatenweg eingefuegt "
                        + "(Nullalternative gewaehlt: %d, kein freier Zeitpunkt (Arbeit/Bildung): %d, "
                        + "keine Vorlage: %d, kein Modus verfuegbar/routbar: %d).",
                inserted, total, skippedNullChosen, skippedNoFreeSlot, skippedNoTemplate, skippedNoModeAvailable));
    }

    /**
     * Ein moeglicher Einfuegepunkt fuer den Zusatzweg: die zu splittende
     * bestehende Aktivitaet des Agenten, der tatsaechliche Startzeitpunkt (kann
     * spaeter als die urspruengliche Wunschzeit der Vorlage liegen, siehe
     * findInsertionBoundary-Javadoc) sowie ob es sich um die letzte, offene
     * Aktivitaet des Tages handelt (appendAtDayEnd) - STRUKTURELL anhand der
     * Position im Plan bestimmt, NICHT anhand von activity.getEndTime()
     * (Bugfix, siehe findInsertionBoundary-Javadoc "Nicht-letzte Aktivitaet
     * ohne end_time").
     */
    private record insertionPoint(int index, Activity activity, double candidateStart, boolean appendAtDayEnd) {
    }

    /**
     * Arbeit/Bildung duerfen weder als Vorlagen-Zweck gezogen (siehe
     * collectTemplates) noch als laufende Aktivitaet fuer einen Zusatzweg
     * unterbrochen werden (siehe findInsertionBoundary) - unrealistisch, waehrend
     * eines Pflichttermins spontan einen Zusatzweg zu unternehmen. Jede andere
     * Aktivitaet (Freizeit, zuhause, Einkauf, Besuch, ...) darf dagegen
     * unterbrochen werden.
     */
    private static boolean isBlockingPurpose(String activityType) {
        String purpose = behaviourModule.parseActivityType(activityType).purpose();
        return "work".equals(purpose) || purpose.startsWith("educ");
    }

    /**
     * Sucht den fruehesten Zeitpunkt ab der gewuenschten Startzeit der Vorlage,
     * an dem der Zusatzweg in die BESTEHENDE Wegekette des Agenten passt -
     * Auftraggeber-Feedback "Zusatzwege duerfen nicht ausnahmslos abends
     * stattfinden, das ist nicht realistisch, ABER waehrend Arbeit/Bildung darf
     * er nicht stattfinden".
     *
     * Laeuft die Wegekette chronologisch durch: faellt die (ggf. bereits nach
     * hinten verschobene) Wunschzeit in eine Arbeits-/Bildungsaktivitaet
     * (isBlockingPurpose), wird auf deren end_time verschoben und mit der
     * naechsten Aktivitaet weitergeprueft. Faellt sie dagegen in JEDE ANDERE
     * Aktivitaet (auch mitten hinein, nicht nur in die Luecke danach), wird
     * genau dort gesplittet - siehe insertCandidateTrip.
     *
     * Nicht-letzte Aktivitaeten OHNE end_time (z. B. dauer-/max_dur-basiert
     * statt uhrzeit-basiert getaktet, oder - sollte der Plan zu diesem
     * fruehen Zeitpunkt bereits welche enthalten - Stage-/Interaktions-
     * aktivitaeten) werden uebersprungen, NIEMALS als Ankerpunkt gewaehlt:
     * es gibt dafuer keinen verlaesslichen Referenzzeitpunkt, um danach eine
     * zeitlich gueltige Rueckkehr-Aktivitaet zu bauen. Bugfix - vorher wurde
     * so eine Aktivitaet wie die (einzig erlaubte) letzte, offene Aktivitaet
     * behandelt, was mitten im Plan eine zweite "offene" Aktivitaet erzeugte
     * und MATSims Router beim ersten Routing mit "NoSuchElementException:
     * Undefined time" abstuerzen liess.
     *
     * @return die zu splittende Aktivitaet + tatsaechlicher Startzeitpunkt,
     *         oder null, wenn der GESAMTE restliche Tag durch Arbeit/Bildung
     *         blockiert ist (inkl. Sonderfall: die letzte, offene Aktivitaet
     *         ist selbst Arbeit/Bildung - dann gibt es keinen Fallback mehr).
     */
    private static insertionPoint findInsertionBoundary(List<PlanElement> elements, double desiredStartSeconds) {
        double earliestPossible = desiredStartSeconds;
        int lastIndex = elements.size() - 1;
        for (int i = 0; i < elements.size(); i += 2) {
            if (!(elements.get(i) instanceof Activity activity)) {
                continue; // strukturell nicht erreichbar, Plaene wechseln strikt Activity/Leg/Activity/...
            }
            boolean isLast = (i == lastIndex);
            boolean blocking = isBlockingPurpose(activity.getType());
            if (isLast) {
                return blocking ? null : new insertionPoint(i, activity, Math.max(0.0, earliestPossible), true);
            }
            if (activity.getEndTime().isUndefined()) {
                continue; // siehe Methoden-Javadoc - kein verlaesslicher Referenzzeitpunkt, ueberspringen
            }
            double thisEnd = activity.getEndTime().seconds();
            if (earliestPossible < thisEnd) {
                if (!blocking) {
                    return new insertionPoint(i, activity, Math.max(0.0, earliestPossible), false);
                }
                earliestPossible = thisEnd; // Arbeit/Bildung blockiert - fruehestens danach moeglich
            }
        }
        return null; // durch isLast oben unerreichbar, defensiv
    }

    /**
     * Splittet boundaryActivity bei candidateStart: der bestehende Teil endet
     * hier (statt ggf. erst spaeter oder gar nicht), danach folgen Leg ->
     * Kandidatenaktivitaet -> Leg -> Rueckkehr-Kopie von boundaryActivity
     * (gleicher Typ/Ort - der Agent setzt fort, was er vorher tat, statt
     * zwingend nachhause zurueckzukehren).
     *
     * War boundaryActivity die letzte, OFFENE Aktivitaet des Tages, bleibt die
     * Rueckkehr-Kopie ebenfalls offen (Tagesende, wie zuvor). Hatte sie dagegen
     * bereits eine feste end_time, behaelt die Kopie GENAU DIESE end_time -
     * der Rest des Tages bleibt inhaltlich unveraendert; reicht die Luecke bis
     * dahin nicht fuer die volle Zusatzweg-Dauer, verschiebt sich nur der
     * Ueberhang (max(...)) nach hinten, nicht der gesamte Resttag.
     */
    private static void insertCandidateTrip(Plan plan, int boundaryIndex, Activity boundaryActivity,
            boolean appendAtDayEnd, String mode, Activity destinationActivity, double candidateStart, double duration) {

        // originalEnd VOR dem Kuerzen sichern - appendAtDayEnd kommt strukturell (Position im
        // Plan) aus findInsertionBoundary, NICHT aus getEndTime().isUndefined() (Bugfix, siehe
        // dortigen Javadoc) - boundaryActivity kann daher hier auch im Nicht-Tagesende-Fall
        // sicher als "hatte eine definierte end_time" behandelt werden.
        double originalEnd = appendAtDayEnd ? Double.NaN : boundaryActivity.getEndTime().seconds();

        boundaryActivity.setEndTime(candidateStart);

        Leg outboundLeg = PopulationUtils.createLeg(mode);
        outboundLeg.setDepartureTime(candidateStart);
        destinationActivity.setEndTime(candidateStart + duration);
        PopulationUtils.insertLegAct(plan, boundaryIndex + 1, outboundLeg, destinationActivity);

        Leg inboundLeg = PopulationUtils.createLeg(mode);
        inboundLeg.setDepartureTime(candidateStart + duration);
        // Bugfix (galt urspruenglich fuer den Tagesende-Sonderfall, gilt hier analog fuer
        // jede Rueckkehr-Kopie): der ABSTRAKTE homeType ("home") ist bei VSP-Konvention-
        // Szenarien nie als konkreter Aktivitaetstyp in den Scoring-Parametern registriert
        // (nur "home_<dauer>") - boundaryActivity.getType() ist dagegen der KONKRETE Typ,
        // unter dem die Aktivitaet bereits registriert ist.
        Activity returnActivity = copyLocationAs(boundaryActivity, boundaryActivity.getType());
        if (!appendAtDayEnd) {
            returnActivity.setEndTime(Math.max(originalEnd, candidateStart + duration));
        }
        // sonst: returnActivity bleibt offen (kein setEndTime) - letzte Aktivitaet des Tages.
        PopulationUtils.insertLegAct(plan, boundaryIndex + 3, inboundLeg, returnActivity);
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
     * Zweck-Einschraenkung: Arbeit und alle Bildungszwecke (educ_*) sind
     * ausgeschlossen (isBlockingPurpose) - ein spontaner Zusatzweg soll keinen
     * Pflichttermin wie Arbeit/Schule simulieren. Sonst wird der Zweck
     * zufaellig aus allen tatsaechlich vorkommenden Nicht-Heim-Aktivitaeten
     * gezogen (Auftraggeber-Vorgabe "einfach immer random zuweisen" statt
     * einer manuell zu pflegenden Zweck-Taxonomie je Szenario).
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
                        || isBlockingPurpose(activity.getType())
                        || TripStructureUtils.isStageActivityType(activity.getType())) {
                    continue;
                }
                // Bugfix: bei frisch geladenen (noch ungerouteten) Plaenen ist
                // Leg.getDepartureTime() IMMER UNDEFINED - die Abfahrtszeit steckt zu diesem
                // Zeitpunkt nur in der end_time der VORAUSGEHENDEN Aktivitaet (elements.get(i-2),
                // durch die Plan-Struktur Activity/Leg/Activity/... immer eine Activity, sobald
                // elements.get(i) selbst eine Activity ist). Ein reiner precedingLeg.getDepartureTime()-
                // Check verwarf dadurch AUSNAHMSLOS jede Kandidatenaktivitaet (0 Vorlagen aus 0
                // Segmenten in echten Laeufen), nicht nur bei fehlender Zeitangabe.
                if (!(elements.get(i - 1) instanceof Leg) || i < 2
                        || !(elements.get(i - 2) instanceof Activity originActivity)
                        || originActivity.getEndTime().isUndefined()) {
                    continue;
                }
                double typicalDuration = behaviourModule.parseActivityType(activity.getType()).typicalDurationSeconds();
                candidateTripTemplate template = new candidateTripTemplate(
                        activity, originActivity.getEndTime().seconds(), typicalDuration);
                result.computeIfAbsent(segmentId, k -> new ArrayList<>()).add(template);
            }
        }
        return result;
    }
}
