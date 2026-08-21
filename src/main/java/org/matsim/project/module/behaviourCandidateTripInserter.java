package org.matsim.project.module;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.Config;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.StartupListener;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.geometry.CoordUtils;
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
    private final Config config;

    @Inject
    public behaviourCandidateTripInserter(Population population, ActivityFacilities facilities,
            TripRouter tripRouter, TimeInterpretation timeInterpretation, behaviourUtilityFunction utilityFunction,
            behaviourModeAvailability modeAvailability, behaviourConfigGroup cfg, Vehicles vehicles,
            Config config) {
        this.population = population;
        this.facilities = facilities;
        this.tripRouter = tripRouter;
        this.timeInterpretation = timeInterpretation;
        this.utilityFunction = utilityFunction;
        this.modeAvailability = modeAvailability;
        this.cfg = cfg;
        this.vehicles = vehicles;
        this.config = config;
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

    /**
     * Choice-Set der Nullalternative-Ziehung nach verhaltensmodell.
     * kandidatenwegWelt (Schritt 5 der Auftraggeber-Spezifikation
     * "Implementierungsspezifikation: Kandidatenwege mit Nullalternative"):
     * "base" = nur CA/PT (keine automatisierte Mobilitaet), "avm" = alle
     * fuenf Alternativen. Wirkt NUR hier auf den Kandidatenweg-Mechanismus -
     * die normale Moduswahl der uebrigen Wege (behaviourModeAvailability)
     * bleibt davon unberuehrt, siehe cfg.kandidatenwegWelt-Javadoc.
     */
    private static Set<alternatives> buildWorldChoiceSet(String kandidatenwegWelt) {
        if ("base".equalsIgnoreCase(kandidatenwegWelt)) {
            return EnumSet.of(alternatives.CA, alternatives.PT);
        }
        if (!"avm".equalsIgnoreCase(kandidatenwegWelt)) {
            throw new IllegalArgumentException("Unbekannter Wert '" + kandidatenwegWelt
                    + "' fuer verhaltensmodell.kandidatenwegWelt - erwartet 'base' oder 'avm'.");
        }
        return EnumSet.allOf(alternatives.class);
    }

    /**
     * Eine Zeile der Auswertungs-CSV (Schritt 8): "eine Zeile je Agent,
     * unabhaengig vom Ergebnis". entscheidung ist "WEG" (Kandidatenweg
     * eingefuegt) oder "NULLALTERNATIVE" - die Spezifikation kennt nur diese
     * zwei Werte, strukturelle Ueberspringungs-Faelle (kein Modus verfuegbar/
     * routbar, keine Vorlage, kein freier Zeitpunkt) werden hier ebenfalls
     * als NULLALTERNATIVE gefuehrt (es findet so oder so kein Weg statt),
     * mit leeren u/p0/distanz/zweck-Feldern, sofern zu diesem Zeitpunkt der
     * Verarbeitung noch nicht bekannt - siehe Anwendungsstellen in
     * notifyStartup. Ausnahme "kein Modus verfuegbar/routbar": dort ist
     * p0=1.0 deterministisch (nur die Nullalternative im Choice-Set, siehe
     * Spezifikation Testfall 7), auch wenn keine tatsaechliche Ziehung
     * stattfand.
     */
    private record kandidatenwegRow(String personId, String segment, Double distanzMeter, String zweck, Double u,
            Double p0, String entscheidung, String modus) {
    }

    @Override
    public void notifyStartup(StartupEvent event) {

        if (cfg.getAscNullKalibrierungAktiv()) {
            // Schritt 9 (Kalibrierung): ersetzt die normale Kandidatenweg-Einfuegung
            // komplett, siehe cfg.ascNullKalibrierungAktiv-Javadoc und
            // calibrateAscNull()-Javadoc.
            calibrateAscNull();
            return;
        }

        String homeType = cfg.getHomeActivityType();
        double ascNull = cfg.getAscNull();
        long randomSeed = cfg.getRandomSeed();
        String segmentAttribute = cfg.getSegmentAttribute();
        Set<alternatives> worldChoiceSet = buildWorldChoiceSet(cfg.getKandidatenwegWelt());
        List<kandidatenwegRow> csvRows = new ArrayList<>();
        List<logsumRow> logsumRows = new ArrayList<>();

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

            String personId = person.getId().toString();
            Object segmentValue = person.getAttributes().getAttribute(segmentAttribute);
            String segment = segmentValue == null ? "unbekannt" : segmentValue.toString();

            Plan plan = person.getSelectedPlan();
            List<PlanElement> elements = plan.getPlanElements();

            List<candidateTripTemplate> pool = resolveTemplatePool(person, segmentAttribute, templatesBySegment, allTemplates);
            if (pool.isEmpty()) {
                skippedNoTemplate++;
                person.getAttributes().putAttribute(OUTCOME_ATTRIBUTE, "skipped:noTemplate");
                csvRows.add(new kandidatenwegRow(personId, segment, null, null, null, null, "NULLALTERNATIVE", null));
                continue;
            }

            long templateSeed = tripContextBuilder.personSeed(randomSeed, person.getId(), "candidateTemplate");
            candidateTripTemplate template = pool.get(new Random(templateSeed).nextInt(pool.size()));
            String zweck = behaviourModule.parseActivityType(template.sourceActivity().getType()).purpose();

            double duration = template.durationSeconds();
            if (duration >= END_OF_DAY_SECONDS) {
                skippedNoTemplate++;
                person.getAttributes().putAttribute(OUTCOME_ATTRIBUTE, "skipped:noTemplate");
                csvRows.add(new kandidatenwegRow(personId, segment, null, zweck, null, null, "NULLALTERNATIVE", null));
                continue;
            }

            insertionPoint boundary = findInsertionBoundary(elements, template.startTimeSeconds());
            if (boundary == null) {
                // Restlicher Tag komplett durch Arbeit/Bildung blockiert - inkl. Sonderfall
                // "letzte, offene Aktivitaet ist selbst Arbeit/Bildung" (siehe
                // findInsertionBoundary-Javadoc). Kein freier Zeitpunkt fuer einen Zusatzweg.
                skippedNoFreeSlot++;
                person.getAttributes().putAttribute(OUTCOME_ATTRIBUTE, "skipped:noFreeSlot");
                csvRows.add(new kandidatenwegRow(personId, segment, null, zweck, null, null, "NULLALTERNATIVE", null));
                continue;
            }
            Activity boundaryActivity = boundary.activity();
            double candidateStart = boundary.candidateStart();

            // Luftliniendistanz Einfuegepunkt->Kandidatenziel (Schritt 8, Spalte "distanz") -
            // hier statt erst nach dem Tagesende-Check berechnen: braucht nur boundaryActivity
            // (bereits bekannt) und template.sourceActivity() (dieselbe Koordinate wie die
            // spaeter tatsaechlich verwendete destinationActivity, siehe copyLocationAs unten -
            // nur der Aktivitaetstyp aendert sich, nicht der Ort).
            double distanzMeter = CoordUtils.calcEuclideanDistance(
                    FacilitiesUtils.toFacility(boundaryActivity, facilities).getCoord(),
                    FacilitiesUtils.toFacility(template.sourceActivity(), facilities).getCoord());

            if (candidateStart + duration >= END_OF_DAY_SECONDS) {
                skippedNoTemplate++;
                person.getAttributes().putAttribute(OUTCOME_ATTRIBUTE, "skipped:noTemplate");
                csvRows.add(new kandidatenwegRow(personId, segment, distanzMeter, zweck, null, null, "NULLALTERNATIVE", null));
                continue;
            }

            agentProfile profile = resolveProfile(person, segmentAttribute, segmentsById)
                    .draw(new Random(tripContextBuilder.personSeed(randomSeed, person.getId(), "profile")));

            Facility originFacility = FacilitiesUtils.toFacility(boundaryActivity, facilities);
            Activity destinationActivity = copyLocationAs(template.sourceActivity(), template.sourceActivity().getType());
            Facility destinationFacility = FacilitiesUtils.toFacility(destinationActivity, facilities);

            Map<Optional<alternatives>, Double> utilities = new LinkedHashMap<>();
            utilities.put(Optional.empty(), ascNull);

            // Schritt 5 (Choice Set/Verfuegbarkeit): Basis-/AVM-Welt zuerst (worldChoiceSet),
            // danach Fuehrerschein/Fahrzeugzugang (modeAvailability, unveraendert fuer die
            // uebrige Moduswahl). CA/PAV(AV) "nur wenn carAvail gesetzt" und PSAV/SSAV "immer
            // verfuegbar" deckt modeAvailability bereits ab (siehe dortigen Javadoc). PT-
            // Anbindung bewusst OHNE eigene Distanzschwelle: PT ist wie im laufenden DCM
            // unbedingt verfuegbar (behaviourModeAvailability-Javadoc "fuer alle Personen
            // verfuegbar, keine Einschraenkung") - schlechte Anbindung wirkt stattdessen ueber
            // die reale, geroutete Wartezeit/Reisezeit auf den Nutzen (tripContextBuilder.
            // ptWaitTimeHours), also denselben weichen Mechanismus wie bei den uebrigen Wegen,
            // statt einen zweiten, hier eigenstaendig eingefuehrten harten Cutoff zu pflegen.
            Collection<String> availableModes = modeAvailability.getAvailableModes(person, List.of());
            for (alternatives alternative : modeParamsByAlternative.keySet()) {
                if (!worldChoiceSet.contains(alternative)) {
                    continue;
                }
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

            logsumRows.add(buildLogsumRow(personId, segment, utilities));

            if (utilities.size() == 1) {
                // nur die Nullalternative selbst (kein Modus verfuegbar/routbar) - keine echte Wahl
                // moeglich, P(0)=1 deterministisch (Spezifikation Testfall 7, siehe
                // kandidatenwegRow-Javadoc).
                skippedNoModeAvailable++;
                person.getAttributes().putAttribute(OUTCOME_ATTRIBUTE, "skipped:noModeAvailable");
                csvRows.add(new kandidatenwegRow(personId, segment, distanzMeter, zweck, null, 1.0, "NULLALTERNATIVE", null));
                continue;
            }

            Map<Optional<alternatives>, Double> probabilities = behaviourUtilityFunction.softmax(utilities, cfg.getScaleParameter());
            double draw = new Random(tripContextBuilder.personSeed(randomSeed, person.getId(), "nullAlternativeDraw")).nextDouble();
            Optional<alternatives> chosen = behaviourUtilityFunction.drawFromCumulative(probabilities, draw);
            double p0 = probabilities.get(Optional.<alternatives>empty());

            if (chosen.isEmpty()) {
                skippedNullChosen++;
                person.getAttributes().putAttribute(OUTCOME_ATTRIBUTE, "optedOut");
                csvRows.add(new kandidatenwegRow(personId, segment, distanzMeter, zweck, draw, p0, "NULLALTERNATIVE", null));
                continue;
            }

            insertCandidateTrip(plan, boundary.index(), boundaryActivity, boundary.appendAtDayEnd(),
                    chosen.get().getMatsimMode(), destinationActivity, candidateStart, duration);
            person.getAttributes().putAttribute(OUTCOME_ATTRIBUTE, "inserted:" + chosen.get().getMatsimMode());
            csvRows.add(new kandidatenwegRow(personId, segment, distanzMeter, zweck, draw, p0, "WEG", chosen.get().getMatsimMode()));
            inserted++;
        }

        log.info(String.format(
                "Nullalternative: %d/%d Agenten mit Kandidatenweg eingefuegt "
                        + "(Nullalternative gewaehlt: %d, kein freier Zeitpunkt (Arbeit/Bildung): %d, "
                        + "keine Vorlage: %d, kein Modus verfuegbar/routbar: %d).",
                inserted, total, skippedNullChosen, skippedNoFreeSlot, skippedNoTemplate, skippedNoModeAvailable));

        writeKandidatenwegeCsv(csvRows);
        writeLogsumCsv(logsumRows);
    }

    /**
     * Schreibt die Auswertungs-CSV (Schritt 8 der Spezifikation) ins
     * outputDirectory des Laufs: eine Zeile je Agent, unabhaengig vom
     * Ergebnis - siehe kandidatenwegRow-Javadoc fuer die Semantik der
     * Spalten und fuer die NULLALTERNATIVE-Zuordnung struktureller
     * Ueberspringungs-Faelle.
     */
    private void writeKandidatenwegeCsv(List<kandidatenwegRow> rows) {
        try {
            Path directory = Path.of(config.controller().getOutputDirectory());
            Files.createDirectories(directory);
            Path csvPath = directory.resolve("kandidatenwege.csv");

            StringBuilder sb = new StringBuilder();
            sb.append("personId;segment;distanz;zweck;u;p0;entscheidung;modus\n");
            for (kandidatenwegRow row : rows) {
                sb.append(row.personId()).append(';')
                        .append(row.segment()).append(';')
                        .append(row.distanzMeter() == null ? "" : String.format(Locale.ROOT, "%.3f", row.distanzMeter())).append(';')
                        .append(row.zweck() == null ? "" : row.zweck()).append(';')
                        .append(row.u() == null ? "" : String.format(Locale.ROOT, "%.6f", row.u())).append(';')
                        .append(row.p0() == null ? "" : String.format(Locale.ROOT, "%.6f", row.p0())).append(';')
                        .append(row.entscheidung()).append(';')
                        .append(row.modus() == null ? "" : row.modus())
                        .append('\n');
            }
            Files.writeString(csvPath, sb.toString(), StandardCharsets.UTF_8);
            log.info("Nullalternative: " + rows.size() + " Zeilen nach " + csvPath + " geschrieben.");
        } catch (IOException e) {
            throw new UncheckedIOException("kandidatenwege.csv konnte nicht geschrieben werden.", e);
        }
    }

    /**
     * Logsum je Person UND je Alternative (Konzept aus dem induzierte-
     * Nachfrage-Branch, siehe inducedDemandModel-Klassen-Javadoc dort:
     * Lambda_n = ln SUM_{i in C_n} exp(V_ni), unveraendert uebernommen ueber
     * behaviourUtilityFunction.logsum - hier NUR als Diagnose-Output, OHNE
     * den dortigen Generierungsmechanismus (growthFactor/induzierte Wege) zu
     * uebernehmen):
     *
     *   lambdaBase = Logsum ueber die heutige Welt (nur CA/PT, siehe
     *                inducedDemandModel.BASELINE_CHOICE_SET) - als TEILMENGE
     *                der bereits gerouteten realUtilities gebildet, KEIN
     *                zusaetzliches Routing noetig.
     *   lambdaAvm  = Logsum ueber das tatsaechlich verfuegbare/geroutete
     *                Choice-Set dieser Person (kann bei kandidatenwegWelt=
     *                base identisch zu lambdaBase sein - dann stehen ohnehin
     *                nur CA/PT im Choice-Set, siehe buildWorldChoiceSet).
     *   deltaLogsum = lambdaAvm - lambdaBase.
     *
     * "pro Alternative": zusaetzlich der einzelne Nutzenwert V_i je
     * Alternative (CA/AV/PT/PSAV/SSAV), leer, wenn fuer diese Person nicht
     * verfuegbar/routbar - das ist die Aufschluesselung, aus der sich
     * lambdaAvm zusammensetzt.
     */
    private record logsumRow(String personId, String segment, Double lambdaBase, Double lambdaAvm,
            Double deltaLogsum, Map<alternatives, Double> perAlternative) {
    }

    private logsumRow buildLogsumRow(String personId, String segment, Map<Optional<alternatives>, Double> utilities) {
        Map<alternatives, Double> realUtilities = new EnumMap<>(alternatives.class);
        for (Map.Entry<Optional<alternatives>, Double> entry : utilities.entrySet()) {
            entry.getKey().ifPresent(alternative -> realUtilities.put(alternative, entry.getValue()));
        }
        if (realUtilities.isEmpty()) {
            return new logsumRow(personId, segment, null, null, null, realUtilities);
        }
        double lambdaAvm = utilityFunction.logsum(realUtilities);

        // "Heutige Welt" = nur CA/PT (inducedDemandModel.BASELINE_CHOICE_SET im
        // induzierte-Nachfrage-Branch - die Klasse existiert in diesem Branch nicht
        // mehr, daher hier inline statt einer neuen Abhaengigkeit), dieselbe Menge
        // wie buildWorldChoiceSet("base").
        Map<alternatives, Double> baselineUtilities = new EnumMap<>(alternatives.class);
        for (alternatives baselineAlternative : EnumSet.of(alternatives.CA, alternatives.PT)) {
            Double v = realUtilities.get(baselineAlternative);
            if (v != null) {
                baselineUtilities.put(baselineAlternative, v);
            }
        }
        Double lambdaBase = baselineUtilities.isEmpty() ? null : utilityFunction.logsum(baselineUtilities);
        Double deltaLogsum = lambdaBase == null ? null : lambdaAvm - lambdaBase;

        return new logsumRow(personId, segment, lambdaBase, lambdaAvm, deltaLogsum, realUtilities);
    }

    /**
     * Schreibt logsum.csv (Diagnose-Output, siehe logsumRow-Javadoc) ins
     * outputDirectory: eine Zeile je Person, die die Nutzenberechnung
     * erreicht hat (nicht bei skipped:noTemplate/skipped:noFreeSlot - dort
     * gibt es kein geroutetes Choice-Set, ueber das ein Logsum sinnvoll
     * waere). V_&lt;alternative&gt;-Spalten in fester Reihenfolge (alternatives-
     * Enum-Deklaration: CA, AV, PT, PSAV, SSAV), leer wenn fuer diese Person
     * nicht verfuegbar/routbar.
     */
    private void writeLogsumCsv(List<logsumRow> rows) {
        try {
            Path directory = Path.of(config.controller().getOutputDirectory());
            Files.createDirectories(directory);
            Path csvPath = directory.resolve("logsum.csv");

            StringBuilder sb = new StringBuilder();
            sb.append("personId;segment;lambdaBase;lambdaAvm;deltaLogsum");
            for (alternatives alternative : alternatives.values()) {
                sb.append(";V_").append(alternative.name());
            }
            sb.append('\n');
            for (logsumRow row : rows) {
                sb.append(row.personId()).append(';')
                        .append(row.segment()).append(';')
                        .append(row.lambdaBase() == null ? "" : String.format(Locale.ROOT, "%.6f", row.lambdaBase())).append(';')
                        .append(row.lambdaAvm() == null ? "" : String.format(Locale.ROOT, "%.6f", row.lambdaAvm())).append(';')
                        .append(row.deltaLogsum() == null ? "" : String.format(Locale.ROOT, "%.6f", row.deltaLogsum()));
                for (alternatives alternative : alternatives.values()) {
                    Double v = row.perAlternative().get(alternative);
                    sb.append(';').append(v == null ? "" : String.format(Locale.ROOT, "%.6f", v));
                }
                sb.append('\n');
            }
            Files.writeString(csvPath, sb.toString(), StandardCharsets.UTF_8);
            log.info("Nullalternative: " + rows.size() + " Zeilen nach " + csvPath + " geschrieben.");
        } catch (IOException e) {
            throw new UncheckedIOException("logsum.csv konnte nicht geschrieben werden.", e);
        }
    }

    /** Phase-1-Ergebnis einer Person fuer die Kalibrierung: routingabhaengig, ascNull-UNABHAENGIG. */
    private record calibrationPersonContext(Map<alternatives, Double> realUtilities, double nullAlternativeDraw) {
    }

    /**
     * Schritt 9 (Kalibrierung): Bisektion auf ascNull, AUSSCHLIESSLICH in der
     * Basiswelt (CA/PT - unabhaengig vom konfigurierten kandidatenwegWelt,
     * siehe Spezifikation "darf dort nicht neu kalibriert werden - das waere
     * zirkulaer"), Ziel = cfg.ascNullKalibrierungZielanteil.
     *
     * WICHTIGE ABWEICHUNG von der Spezifikation ("ein Durchlauf ... dauert
     * Sekunden, 40 Durchlaeufe sind unkritisch"): das gilt fuer die in der
     * Spezifikation vorgesehene Analytik-LOS ohne Routing (Schritt 4). Dieses
     * Add-on routet dagegen echt (siehe Klassen-Javadoc) - ein 10pct-Lauf
     * braucht dafuer mehrere Minuten (siehe Git-Historie), 40x davon waere
     * unpraktikabel. Deshalb hier explizit in zwei Phasen getrennt: Phase 1
     * (teuer, EINMAL) berechnet je Person die gerouteten CA/PT-Nutzenwerte
     * UND die deterministische Ziehung nullAlternativeDraw - beide sind
     * unabhaengig von ascNull, das nur die Nullalternative selbst betrifft.
     * Phase 2 (billig, 40x) wiederholt nur noch Softmax+Ziehung ueber die in
     * Phase 1 bereits berechneten Werte - reine Arithmetik, kein Routing mehr.
     * Damit bleibt die Bisektion trotz echtem Routing praktikabel (eine
     * Routing-Phase statt vierzig).
     *
     * Strukturell ausgeschlossene Personen (keine Vorlage/kein freier
     * Zeitpunkt) zaehlen in den Nenner des Ziel-Anteils, tragen aber nie zum
     * WEG-Zaehler bei - unabhaengig von ascNull, siehe kandidatenwegRow-
     * Javadoc fuer dieselbe Logik in der normalen CSV.
     */
    private void calibrateAscNull() {
        String homeType = cfg.getHomeActivityType();
        long randomSeed = cfg.getRandomSeed();
        String segmentAttribute = cfg.getSegmentAttribute();
        double targetShare = cfg.getAscNullKalibrierungZielanteil();
        Set<alternatives> baseChoiceSet = EnumSet.of(alternatives.CA, alternatives.PT);

        Map<alternatives, modeParams> modeParamsByAlternative = cfg.buildModeParams();
        Map<String, agentProfile> segmentsById = cfg.buildSegments();
        Map<String, List<candidateTripTemplate>> templatesBySegment = collectTemplates(homeType, segmentAttribute);
        List<candidateTripTemplate> allTemplates = templatesBySegment.values().stream().flatMap(List::stream).toList();

        int totalPopulation = population.getPersons().size();
        log.info("ascNull-Kalibrierung: Phase 1 (Routing CA/PT, Basiswelt, einmalig) fuer "
                + totalPopulation + " Personen, Ziel-WEG-Anteil " + targetShare + "...");

        List<calibrationPersonContext> contexts = new ArrayList<>();
        for (Person person : population.getPersons().values()) {
            List<candidateTripTemplate> pool = resolveTemplatePool(person, segmentAttribute, templatesBySegment, allTemplates);
            if (pool.isEmpty()) {
                continue; // strukturell ausgeschlossen - siehe Methoden-Javadoc
            }
            long templateSeed = tripContextBuilder.personSeed(randomSeed, person.getId(), "candidateTemplate");
            candidateTripTemplate template = pool.get(new Random(templateSeed).nextInt(pool.size()));
            double duration = template.durationSeconds();
            if (duration >= END_OF_DAY_SECONDS) {
                continue;
            }
            List<PlanElement> elements = person.getSelectedPlan().getPlanElements();
            insertionPoint boundary = findInsertionBoundary(elements, template.startTimeSeconds());
            if (boundary == null) {
                continue;
            }
            Activity boundaryActivity = boundary.activity();
            double candidateStart = boundary.candidateStart();
            if (candidateStart + duration >= END_OF_DAY_SECONDS) {
                continue;
            }

            agentProfile profile = resolveProfile(person, segmentAttribute, segmentsById)
                    .draw(new Random(tripContextBuilder.personSeed(randomSeed, person.getId(), "profile")));
            Facility originFacility = FacilitiesUtils.toFacility(boundaryActivity, facilities);
            Activity destinationActivity = copyLocationAs(template.sourceActivity(), template.sourceActivity().getType());
            Facility destinationFacility = FacilitiesUtils.toFacility(destinationActivity, facilities);

            Collection<String> availableModes = modeAvailability.getAvailableModes(person, List.of());
            Map<alternatives, Double> realUtilities = new LinkedHashMap<>();
            for (alternatives alternative : modeParamsByAlternative.keySet()) {
                if (!baseChoiceSet.contains(alternative) || !availableModes.contains(alternative.getMatsimMode())) {
                    continue;
                }
                if (alternative == alternatives.CA) {
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
                    continue;
                }
                double costPerKm = params.effectiveCostPerKm(cfg.hasTicket(person));
                TripContext tripContext = tripContextBuilder.buildTripContext(
                        timeInterpretation, candidateStart, routed, costPerKm);
                realUtilities.put(alternative, utilityFunction.utility(profile, params, tripContext, null));
            }

            double draw = new Random(tripContextBuilder.personSeed(randomSeed, person.getId(), "nullAlternativeDraw")).nextDouble();
            contexts.add(new calibrationPersonContext(realUtilities, draw));
        }

        log.info("ascNull-Kalibrierung: Phase 1 fertig (" + contexts.size() + "/" + totalPopulation
                + " Personen teilnahmefaehig), Phase 2 (Bisektion, 40 Iterationen, kein weiteres Routing)...");

        double scaleParameter = cfg.getScaleParameter();
        double lo = -10.0, hi = 10.0, mid = 0.0;
        long wegCountAtMid = 0;
        for (int iteration = 0; iteration < 40; iteration++) {
            mid = (lo + hi) / 2.0;
            wegCountAtMid = 0;
            for (calibrationPersonContext context : contexts) {
                if (context.realUtilities().isEmpty()) {
                    continue; // kein Modus verfuegbar/routbar - immer Nullalternative, unabhaengig von ascNull
                }
                Map<Optional<alternatives>, Double> utilities = new LinkedHashMap<>();
                utilities.put(Optional.empty(), mid);
                for (Map.Entry<alternatives, Double> entry : context.realUtilities().entrySet()) {
                    utilities.put(Optional.of(entry.getKey()), entry.getValue());
                }
                Map<Optional<alternatives>, Double> probabilities = behaviourUtilityFunction.softmax(utilities, scaleParameter);
                if (behaviourUtilityFunction.drawFromCumulative(probabilities, context.nullAlternativeDraw()).isPresent()) {
                    wegCountAtMid++;
                }
            }
            double share = (double) wegCountAtMid / totalPopulation;
            // P(0) steigt monoton mit ascNull -> WEG-Anteil faellt monoton mit ascNull (siehe
            // Methoden-Javadoc): Anteil zu hoch -> ascNull erhoehen -> obere Haelfte weitersuchen.
            if (share > targetShare) {
                lo = mid;
            } else {
                hi = mid;
            }
        }

        double finalShare = (double) wegCountAtMid / totalPopulation;
        log.info(String.format(Locale.ROOT,
                "ascNull-Kalibrierung fertig: ascNull=%.6f (WEG-Anteil %.4f, Ziel %.4f, Basiswelt CA/PT, "
                        + "%d/%d Personen, 40 Bisektionsschritte).",
                mid, finalShare, targetShare, wegCountAtMid, totalPopulation));
        writeCalibrationResult(mid, targetShare, finalShare, wegCountAtMid, totalPopulation);

        // Kein Aufruf hier hat einen Sinn fuer einen reinen Kalibrierungslauf - die eigentlichen
        // MATSim-Iterationen (config.controller().lastIteration) wuerden nur die UNVERAENDERTEN
        // Basisplaene (kein Kandidatenweg eingefuegt, siehe notifyStartup-Guard) minutenlang
        // durchrechnen, ohne dass das Ergebnis irgendwo verwendet wird.
        log.info("ascNull-Kalibrierung: beende den Prozess (keine MATSim-Iterationen fuer einen "
                + "reinen Kalibrierungslauf noetig).");
        System.exit(0);
    }

    private void writeCalibrationResult(double ascNull, double targetShare, double achievedShare,
            long wegCount, int totalPopulation) {
        try {
            Path directory = Path.of(config.controller().getOutputDirectory());
            Files.createDirectories(directory);
            Path csvPath = directory.resolve("ascnull_kalibrierung.csv");
            String csv = "ascNull;zielAnteil;erreichterAnteil;wegCount;totalPopulation\n"
                    + String.format(Locale.ROOT, "%.6f;%.6f;%.6f;%d;%d\n",
                            ascNull, targetShare, achievedShare, wegCount, totalPopulation);
            Files.writeString(csvPath, csv, StandardCharsets.UTF_8);
            log.info("ascNull-Kalibrierung: Ergebnis nach " + csvPath + " geschrieben.");
        } catch (IOException e) {
            throw new UncheckedIOException("ascnull_kalibrierung.csv konnte nicht geschrieben werden.", e);
        }
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
