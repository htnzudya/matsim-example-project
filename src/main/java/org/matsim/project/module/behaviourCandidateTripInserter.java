package org.matsim.project.module;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
import org.matsim.api.core.v01.Coord;
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
 *  - NACHBARSCHAFTSAUSWAHL (ersetzt die fruehere segmentbasierte Zufallsziehung):
 *    Kandidatenweg-Attribute (Zweck, Ziel/Distanz, Startzeit, Dauer) werden
 *    NICHT unabhaengig gezogen, sondern als EIN gemeinsamer, real
 *    existierender Weg eines ANDEREN Agenten der GESAMTEN Population
 *    uebernommen (collectTemplateProviders(...)/homeLocationGrid/
 *    selectNearestFittingTemplate(...)) - vermeidet unplausible Attribut-
 *    Kombinationen, die bei unabhaengigen Marginalziehungen entstehen
 *    koennten. "Vergleichbar" bedeutet dabei NICHT mehr gleiches Segment,
 *    sondern geografische Naehe: gesucht wird - beginnend beim naechsten
 *    Nachbarn (kuerzeste euklidische Distanz der Heimatkoordinaten,
 *    unabhaengig vom Segment) und mit wachsendem Suchradius - der erste
 *    Nachbar, dessen Vorlagen-Weg TATSAECHLICH in die bestehende Wegekette
 *    der Zielperson passt. Qualifizierend ist dabei nur ein DIREKTER
 *    Hin-/Rueckweg von/nach Hause (Aktivitaet unmittelbar von einer
 *    Heimataktivitaet aus erreicht UND unmittelbar von einer Heimataktivitaet
 *    gefolgt, siehe collectTemplateProviders-Javadoc) - kein Pflichtweg
 *    (Arbeit/Bildung sind als Vorlagen-Zweck ausgeschlossen) und keine
 *    laengere Wegekette mit weiteren Zwischenstopps.
 *    Passt der Weg des naechsten Nachbarn nicht (z. B. Tag der Zielperson
 *    schon komplett durch Arbeit/Bildung verplant), wird NICHT die ganze
 *    Person uebersprungen, sondern beim naechstnaeheren Nachbarn
 *    weitergesucht (Auftraggeber-Feedback "wenn Aktivitaetsplaene schon fuer
 *    den Tag voll, skippe einfach zu einem naechsten Agenten") - erst wenn
 *    KEIN Nachbar der gesamten Population einen passenden Weg liefert, gilt
 *    die Person als strukturell uebersprungen (skipped:noFreeSlot). Der
 *    Zweck selbst ergibt sich dabei automatisch aus dem gewaehlten
 *    Nachbar-Weg, ohne eigene Zweck-Taxonomie je Szenario pflegen zu muessen.
 *    0-Wege-Agenten (kein eigener erhobener Weg) sind davon unberuehrt - die
 *    Nachbarschaftssuche braucht nur die Heimatkoordinate der Zielperson,
 *    nicht deren eigene Wegekette.
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
 *    ausgeschlossen (siehe collectTemplateProviders-Javadoc) - ein Zusatzweg soll
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
        ensureVehicleId(vehicles, person, mode);
    }

    /**
     * Statische, wiederverwendbare Fassung (siehe Methoden-Javadoc oben) -
     * package-private, damit auch behaviourBaselineAscCalibrator (dasselbe
     * Paket) sie fuer CA-Routing waehrend der ASC-Kalibrierung nutzen kann,
     * ohne die Logik ein zweites Mal zu pflegen.
     */
    static void ensureVehicleId(Vehicles vehicles, Person person, String mode) {
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
     * Ein Agent mit mindestens einem qualifizierenden Kandidatenweg (direkter
     * Hin-/Rueckweg von/nach Hause, siehe collectTemplateProviders-Javadoc)
     * sowie dessen Heimatkoordinate - Grundlage der Nachbarschaftssuche
     * (siehe Klassen-Javadoc "Nachbarschaftsauswahl").
     */
    private record templateProvider(Id<Person> personId, Coord homeCoord, List<candidateTripTemplate> templates) {
    }

    /** Ergebnis der Nachbarschaftssuche: Nachbar + dessen passende Vorlage + der bereits validierte Einfuegepunkt. */
    private record templateSelection(templateProvider provider, candidateTripTemplate template, insertionPoint boundary) {
    }

    /**
     * Einfacher Gitter-Index ueber die Heimatkoordinaten aller templateProvider,
     * fuer eine entfernungsaufsteigende Nachbarschaftssuche (Ring-Expansion um
     * die Gitterzelle der Zielkoordinate) ohne O(n)-Sortierung je Zielperson -
     * noetig, weil die Suche fuer JEDE Person der Population laufen kann
     * (siehe notifyStartup/calibrateAscNull); ein voller Sortierlauf je Person
     * waere bei grossen Populationen (z. B. hoehere Prozentsaetze auf einem
     * Server mit mehr RAM) nicht mehr praktikabel (O(n^2 log n)).
     *
     * Zellgroesse so gewaehlt, dass im Mittel ein Provider je Zelle liegt
     * (sqrt(Flaeche/Anzahl)) - bei ungefaehr gleichverteilten Heimatkoordinaten
     * (Siedlungsflaeche) findet die Ringsuche die naechsten Nachbarn i. d. R.
     * schon im ersten oder zweiten Ring. expandingRing(...) liefert genau die
     * Provider einer einzelnen Ringschale (Tschebyschow-Distanz == ring, kein
     * Ueberlapp zwischen Ringen) - die Sicherheitsbedingung "kleinste bisher
     * gefundene Distanz &lt;= ring*cellSize" im Aufrufer
     * (selectNearestFittingTemplate) stellt sicher, dass kein naeherer
     * Provider in einem noch nicht durchsuchten, weiter entfernten Ring
     * uebersehen wird (Standardargument der Gitter-Ringsuche).
     */
    private static final class homeLocationGrid {
        private final double cellSize;
        private final int minCellX, minCellY, maxCellX, maxCellY;
        private final Map<Long, List<templateProvider>> cells = new LinkedHashMap<>();

        homeLocationGrid(List<templateProvider> providers) {
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
            double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (templateProvider provider : providers) {
                minX = Math.min(minX, provider.homeCoord().getX());
                maxX = Math.max(maxX, provider.homeCoord().getX());
                minY = Math.min(minY, provider.homeCoord().getY());
                maxY = Math.max(maxY, provider.homeCoord().getY());
            }
            double area = Math.max(1.0, (maxX - minX) * (maxY - minY));
            this.cellSize = Math.max(1.0, Math.sqrt(area / Math.max(1, providers.size())));

            int loX = Integer.MAX_VALUE, loY = Integer.MAX_VALUE, hiX = Integer.MIN_VALUE, hiY = Integer.MIN_VALUE;
            for (templateProvider provider : providers) {
                int cx = cellCoord(provider.homeCoord().getX());
                int cy = cellCoord(provider.homeCoord().getY());
                cells.computeIfAbsent(cellKey(cx, cy), k -> new ArrayList<>()).add(provider);
                loX = Math.min(loX, cx);
                loY = Math.min(loY, cy);
                hiX = Math.max(hiX, cx);
                hiY = Math.max(hiY, cy);
            }
            this.minCellX = providers.isEmpty() ? 0 : loX;
            this.minCellY = providers.isEmpty() ? 0 : loY;
            this.maxCellX = providers.isEmpty() ? 0 : hiX;
            this.maxCellY = providers.isEmpty() ? 0 : hiY;
        }

        private int cellCoord(double ordinate) {
            return (int) Math.floor(ordinate / cellSize);
        }

        private static long cellKey(int cx, int cy) {
            return (((long) cx) << 32) ^ (cy & 0xffffffffL);
        }

        double cellSize() {
            return cellSize;
        }

        /** Groesster Ring, ab dem die gesamte Population sicher durchsucht ist (Suchabbruch-Garantie). */
        int maxRing(Coord query) {
            int qx = cellCoord(query.getX());
            int qy = cellCoord(query.getY());
            int reach = Math.max(
                    Math.max(Math.abs(qx - minCellX), Math.abs(qx - maxCellX)),
                    Math.max(Math.abs(qy - minCellY), Math.abs(qy - maxCellY)));
            return Math.max(1, reach + 1);
        }

        /** Alle Provider, deren Gitterzelle genau im Tschebyschow-Abstand 'ring' um query liegt. */
        List<templateProvider> expandingRing(Coord query, int ring) {
            int qx = cellCoord(query.getX());
            int qy = cellCoord(query.getY());
            List<templateProvider> result = new ArrayList<>();
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dy = -ring; dy <= ring; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != ring) {
                        continue;
                    }
                    List<templateProvider> bucket = cells.get(cellKey(qx + dx, qy + dy));
                    if (bucket != null) {
                        result.addAll(bucket);
                    }
                }
            }
            return result;
        }
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
    /**
     * Die fuenf "Hightech"-Alternativen, fuer die der Kandidatenweg-Mechanismus
     * ueberhaupt gedacht ist (siehe Klassen-Javadoc). BEWUSST NICHT
     * EnumSet.allOf(alternatives.class): seit BIKE/WALK/RIDE Teil des Enums
     * sind (siehe alternatives-Klassen-Javadoc), wuerde "allOf" sie hier
     * faelschlich als moegliches Kandidatenweg-Verkehrsmittel zulassen - die
     * Nullalternative-Entscheidung soll aber unveraendert nur zwischen CA/AV/
     * PT/PSAV/SSAV (bzw. in der Basiswelt nur CA/PT) und "kein Weg" waehlen.
     */
    private static final Set<alternatives> AVM_CHOICE_SET = EnumSet.of(
            alternatives.CA, alternatives.AV, alternatives.PT, alternatives.PSAV, alternatives.SSAV);

    private static Set<alternatives> buildWorldChoiceSet(String kandidatenwegWelt) {
        if ("base".equalsIgnoreCase(kandidatenwegWelt)) {
            return EnumSet.of(alternatives.CA, alternatives.PT);
        }
        if (!"avm".equalsIgnoreCase(kandidatenwegWelt)) {
            throw new IllegalArgumentException("Unbekannter Wert '" + kandidatenwegWelt
                    + "' fuer verhaltensmodell.kandidatenwegWelt - erwartet 'base' oder 'avm'.");
        }
        return AVM_CHOICE_SET;
    }

    /**
     * Eine Zeile der Auswertungs-CSV (Schritt 8): "eine Zeile je Agent,
     * unabhaengig vom Ergebnis". entscheidung ist "WEG" (Kandidatenweg
     * eingefuegt) oder "NULLALTERNATIVE" - die Spezifikation kennt nur diese
     * zwei Werte, strukturelle Ueberspringungs-Faelle (kein Modus verfuegbar/
     * routbar, keine Vorlage, kein freier Zeitpunkt) werden hier ebenfalls
     * als NULLALTERNATIVE gefuehrt (es findet so oder so kein Weg statt),
     * mit leeren Feldern, sofern zu diesem Zeitpunkt der Verarbeitung noch
     * nicht bekannt - siehe Anwendungsstellen in notifyStartup.
     *
     * GLOBALE KONVERSIONSRATE f (Korrektur vom 2026-08-29, ersetzt die vorherige
     * Version die den Kandidatenweg direkt per Softmax ueber das komplette
     * avm-Choice-Set entschied): ascNull ist AUSSCHLIESSLICH in der Basiswelt
     * (CA/PT) kalibriert (siehe calibrateAscNull-Javadoc) - die direkte
     * Anwendung von ascNull auf ein groesseres avm-Choice-Set war inkonsistent
     * (mehr/attraktivere Alternativen im Softmax-Nenner senken p0 automatisch,
     * unabhaengig von ascNull, siehe behaviourUtilityFunction.softmax).
     *
     * Neu (Auftraggeber-Klarstellung 2026-08-29): f = (p_avm - p_base) /
     * (1 - p_base) - der Anteil der Kandidatenwege, der in der avm-Welt
     * GEGENUEBER der Basiswelt zusaetzlich gewaehlt wird - wird EINMAL
     * POPULATIONSWEIT aus den erwarteten (nicht gezogenen) Wege-Anteilen ueber
     * ALLE bewerteten Kandidaten dieses Laufs berechnet (siehe notifyStartup),
     * und dann UNABHAENGIG vom individuellen Basisergebnis jedes einzelnen
     * Kandidaten als FLACHE Wahrscheinlichkeit auf JEDEN Kandidaten angewendet
     * (ein einziger Zug je Kandidat gegen f) - NICHT als "Rettung" nur der in
     * der Basiswelt abgelehnten Kandidaten. Die individuelle Basisentscheidung
     * (basisEntscheidung) hat damit KEINEN Einfluss mehr auf das tatsaechliche
     * Ergebnis dieses Kandidaten, sie fliesst nur noch in die Berechnung von f
     * ein und wird rein zu Diagnosezwecken mitgefuehrt. f ist fuer alle
     * Personen gleich (eine einzige Zahl je Lauf), daher hier NICHT als Spalte
     * gefuehrt (siehe Log-Zusammenfassung).
     *
     * p0Base/p0Avm: Nullalternative-Wahrscheinlichkeit dieses Kandidaten in
     * der jeweiligen Welt (null, wenn nicht bekannt/nicht anwendbar - z. B.
     * strukturell uebersprungen). basisEntscheidung: "WEG" oder
     * "NULLALTERNATIVE" - Ergebnis der REINEN Basiswelt-Ziehung (nur
     * Diagnose, siehe oben). entscheidung/modus: das TATSAECHLICHE Endergebnis
     * (nach dem flachen f-Zug, im avm-Choice-Set gewaehlter Modus).
     */
    private record kandidatenwegRow(String personId, String segment, Double distanzMeter, String zweck,
            Double p0Base, Double p0Avm, String basisEntscheidung, String entscheidung, String modus) {
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
        double agentAnteil = cfg.getKandidatenwegAgentAnteil();
        double scaleParameter = cfg.getScaleParameter();
        Set<alternatives> worldChoiceSet = buildWorldChoiceSet(cfg.getKandidatenwegWelt());
        // ascNull ist AUSSCHLIESSLICH in der Basiswelt kalibriert (siehe calibrateAscNull-
        // Javadoc) - fuer die Basisentscheidung (Phase 2) gilt deshalb IMMER CA/PT, unabhaengig
        // vom konfigurierten kandidatenwegWelt (siehe kandidatenwegRow-Javadoc "ZWEISTUFIGE
        // ENTSCHEIDUNG").
        Set<alternatives> baseChoiceSet = EnumSet.of(alternatives.CA, alternatives.PT);
        List<kandidatenwegRow> csvRows = new ArrayList<>();
        List<logsumRow> logsumRows = new ArrayList<>();
        List<pendingCandidate> pending = new ArrayList<>();

        Map<alternatives, modeParams> modeParamsByAlternative = cfg.buildModeParams();
        Map<String, agentProfile> segmentsById = cfg.buildSegments();

        List<templateProvider> allProviders = collectTemplateProviders(homeType);
        homeLocationGrid grid = new homeLocationGrid(allProviders);

        log.info("Nullalternative: " + allProviders.size() + " Agenten mit mindestens einem qualifizierenden "
                + "Kandidatenweg (direkter Hin-/Rueckweg von/nach Hause, ohne Arbeit/Bildung) als Vorlagen-Pool "
                + "fuer die Nachbarschaftssuche gesammelt.");

        int total = 0, skippedNotSampled = 0, skippedNoTemplate = 0, skippedNoFreeSlot = 0, skippedNoModeAvailable = 0;

        // PHASE 1: Sampling/Vorlagenwahl/Routing wie bisher - aber statt sofort zu entscheiden,
        // werden je Kandidat nur die Nutzenwerte (Basis- UND avm-Welt) berechnet und in "pending"
        // gesammelt. Die eigentliche Entscheidung (Phase 2) braucht zuerst die POPULATIONSWEITE
        // Konversionsrate f, die sich erst aus ALLEN Kandidaten zusammen ergibt.
        for (Person person : population.getPersons().values()) {
            total++;

            String personId = person.getId().toString();
            Object segmentValue = person.getAttributes().getAttribute(segmentAttribute);
            String segment = segmentValue == null ? "unbekannt" : segmentValue.toString();

            if (!isCandidateEligible(person, randomSeed, agentAnteil)) {
                skippedNotSampled++;
                person.getAttributes().putAttribute(OUTCOME_ATTRIBUTE, "skipped:notSampled");
                csvRows.add(new kandidatenwegRow(personId, segment, null, null, null, null, null, "NULLALTERNATIVE", null));
                continue;
            }

            List<PlanElement> elements = person.getSelectedPlan().getPlanElements();

            Coord homeCoord = allProviders.isEmpty() ? null : resolveHomeCoord(person, homeType);
            if (homeCoord == null) {
                skippedNoTemplate++;
                person.getAttributes().putAttribute(OUTCOME_ATTRIBUTE, "skipped:noTemplate");
                csvRows.add(new kandidatenwegRow(personId, segment, null, null, null, null, null, "NULLALTERNATIVE", null));
                continue;
            }

            Optional<templateSelection> selection = selectNearestFittingTemplate(person.getId(), homeCoord, elements, grid);
            if (selection.isEmpty()) {
                // Kein Nachbar der gesamten Population liefert einen Vorlagen-Weg, der in die
                // bestehende Wegekette dieser Person passt (Tag komplett durch Arbeit/Bildung
                // verplant, oder alle Vorlagen-Dauern sprengen das Tagesende) - siehe
                // selectNearestFittingTemplate-Javadoc.
                skippedNoFreeSlot++;
                person.getAttributes().putAttribute(OUTCOME_ATTRIBUTE, "skipped:noFreeSlot");
                csvRows.add(new kandidatenwegRow(personId, segment, null, null, null, null, null, "NULLALTERNATIVE", null));
                continue;
            }

            candidateTripTemplate template = selection.get().template();
            insertionPoint boundary = selection.get().boundary();
            String zweck = behaviourModule.parseActivityType(template.sourceActivity().getType()).purpose();
            double duration = template.durationSeconds();
            Activity boundaryActivity = boundary.activity();
            double candidateStart = boundary.candidateStart();

            // Luftliniendistanz Einfuegepunkt->Kandidatenziel (Schritt 8, Spalte "distanz") -
            // braucht nur boundaryActivity (bereits bekannt) und template.sourceActivity()
            // (dieselbe Koordinate wie die spaeter tatsaechlich verwendete destinationActivity,
            // siehe copyLocationAs unten - nur der Aktivitaetstyp aendert sich, nicht der Ort).
            double distanzMeter = CoordUtils.calcEuclideanDistance(
                    FacilitiesUtils.toFacility(boundaryActivity, facilities).getCoord(),
                    FacilitiesUtils.toFacility(template.sourceActivity(), facilities).getCoord());

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
                        new Random(tripContextBuilder.personSeed(randomSeed, person.getId(), alternative.name())),
                        cfg.resolveIncomeTier(person));

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
                // nur die Nullalternative selbst (kein Modus verfuegbar/routbar, weder Basis-
                // noch avm-Welt) - keine echte Wahl moeglich, unabhaengig von f (siehe
                // kandidatenwegRow-Javadoc).
                skippedNoModeAvailable++;
                person.getAttributes().putAttribute(OUTCOME_ATTRIBUTE, "skipped:noModeAvailable");
                csvRows.add(new kandidatenwegRow(personId, segment, distanzMeter, zweck, 1.0, 1.0, "NULLALTERNATIVE",
                        "NULLALTERNATIVE", null));
                continue;
            }

            double p0Base = extractP0(utilities, baseChoiceSet, scaleParameter);
            double p0Avm = extractP0(utilities, worldChoiceSet, scaleParameter);

            pending.add(new pendingCandidate(person, personId, segment, distanzMeter, zweck, boundary,
                    destinationActivity, duration, utilities, p0Base, p0Avm));
        }

        // PHASE 2a: globale Konversionsrate f (siehe kandidatenwegRow-Javadoc "GLOBALE
        // KONVERSIONSRATE f"). Erwartungswert (KEINE zusaetzliche Ziehung) ueber ALLE Kandidaten
        // dieser Phase, damit f nicht selbst schon Stichprobenrauschen in den flachen
        // Einfuege-Zug der Phase 2b traegt.
        double sumBaseTaken = 0.0, sumAvmTaken = 0.0;
        for (pendingCandidate candidate : pending) {
            sumBaseTaken += 1.0 - candidate.p0Base();
            sumAvmTaken += 1.0 - candidate.p0Avm();
        }
        double pBase = pending.isEmpty() ? 0.0 : sumBaseTaken / pending.size();
        double pAvm = pending.isEmpty() ? 0.0 : sumAvmTaken / pending.size();
        double f = pBase >= 1.0 ? 0.0 : (pAvm - pBase) / (1.0 - pBase);
        f = Math.max(0.0, Math.min(1.0, f));

        log.info(String.format(Locale.ROOT,
                "Nullalternative: Konversionsrate f=%.4f (p_base=%.4f, p_avm=%.4f ueber %d Kandidaten) - Anteil der "
                        + "Kandidatenwege, der in der avm-Welt gegenueber der Basiswelt zusaetzlich gewaehlt wird; "
                        + "wird als flache Wahrscheinlichkeit auf JEDEN Kandidaten angewendet, unabhaengig von "
                        + "dessen individueller Basisentscheidung.",
                f, pBase, pAvm, pending.size()));

        // PHASE 2b: EIN flacher Zug je Kandidat gegen die globale Rate f (Auftraggeber-Klarstellung
        // 2026-08-29: f ist NICHT nur eine "Rettung" der Basiswelt-Ablehnungen, sondern gilt
        // UNABHAENGIG vom individuellen Basisergebnis fuer JEDEN Kandidaten gleichermassen - die
        // Basisentscheidung fliesst nur noch in die Berechnung von f (Phase 2a) ein, nicht mehr in
        // die einzelne Einfuege-Entscheidung). basisEntscheidung wird trotzdem weiter je Kandidat
        // ermittelt und in der CSV mitgefuehrt - rein zu Diagnosezwecken (Vergleich Basis- vs.
        // tatsaechliches Ergebnis), OHNE Einfluss auf "insert".
        int inserted = 0, skippedNullChosen = 0;
        for (pendingCandidate candidate : pending) {
            Map<Optional<alternatives>, Double> baseUtilities = filterChoiceSet(candidate.utilities(), baseChoiceSet);
            Map<Optional<alternatives>, Double> baseProbabilities = behaviourUtilityFunction.softmax(baseUtilities, scaleParameter);
            double baseDraw = new Random(tripContextBuilder.personSeed(randomSeed, candidate.person().getId(),
                    "nullAlternativeBaseDraw")).nextDouble();
            boolean baseChosen = behaviourUtilityFunction.drawFromCumulative(baseProbabilities, baseDraw).isPresent();
            String basisEntscheidung = baseChosen ? "WEG" : "NULLALTERNATIVE";

            double insertDraw = new Random(tripContextBuilder.personSeed(randomSeed, candidate.person().getId(),
                    "nullAlternativeInsertDraw")).nextDouble();
            boolean insert = insertDraw < f;

            if (!insert) {
                skippedNullChosen++;
                candidate.person().getAttributes().putAttribute(OUTCOME_ATTRIBUTE, "optedOut");
                csvRows.add(new kandidatenwegRow(candidate.personId(), candidate.segment(), candidate.distanzMeter(),
                        candidate.zweck(), candidate.p0Base(), candidate.p0Avm(), basisEntscheidung,
                        "NULLALTERNATIVE", null));
                continue;
            }

            // Konkreter Modus: sobald feststeht, dass ein Weg stattfindet, zaehlt fuer die
            // MODUSWAHL die volle avm-Alternativenmenge - die Person lebt in der avm-Welt und
            // waehlt unter deren tatsaechlich verfuegbaren Alternativen.
            Map<alternatives, Double> realAlternatives = new LinkedHashMap<>();
            for (Map.Entry<Optional<alternatives>, Double> entry : candidate.utilities().entrySet()) {
                entry.getKey().ifPresent(alt -> realAlternatives.put(alt, entry.getValue()));
            }
            Map<alternatives, Double> modeProbabilities = behaviourUtilityFunction.softmax(realAlternatives, scaleParameter);
            double modeDraw = new Random(tripContextBuilder.personSeed(randomSeed, candidate.person().getId(),
                    "nullAlternativeAvmModeDraw")).nextDouble();
            alternatives chosenMode = behaviourUtilityFunction.drawFromCumulative(modeProbabilities, modeDraw);

            insertCandidateTrip(candidate.person().getSelectedPlan(), candidate.boundary().index(),
                    candidate.boundary().activity(), candidate.boundary().appendAtDayEnd(), chosenMode.getMatsimMode(),
                    candidate.destinationActivity(), candidate.boundary().candidateStart(), candidate.duration());
            candidate.person().getAttributes().putAttribute(OUTCOME_ATTRIBUTE, "inserted:" + chosenMode.getMatsimMode());
            csvRows.add(new kandidatenwegRow(candidate.personId(), candidate.segment(), candidate.distanzMeter(),
                    candidate.zweck(), candidate.p0Base(), candidate.p0Avm(), basisEntscheidung, "WEG",
                    chosenMode.getMatsimMode()));
            inserted++;
        }

        log.info(String.format(Locale.ROOT,
                "Nullalternative: %d/%d Agenten mit Kandidatenweg eingefuegt (flacher Zug gegen f=%.4f, "
                        + "unabhaengig von der individuellen Basisentscheidung; nicht in der Stichprobe "
                        + "(kandidatenwegAgentAnteil=%.4f): %d, Nullalternative gewaehlt: %d, kein freier Zeitpunkt "
                        + "(Arbeit/Bildung): %d, keine Vorlage: %d, kein Modus verfuegbar/routbar: %d).",
                inserted, total, f, agentAnteil, skippedNotSampled, skippedNullChosen, skippedNoFreeSlot,
                skippedNoTemplate, skippedNoModeAvailable));

        writeKandidatenwegeCsv(csvRows);
        writeLogsumCsv(logsumRows);
    }

    /** Zwischenergebnis von Phase 1 (siehe notifyStartup) fuer einen Kandidaten der Stichprobe. */
    private record pendingCandidate(Person person, String personId, String segment, double distanzMeter,
            String zweck, insertionPoint boundary, Activity destinationActivity, double duration,
            Map<Optional<alternatives>, Double> utilities, double p0Base, double p0Avm) {
    }

    /** utilities auf choiceSet (plus Nullalternative) einschraenken - Hilfsmethode fuer notifyStartup. */
    private static Map<Optional<alternatives>, Double> filterChoiceSet(
            Map<Optional<alternatives>, Double> utilities, Set<alternatives> choiceSet) {
        Map<Optional<alternatives>, Double> filtered = new LinkedHashMap<>();
        for (Map.Entry<Optional<alternatives>, Double> entry : utilities.entrySet()) {
            if (entry.getKey().isEmpty() || choiceSet.contains(entry.getKey().get())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }

    /**
     * P(Nullalternative) innerhalb von choiceSet - 1.0, wenn choiceSet fuer diesen Kandidaten
     * keine einzige routbare/verfuegbare Alternative enthaelt (dann bliebe sonst nur die
     * Nullalternative selbst im gefilterten Choice-Set, softmax darauf ist trivial 1.0, aber ohne
     * die triviale Rechnung extra anzustossen).
     */
    private static double extractP0(Map<Optional<alternatives>, Double> utilities, Set<alternatives> choiceSet,
            double scaleParameter) {
        Map<Optional<alternatives>, Double> filtered = filterChoiceSet(utilities, choiceSet);
        if (filtered.size() == 1) {
            return 1.0;
        }
        return behaviourUtilityFunction.softmax(filtered, scaleParameter).get(Optional.<alternatives>empty());
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
            sb.append("personId;segment;distanz;zweck;p0Base;p0Avm;basisEntscheidung;entscheidung;modus\n");
            for (kandidatenwegRow row : rows) {
                sb.append(row.personId()).append(';')
                        .append(row.segment()).append(';')
                        .append(row.distanzMeter() == null ? "" : String.format(Locale.ROOT, "%.3f", row.distanzMeter())).append(';')
                        .append(row.zweck() == null ? "" : row.zweck()).append(';')
                        .append(row.p0Base() == null ? "" : String.format(Locale.ROOT, "%.6f", row.p0Base())).append(';')
                        .append(row.p0Avm() == null ? "" : String.format(Locale.ROOT, "%.6f", row.p0Avm())).append(';')
                        .append(row.basisEntscheidung() == null ? "" : row.basisEntscheidung()).append(';')
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
     * NEUER ANSATZ (Auftraggeber-Vorgabe, ersetzt die Version die NUR
     * Kandidatenwege zaehlte): die Nullalternative-Entscheidung wird jetzt
     * NICHT mehr nur auf den neuen Kandidatenweg angewendet, sondern
     * ZUSAETZLICH auf JEDEN bereits bestehenden (erhobenen) Weg JEDER Person
     * der Population - fuer die Kalibrierung, NICHT fuer den echten Lauf
     * (siehe notifyStartup: dort bleiben bestehende Plaene unveraendert,
     * nur der Kandidatenweg wird ueberhaupt eingefuegt/verworfen).
     * Grundgesamtheit der Bisektion ist also: ALLE bestehenden Wege ALLER
     * Personen PLUS die Kandidatenwege NUR der ueber
     * kandidatenwegAgentAnteil gezogenen Teilmenge (z. B. 20% der Agenten) -
     * NICHT mehr die reine Personenzahl. targetShare (WEG-Anteil, wie
     * bisher definiert) muss dafuer entsprechend gesetzt werden, z. B. fuer
     * "17% aller Wege (bestehende + Kandidaten) werden nicht genommen" ->
     * targetShare = 1 - 0.17 = 0.83.
     * Herleitung/Rechtfertigung: bei kandidatenwegAgentAnteil=1.0 (Default)
     * entspricht das exakt der vorherigen Definition (ein bestehender Weg
     * wird hier NICHT extra gezaehlt, da bestehende Wege nur bei
     * agentAnteil&lt;1.0 ueberhaupt eine neue Bedeutung fuer die Zielgroesse
     * gewinnen - Bugfix-Hinweis: bei agentAnteil=1.0 werden bestehende Wege
     * TROTZDEM mitgezaehlt, das Ziel muss dann entsprechend angepasst
     * werden, siehe Konfigurationskommentar in scenarios/testszenario/
     * config.xml).
     *
     * WICHTIGE ABWEICHUNG von der Spezifikation ("ein Durchlauf ... dauert
     * Sekunden, 40 Durchlaeufe sind unkritisch"): das gilt fuer die in der
     * Spezifikation vorgesehene Analytik-LOS ohne Routing (Schritt 4). Dieses
     * Add-on routet dagegen echt (siehe Klassen-Javadoc) - ein 10pct-Lauf
     * braucht dafuer mehrere Minuten (siehe Git-Historie), 40x davon waere
     * unpraktikabel. Deshalb hier explizit in zwei Phasen getrennt: Phase 1
     * (teuer, EINMAL) berechnet je Weg (bestehend ODER Kandidat) die
     * gerouteten CA/PT-Nutzenwerte UND die deterministische Ziehung
     * nullAlternativeDraw - beide sind unabhaengig von ascNull, das nur die
     * Nullalternative selbst betrifft. Phase 2 (billig, 40x) wiederholt nur
     * noch Softmax+Ziehung ueber die in Phase 1 bereits berechneten Werte -
     * reine Arithmetik, kein Routing mehr. Damit bleibt die Bisektion trotz
     * echtem Routing praktikabel (eine Routing-Phase statt vierzig) - auch
     * wenn Phase 1 durch die bestehenden Wege ALLER Personen jetzt deutlich
     * mehr Routing-Aufwand hat als vorher (vorher: ein Kandidat je Person;
     * jetzt: alle bestehenden Wege je Person plus ein Kandidat je Person der
     * Stichprobe).
     *
     * Strukturell ausgeschlossene Wege (kein Modus verfuegbar/routbar, oder -
     * fuer Kandidaten - keine Vorlage/kein freier Zeitpunkt) zaehlen in den
     * Nenner des Ziel-Anteils, tragen aber nie zum WEG-Zaehler bei -
     * unabhaengig von ascNull, siehe kandidatenwegRow-Javadoc fuer dieselbe
     * Logik in der normalen CSV.
     */
    private void calibrateAscNull() {
        String homeType = cfg.getHomeActivityType();
        long randomSeed = cfg.getRandomSeed();
        String segmentAttribute = cfg.getSegmentAttribute();
        double targetShare = cfg.getAscNullKalibrierungZielanteil();
        double agentAnteil = cfg.getKandidatenwegAgentAnteil();
        Set<alternatives> baseChoiceSet = EnumSet.of(alternatives.CA, alternatives.PT);

        Map<alternatives, modeParams> modeParamsByAlternative = cfg.buildModeParams();
        Map<String, agentProfile> segmentsById = cfg.buildSegments();
        List<templateProvider> allProviders = collectTemplateProviders(homeType);
        homeLocationGrid grid = new homeLocationGrid(allProviders);

        int totalPopulation = population.getPersons().size();
        log.info(String.format(Locale.ROOT,
                "ascNull-Kalibrierung: Phase 1 (Routing CA/PT, Basiswelt, einmalig) fuer alle bestehenden "
                        + "Wege von %d Personen PLUS Kandidatenwege einer %.4f-Stichprobe, Ziel-WEG-Anteil %.4f...",
                totalPopulation, agentAnteil, targetShare));

        List<calibrationPersonContext> contexts = new ArrayList<>();
        for (Person person : population.getPersons().values()) {
            agentProfile profile = resolveProfile(person, segmentAttribute, segmentsById)
                    .draw(new Random(tripContextBuilder.personSeed(randomSeed, person.getId(), "profile")));
            Collection<String> availableModes = modeAvailability.getAvailableModes(person, List.of());

            // NEU: jeder bestehende (bereits erhobene) Weg dieser Person - siehe Methoden-Javadoc
            // "Neuer Ansatz". TripStructureUtils.getTrips liefert echte Wegeendpunkte (ueberspringt
            // ggf. vorhandene Stage-/Interaktionsaktivitaeten automatisch), unabhaengig vom
            // tatsaechlich gewaehlten Modus wird hier ausschliesslich CA/PT bewertet (Basiswelt).
            List<TripStructureUtils.Trip> existingTrips = TripStructureUtils.getTrips(person.getSelectedPlan());
            for (int tripIndex = 0; tripIndex < existingTrips.size(); tripIndex++) {
                TripStructureUtils.Trip trip = existingTrips.get(tripIndex);
                Activity originActivity = trip.getOriginActivity();
                Activity destinationActivity = trip.getDestinationActivity();
                if (originActivity.getEndTime().isUndefined()) {
                    continue; // kein verlaesslicher Abfahrtszeitpunkt - siehe collectTemplateProviders-Javadoc
                }
                double departureTime = originActivity.getEndTime().seconds();
                Facility originFacility = FacilitiesUtils.toFacility(originActivity, facilities);
                Facility destinationFacility = FacilitiesUtils.toFacility(destinationActivity, facilities);

                Map<alternatives, Double> existingTripUtilities = new LinkedHashMap<>();
                for (alternatives alternative : modeParamsByAlternative.keySet()) {
                    if (!baseChoiceSet.contains(alternative) || !availableModes.contains(alternative.getMatsimMode())) {
                        continue;
                    }
                    if (alternative == alternatives.CA) {
                        ensureVehicleId(person, alternative.getMatsimMode());
                    }
                    modeParams meanParams = modeParamsByAlternative.get(alternative);
                    modeParams params = meanParams.draw(
                            new Random(tripContextBuilder.personSeed(randomSeed, person.getId(),
                                    "baseline" + tripIndex + alternative.name())),
                            cfg.resolveIncomeTier(person));
                    List<? extends PlanElement> routed;
                    try {
                        routed = tripRouter.calcRoute(alternative.getMatsimMode(), originFacility, destinationFacility,
                                departureTime, person, new AttributesImpl());
                    } catch (RuntimeException e) {
                        continue;
                    }
                    double costPerKm = params.effectiveCostPerKm(cfg.hasTicket(person));
                    TripContext tripContext = tripContextBuilder.buildTripContext(
                            timeInterpretation, departureTime, routed, costPerKm);
                    existingTripUtilities.put(alternative, utilityFunction.utility(profile, params, tripContext, null));
                }

                double existingTripDraw = new Random(tripContextBuilder.personSeed(randomSeed, person.getId(),
                        "baselineNullDraw" + tripIndex)).nextDouble();
                contexts.add(new calibrationPersonContext(existingTripUtilities, existingTripDraw));
            }

            // Kandidatenweg wie bisher, aber nur fuer die ueber kandidatenwegAgentAnteil gezogene
            // Stichprobe (siehe isCandidateEligible-Javadoc) - bei agentAnteil=1.0 (Default) wie
            // gehabt fuer alle Personen.
            if (!isCandidateEligible(person, randomSeed, agentAnteil)) {
                continue;
            }
            if (allProviders.isEmpty()) {
                continue; // strukturell ausgeschlossen - siehe Methoden-Javadoc
            }
            Coord homeCoord = resolveHomeCoord(person, homeType);
            if (homeCoord == null) {
                continue;
            }
            List<PlanElement> elements = person.getSelectedPlan().getPlanElements();
            Optional<templateSelection> selection = selectNearestFittingTemplate(person.getId(), homeCoord, elements, grid);
            if (selection.isEmpty()) {
                continue; // strukturell ausgeschlossen - siehe Methoden-Javadoc/selectNearestFittingTemplate-Javadoc
            }
            candidateTripTemplate template = selection.get().template();
            insertionPoint boundary = selection.get().boundary();
            Activity boundaryActivity = boundary.activity();
            double candidateStart = boundary.candidateStart();

            Facility originFacility = FacilitiesUtils.toFacility(boundaryActivity, facilities);
            Activity destinationActivity = copyLocationAs(template.sourceActivity(), template.sourceActivity().getType());
            Facility destinationFacility = FacilitiesUtils.toFacility(destinationActivity, facilities);

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
                        new Random(tripContextBuilder.personSeed(randomSeed, person.getId(), alternative.name())),
                        cfg.resolveIncomeTier(person));
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

        int totalWege = contexts.size();
        log.info("ascNull-Kalibrierung: Phase 1 fertig (" + totalWege + " Wege insgesamt - bestehende Wege "
                + "aller Personen plus Kandidatenwege der Stichprobe), Phase 2 (Bisektion, 40 Iterationen, "
                + "kein weiteres Routing)...");

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
            double share = (double) wegCountAtMid / totalWege;
            // P(0) steigt monoton mit ascNull -> WEG-Anteil faellt monoton mit ascNull (siehe
            // Methoden-Javadoc): Anteil zu hoch -> ascNull erhoehen -> obere Haelfte weitersuchen.
            if (share > targetShare) {
                lo = mid;
            } else {
                hi = mid;
            }
        }

        double finalShare = (double) wegCountAtMid / totalWege;
        log.info(String.format(Locale.ROOT,
                "ascNull-Kalibrierung fertig: ascNull=%.6f (WEG-Anteil %.4f, Ziel %.4f, Basiswelt CA/PT, "
                        + "%d/%d Wege, 40 Bisektionsschritte).",
                mid, finalShare, targetShare, wegCountAtMid, totalWege));
        writeCalibrationResult(mid, targetShare, finalShare, wegCountAtMid, totalWege);

        // Kein Aufruf hier hat einen Sinn fuer einen reinen Kalibrierungslauf - die eigentlichen
        // MATSim-Iterationen (config.controller().lastIteration) wuerden nur die UNVERAENDERTEN
        // Basisplaene (kein Kandidatenweg eingefuegt, siehe notifyStartup-Guard) minutenlang
        // durchrechnen, ohne dass das Ergebnis irgendwo verwendet wird.
        log.info("ascNull-Kalibrierung: beende den Prozess (keine MATSim-Iterationen fuer einen "
                + "reinen Kalibrierungslauf noetig).");
        System.exit(0);
    }

    private void writeCalibrationResult(double ascNull, double targetShare, double achievedShare,
            long wegCount, int totalWege) {
        try {
            Path directory = Path.of(config.controller().getOutputDirectory());
            Files.createDirectories(directory);
            Path csvPath = directory.resolve("ascnull_kalibrierung.csv");
            String csv = "ascNull;zielAnteil;erreichterAnteil;wegCount;totalWege\n"
                    + String.format(Locale.ROOT, "%.6f;%.6f;%.6f;%d;%d\n",
                            ascNull, targetShare, achievedShare, wegCount, totalWege);
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
     * collectTemplateProviders) noch als laufende Aktivitaet fuer einen Zusatzweg
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

    /**
     * Deterministische Ziehung, ob diese Person ueberhaupt einen Kandidatenweg
     * angeboten bekommt (verhaltensmodell.kandidatenwegAgentAnteil, siehe
     * behaviourConfigGroup-Feld-Javadoc) - Default 1.0 (alle Agenten,
     * bisheriges Verhalten). Bei Werten &lt; 1.0 wird NUR fuer die gezogenen
     * Agenten ueberhaupt eine Nachbarschaftssuche versucht; alle anderen
     * bleiben unveraendert wie erhoben, exakt wie bei einer strukturell
     * uebersprungenen Person, nur mit eigener Outcome-Kategorie
     * ("skipped:notSampled") zur Unterscheidung.
     */
    private static boolean isCandidateEligible(Person person, long randomSeed, double agentAnteil) {
        if (agentAnteil >= 1.0) {
            return true;
        }
        double draw = new Random(tripContextBuilder.personSeed(randomSeed, person.getId(), "candidateEligibility")).nextDouble();
        return draw < agentAnteil;
    }

    /**
     * Heimatkoordinate einer Person - Grundlage der Nachbarschaftssuche (siehe
     * homeLocationGrid/selectNearestFittingTemplate-Javadoc). Nimmt die erste
     * im Plan gefundene Heimataktivitaet (bei mehreren Heimataktivitaeten im
     * Tagesplan - unueblich, aber strukturell moeglich - ist das ausreichend,
     * da sie in aller Regel denselben Ort referenzieren). Liefert null, wenn
     * die Person KEINE Heimataktivitaet hat (strukturell durch
     * registerHomeActivityTypes/registerHomeActivityTypes abgedeckt, hier nur
     * defensiv).
     */
    private Coord resolveHomeCoord(Person person, String homeType) {
        for (PlanElement element : person.getSelectedPlan().getPlanElements()) {
            if (element instanceof Activity activity && isHomeActivity(activity.getType(), homeType)) {
                return FacilitiesUtils.toFacility(activity, facilities).getCoord();
            }
        }
        return null;
    }

    /**
     * Sammelt aus der GESAMTEN Population alle qualifizierenden Kandidatenweg-
     * Vorlagen, gruppiert nach dem beitragenden Agenten (templateProvider) -
     * Grundlage der Nachbarschaftssuche (siehe Klassen-Javadoc
     * "Nachbarschaftsauswahl"). ANDERS als die fruehere segmentbasierte
     * Vorlagen-Sammlung wird HIER NICHT mehr nach Segment gruppiert - die
     * geografische Naehe der Heimatkoordinate ersetzt die Segmentzugehoerigkeit
     * als Aehnlichkeitskriterium (Auftraggeber-Vorgabe "naechstmoeglichster
     * Nachbar").
     *
     * Qualifikationskriterium (Auftraggeber-Vorgabe "Weg von zuhause aus, der
     * auch wieder nach Hause zurueckfuehrt", strikte Auslegung): die
     * Aktivitaet selbst darf weder zuhause noch Arbeit/Bildung sein
     * (isBlockingPurpose/isHomeActivity, wie zuvor), UND die UNMITTELBAR
     * VORHERGEHENDE Aktivitaet im Plan DES BEITRAGENDEN AGENTEN muss zuhause
     * sein (Hinweg von zuhause aus) UND die UNMITTELBAR FOLGENDE Aktivitaet
     * (falls vorhanden) muss EBENFALLS zuhause sein (Rueckweg nach Hause) -
     * ein direkter, unverzweigter Hin-/Rueckweg wie er auch beim Zielagenten
     * eingefuegt wird (siehe insertCandidateTrip), keine laengere Wegekette
     * mit weiteren Zwischenstopps. Die letzte, offene Aktivitaet des Tages
     * (kein Nachfolger vorhanden) erfuellt "fuehrt wieder nach Hause" NICHT
     * und scheidet damit als Vorlage aus.
     */
    private List<templateProvider> collectTemplateProviders(String homeType) {
        List<templateProvider> result = new ArrayList<>();

        for (Person person : population.getPersons().values()) {
            List<PlanElement> elements = person.getSelectedPlan().getPlanElements();
            List<candidateTripTemplate> templates = new ArrayList<>();
            Coord homeCoord = null;

            for (int i = 1; i < elements.size(); i++) {
                if (!(elements.get(i) instanceof Activity activity) || isHomeActivity(activity.getType(), homeType)
                        || isBlockingPurpose(activity.getType())
                        || TripStructureUtils.isStageActivityType(activity.getType())) {
                    continue;
                }
                // Bugfix (wie zuvor bei collectTemplates): bei frisch geladenen (noch
                // ungerouteten) Plaenen ist Leg.getDepartureTime() IMMER UNDEFINED - die
                // Abfahrtszeit steckt zu diesem Zeitpunkt nur in der end_time der
                // VORAUSGEHENDEN Aktivitaet.
                if (!(elements.get(i - 1) instanceof Leg) || i < 2
                        || !(elements.get(i - 2) instanceof Activity originActivity)
                        || originActivity.getEndTime().isUndefined()
                        || !isHomeActivity(originActivity.getType(), homeType)) {
                    continue;
                }
                int returnIndex = i + 2;
                if (returnIndex >= elements.size()
                        || !(elements.get(returnIndex) instanceof Activity returnActivity)
                        || !isHomeActivity(returnActivity.getType(), homeType)) {
                    continue;
                }

                if (homeCoord == null) {
                    homeCoord = FacilitiesUtils.toFacility(originActivity, facilities).getCoord();
                }
                double typicalDuration = behaviourModule.parseActivityType(activity.getType()).typicalDurationSeconds();
                templates.add(new candidateTripTemplate(activity, originActivity.getEndTime().seconds(), typicalDuration));
            }

            if (!templates.isEmpty()) {
                result.add(new templateProvider(person.getId(), homeCoord, templates));
            }
        }
        return result;
    }

    /**
     * Nachbarschaftssuche fuer den Kandidatenweg (siehe Klassen-Javadoc
     * "Nachbarschaftsauswahl"): sucht - beginnend beim naechsten Nachbarn in
     * der GESAMTEN Population (unabhaengig vom Segment) und mit wachsendem
     * Suchradius - den ersten qualifizierenden Vorlagen-Weg eines ANDEREN
     * Agenten, der TATSAECHLICH in die bestehende Wegekette dieser Zielperson
     * passt (siehe findInsertionBoundary). Passt der Weg des naechsten
     * Nachbarn nicht (Tag bereits durch Arbeit/Bildung komplett verplant,
     * oder die Vorlagen-Dauer sprengt das Tagesende), wird NICHT die ganze
     * Person uebersprungen, sondern beim naechstnaeheren Nachbarn
     * weitergesucht (Auftraggeber-Feedback "wenn Aktivitaetsplaene schon fuer
     * den Tag voll, skippe einfach zu einem naechsten Agenten") - erst wenn
     * KEIN Nachbar der gesamten Population einen passenden Weg liefert, ist
     * das Ergebnis leer (die Person gilt dann als strukturell uebersprungen).
     *
     * Innerhalb eines Rings werden alle dort gefundenen, tatsaechlich
     * passenden Kandidaten gesammelt und nach echter euklidischer Distanz
     * sortiert; der Ring wird erst dann als abschliessend akzeptiert, wenn
     * die kleinste dort gefundene Distanz hoechstens ring*cellSize betraegt -
     * erst ab diesem Radius kann kein naeherer, noch nicht durchsuchter
     * Nachbar mehr existieren (siehe homeLocationGrid-Klassen-Javadoc).
     * Mehrere Vorlagen DESSELBEN Nachbarn werden chronologisch
     * (startTimeSeconds) probiert, bevor zum naechsten Nachbarn gewechselt
     * wird.
     */
    private Optional<templateSelection> selectNearestFittingTemplate(Id<Person> targetPersonId, Coord targetHomeCoord,
            List<PlanElement> targetElements, homeLocationGrid grid) {

        List<templateSelection> found = new ArrayList<>();
        int maxRing = grid.maxRing(targetHomeCoord);

        for (int ring = 0; ring <= maxRing; ring++) {
            for (templateProvider provider : grid.expandingRing(targetHomeCoord, ring)) {
                if (provider.personId().equals(targetPersonId)) {
                    continue;
                }
                List<candidateTripTemplate> templates = new ArrayList<>(provider.templates());
                templates.sort(Comparator.comparingDouble(candidateTripTemplate::startTimeSeconds));
                for (candidateTripTemplate template : templates) {
                    if (template.durationSeconds() >= END_OF_DAY_SECONDS) {
                        continue;
                    }
                    insertionPoint boundary = findInsertionBoundary(targetElements, template.startTimeSeconds());
                    if (boundary == null) {
                        continue;
                    }
                    if (boundary.candidateStart() + template.durationSeconds() >= END_OF_DAY_SECONDS) {
                        continue;
                    }
                    found.add(new templateSelection(provider, template, boundary));
                    break; // erste passende Vorlage dieses Nachbarn reicht - naechster Nachbar statt naechste Vorlage
                }
            }

            if (!found.isEmpty()) {
                templateSelection closest = found.stream()
                        .min(Comparator.comparingDouble(selection ->
                                CoordUtils.calcEuclideanDistance(targetHomeCoord, selection.provider().homeCoord())))
                        .orElseThrow();
                double closestDistance = CoordUtils.calcEuclideanDistance(targetHomeCoord, closest.provider().homeCoord());
                if (closestDistance <= (double) ring * grid.cellSize()) {
                    return Optional.of(closest);
                }
                // sonst: ein noch naeherer Nachbar koennte in einem weiteren, noch nicht
                // durchsuchten Ring liegen - found bleibt erhalten, Suche geht weiter.
            }
        }

        return found.stream()
                .min(Comparator.comparingDouble(selection ->
                        CoordUtils.calcEuclideanDistance(targetHomeCoord, selection.provider().homeCoord())));
    }
}
