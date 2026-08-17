package org.matsim.project.module;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contribs.discrete_mode_choice.modules.DiscreteModeChoiceConfigurator;
import org.matsim.contribs.discrete_mode_choice.modules.DiscreteModeChoiceModule;
import org.matsim.contribs.discrete_mode_choice.modules.EstimatorModule;
import org.matsim.contribs.discrete_mode_choice.modules.SelectorModule;
import org.matsim.contribs.discrete_mode_choice.modules.config.DiscreteModeChoiceConfigGroup;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contrib.drt.routing.DrtRouteFactory;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtConfigs;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpModule;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.project.config.behaviourConfigGroup;
import org.matsim.project.model.alternatives;
import org.matsim.project.model.behaviourUtilityFunction;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

/**
 * DER EINHAENGEPUNKT DES ADD-ONS.
 *
 * Eine spaetere Simulation bindet das gesamte Verhaltensmodell mit genau
 * zwei Zeilen ein - eine Config-seitige, eine Controler-seitige (der Guice-
 * Modulmechanismus kann Config-Mutationen wie Strategie-Umbau nicht selbst
 * vornehmen, weil der Controler zu dem Zeitpunkt schon aus der fertigen
 * Config gebaut wird; das ist eine MATSim-Lifecycle-Grenze, keine Einschraenkung
 * dieses Add-ons):
 *
 *     protected Config prepareConfig(Config config) {
 *         behaviourModule.configureController(config);
 *         return config;
 *     }
 *
 *     protected void prepareControler(Controler controler) {
 *         controler.addOverridingModule(new behaviourModule());
 *     }
 *
 *     protected void prepareScenario(Scenario scenario) {
 *         behaviourModule.addNetworkModesToLinks(scenario.getNetwork());
 *     }
 *
 * Voraussetzung an das aufrufende Szenario (der "Vertrag" des Add-ons):
 *   1. configureController(config) wird in prepareConfig(...) aufgerufen, BEVOR
 *      der Controler gebaut wird.
 *   2. addNetworkModesToLinks(scenario.getNetwork()) wird in prepareScenario(...)
 *      aufgerufen (Netzwerk ist erst dann geladen, nicht schon bei prepareConfig).
 *   3. Jede Person traegt ein Attribut mit ihrer Segment-ID; der Attributname
 *      steht in der Config unter "segmentAttribut" (Default: "segment").
 *   4. Die fuenf Modi CA, AV, PT, PSAV, SSAV richten sich selbst ein (Routing +
 *      Scoring-Defaults fuer AV/PSAV/SSAV, siehe unten) - das Szenario muss dafuer nichts
 *      vorbereiten, ausser CA/PT wie ueblich (Netzwerk/Fahrplan).
 *   5. Falls das Szenario einen anderen Aktivitaetstyp als "home" fuer die
 *      Heimataktivitaet nutzt (z. B. equil: "h"), muss das vor
 *      configureController(...) gesetzt werden:
 *      behaviourConfigGroup.getOrCreate(config).setHomeActivityType("h");
 *
 * Was das Add-on liefert:
 *   - die Verdrahtung des discrete_mode_choice-Contribs im "Mode Choice in
 *     the Loop"-Schema (Hoerl) mit MultinomialLogit-Selektor
 *   - behaviourUtilityEstimator als TripEstimator("verhalten"), der Nutzenwerte
 *     V(i,j) je Agent und Alternative aus behaviourUtilityFunction liefert
 *   - Cumulative als TourEstimator, d. h. Trip-Nutzen werden je Tour aufsummiert
 *   - daraus die Wahlwahrscheinlichkeiten (MultinomialLogit)
 *   - behaviourModeAvailability als ModeAvailability("verhalten"): CA braucht
 *     Fuehrerschein UND Fahrzeugzugang, AV nur Fahrzeugzugang, PT/PSAV/SSAV sind
 *     immer verfuegbar (siehe dortigen Javadoc)
 *   - AV/PSAV/SSAV als NETZWERKBASIERTE Modi (Schritt 7): dieselben Strassenlinks wie
 *     CA, dieselbe Stau-Dynamik im QSim, eigene beobachtete (kongestionsabhaengige)
 *     Reisezeit fuers Routing - kein Teleport mehr, echte Kapazitaetskonkurrenz mit CA.
 *
 * BEKANNTE PLATZHALTER (siehe behaviourUtilityEstimator-Javadoc): Wartezeit und
 * Kosten sind mangels Tarif-/Fahrplanmodell aktuell immer 0.0; die Segmentzuordnung
 * je Agent kommt noch nicht aus einer echten Datenquelle (Schritt 5).
 */
public final class behaviourModule extends AbstractModule {

    /**
     * Config-seitiger Teil der Einbindung. Muss aus prepareConfig(...) (oder
     * aequivalent) aufgerufen werden, bevor der Controler gebaut wird.
     *
     * Aktiviert den discrete_mode_choice-Contrib im "Mode Choice in the Loop"-
     * Schema: Strategien DiscreteModeChoice (20 % der Agenten/Iteration) +
     * KeepLastSelected (80 %), maxAgentPlanMemorySize=1, NonSelectedPlanSelector
     * als Removal-Selector, MultinomialLogit als Selektor.
     */
    public static void configureController(Config config) {

        behaviourConfigGroup cfg = behaviourConfigGroup.getOrCreate(config);

        DiscreteModeChoiceConfigGroup dmcConfig = DiscreteModeChoiceConfigGroup.getOrCreate(config);
        dmcConfig.getActivityHomeFinderConfigGroup().setActivityTypes(List.of(cfg.getHomeActivityType()));
        dmcConfig.getActivityTourFinderConfigGroup().setActivityTypes(List.of(cfg.getHomeActivityType()));

        DiscreteModeChoiceConfigurator.configureAsModeChoiceInTheLoop(config);
        dmcConfig.setSelector(SelectorModule.MULTINOMIAL_LOGIT);

        // Schritt 4: eigenen TripEstimator statt des DMC-Defaults "Uniform" nutzen,
        // Trip-Nutzen je Tour aufsummieren (Cumulative) statt pro Tour einzeln zu werten.
        dmcConfig.setTripEstimator(behaviourDiscreteModeChoiceExtension.TRIP_ESTIMATOR_NAME);
        dmcConfig.setTourEstimator(EstimatorModule.CUMULATIVE);

        // Caching aktivieren: unser Estimator ruft fuer CA/AV/PSAV/SSAV echtes Netzwerk-
        // Routing auf (AbstractTripRouterEstimator routet jeden Kandidaten selbst),
        // das ist auf einem echten Netz (nicht equil) spuerbar teuer. Da unsere
        // Mixed-Logit-Ziehung ohnehin deterministisch aus Person+Modus ist (Schritt 6),
        // liefert Caching garantiert dieselben Ergebnisse wie ohne - reine
        // Performance-Optimierung, keine Verhaltensaenderung.
        dmcConfig.setCachedModes(Arrays.stream(alternatives.values())
                .map(alternatives::getMatsimMode)
                .collect(Collectors.toSet()));

        // Schritt 7: eigene ModeAvailability statt des DMC-Bausteins "Car" - filtert
        // CA (Fuehrerschein + Fahrzeugzugang) und AV (nur Fahrzeugzugang) je Person,
        // siehe behaviourModeAvailability-Javadoc. Die fuenf Alternativen selbst kommen
        // aus alternatives.java, nicht aus einer XML-Modusliste.
        dmcConfig.setModeAvailability(behaviourDiscreteModeChoiceExtension.MODE_AVAILABILITY_NAME);

        // Schritt 7 (Netzwerkrouting): AV nutzt dasselbe Strassennetz wie CA und
        // erlebt dieselbe Stau-Dynamik, statt teleportiert zu werden. Dazu reicht es,
        // es zu routing().networkModes und qsim().mainModes hinzuzufuegen - MATSims
        // eigene TravelTimeCalculatorModule/TravelDisutilityModule iterieren beide
        // automatisch ueber routing().getNetworkModes() und binden pro Modus TravelTime/
        // TravelDisutility (FreeSpeedTravelTime, falls der Modus nicht zusaetzlich in
        // travelTimeCalculator().analyzedModes steht - siehe unten). Kein eigenes
        // Guice-Binding noetig. Die Netzlinks selbst muessen AV zusaetzlich zu CA
        // erlauben - das passiert nicht hier (kein Network in der Config-Phase
        // verfuegbar), sondern in addNetworkModesToLinks(scenario.getNetwork()), vom
        // Aufrufer aus prepareScenario(...) heraus.
        //
        // PSAV/SSAV NICHT hier: seit Schritt 8 sind das echte DRT-Flotten
        // (matsim-contrib "drt"), keine einfachen Netzwerk-Modi mehr - sie
        // bekommen ihr eigenes Routing/QSim-Handling ueber MultiModeDrtModule
        // (siehe unten und prepareDrtFleets). Waeren sie ZUSAETZLICH hier drin,
        // wuerde MATSims generisches NetworkRouting mit dem DRT-eigenen Routing
        // um denselben Modusnamen konkurrieren.
        RoutingConfigGroup routingConfig = config.routing();
        Set<String> networkModes = new LinkedHashSet<>(routingConfig.getNetworkModes());
        networkModes.add(alternatives.AV.getMatsimMode());
        routingConfig.setNetworkModes(networkModes);

        Set<String> mainModes = new LinkedHashSet<>(config.qsim().getMainModes());
        mainModes.add(alternatives.AV.getMatsimMode());
        config.qsim().setMainModes(mainModes);

        // AV auch bei der Reisezeitmessung mitzaehlen, damit es seine EIGENE
        // beobachtete (kongestionsabhaengige) Reisezeit fuers Routing bekommt, statt
        // stillschweigend auf FreeSpeedTravelTime zurueckzufallen (siehe
        // TravelTimeCalculatorModule: nur Modi in analyzedModes bekommen einen echten
        // TravelTimeCalculator).
        Set<String> analyzedModes = new LinkedHashSet<>(config.travelTimeCalculator().getAnalyzedModes());
        analyzedModes.add(alternatives.AV.getMatsimMode());
        config.travelTimeCalculator().setAnalyzedModes(analyzedModes);

        // Schritt 8 (SAV-Flotte): PSAV/SSAV als echte DRT-Flotten mit Dispatch,
        // Warteschlangen und Leerfahrten (Rebalancing) statt reiner Netzwerk-Modi.
        // multiModeDrt/dvrp kommen aus der Szenario-Config (siehe
        // oberlausitz-dresden/config.xml) - hier nur materialisieren (siehe
        // behaviourConfigGroup.getOrCreate-Javadoc fuer denselben Mechanismus bei
        // generischen vs. typisierten ConfigGroups) und die DRT-Interaktions-
        // Scoringparameter (Boarding-/Alighting-Pseudoaktivitaet) ergaenzen.
        getOrCreateTyped(config, DvrpConfigGroup.GROUP_NAME, DvrpConfigGroup.class, DvrpConfigGroup::new);
        MultiModeDrtConfigGroup multiModeDrtConfig = getOrCreateTyped(
                config, MultiModeDrtConfigGroup.GROUP_NAME, MultiModeDrtConfigGroup.class, MultiModeDrtConfigGroup::new);
        DrtConfigs.adjustMultiModeDrtConfig(multiModeDrtConfig, config.scoring(), config.routing());

        // MATSims eigene (von unserer behaviourUtilityFunction unabhaengige) Kern-
        // Scoringfunktion registriert beim Start automatisch ModeParams-Defaults fuer
        // ihre bekannten Modi (car/pt/walk/bike/ride/other), aber nicht fuer AV/SAV -
        // ohne Eintrag bricht sie ab, sobald ein Agent tatsaechlich av/sav waehlt
        // ("No scoring parameters definded for mode"). Deshalb hier mit denselben
        // Klassen-Defaults (keine erfundenen Werte) nachregistrieren, analog zu dem,
        // was MATSim selbst fuer car/pt/... tut.
        ScoringConfigGroup scoringConfig = config.scoring();
        for (alternatives mode : new alternatives[] { alternatives.AV, alternatives.PSAV, alternatives.SSAV }) {
            if (!scoringConfig.getModes().containsKey(mode.getMatsimMode())) {
                scoringConfig.addModeParams(new ScoringConfigGroup.ModeParams(mode.getMatsimMode()));
            }
        }
    }

    /**
     * Netzwerk-seitiger Teil der Einbindung fuer Schritt 7 (Netzwerkrouting). Muss
     * aus prepareScenario(...) aufgerufen werden (config-Phase hat noch kein Network).
     * Erlaubt AV auf jedem Link, der CA bereits erlaubt - dieselben Strassen,
     * dieselbe Stau-Dynamik. Modifiziert das uebergebene Network in-place.
     *
     * PSAV/SSAV NICHT hier: die brauchen ein Bediengebiet, siehe
     * addServiceAreaModesToLinks(...) - sonst baut useModeFilteredSubnetwork
     * (siehe multiModeDrt-Config) ein "gefiltertes" Subnetz, das genauso gross
     * wie das volle Netz ist (Schritt 12: bei Oberlausitz/Dresden bekamen
     * PSAV/SSAV bislang JEDEN car-Link, das Netz ist aber 640 km x 840 km -
     * weit groesser als ein plausibles SAV-Bediengebiet. Die DVRP-Fahrzeit-
     * Matrix (Sparse-Node-Matrix) skaliert mit der Knotenzahl DES DEN
     * Modi ERLAUBTEN Subnetzes, nicht mit der Zonengroesse - das war der
     * dominante Kostentreiber, nicht die Zonierung).
     */
    public static void addNetworkModesToLinks(Network network) {
        String carMode = alternatives.CA.getMatsimMode();
        Set<String> avOnly = Set.of(alternatives.AV.getMatsimMode());

        for (Link link : network.getLinks().values()) {
            if (link.getAllowedModes().contains(carMode)) {
                Set<String> modes = new LinkedHashSet<>(link.getAllowedModes());
                modes.addAll(avOnly);
                link.setAllowedModes(modes);
            }
        }
    }

    /**
     * Erlaubt PSAV/SSAV auf jedem Link, der CA bereits erlaubt UND dessen
     * Koordinate innerhalb der uebergebenen Bounding Box liegt (Netz-Koordinaten,
     * dieselbe CRS wie das Network, bei Oberlausitz/Dresden EPSG:25832) - siehe
     * addNetworkModesToLinks-Javadoc fuer die Begruendung (DVRP-Fahrzeit-Matrix
     * skaliert mit der Knotenzahl des SAV-Subnetzes). Getrennt von
     * addNetworkModesToLinks(...), weil AV (privates Fahrzeug, kein DVRP,
     * keine teure Matrix) bewusst das VOLLE Netz behaelt.
     */
    public static void addServiceAreaModesToLinks(Network network,
            double minX, double minY, double maxX, double maxY) {
        String carMode = alternatives.CA.getMatsimMode();
        Set<String> serviceAreaModes = Set.of(alternatives.PSAV.getMatsimMode(), alternatives.SSAV.getMatsimMode());

        for (Link link : network.getLinks().values()) {
            if (!link.getAllowedModes().contains(carMode)) {
                continue;
            }
            Coord coord = link.getCoord();
            if (coord.getX() < minX || coord.getX() > maxX || coord.getY() < minY || coord.getY() > maxY) {
                continue;
            }
            Set<String> modes = new LinkedHashSet<>(link.getAllowedModes());
            modes.addAll(serviceAreaModes);
            link.setAllowedModes(modes);
        }
    }

    /**
     * Fahrzeugtyp-seitiger Teil der Einbindung fuer Schritt 7. Manche Szenarien
     * (z. B. Oberlausitz/Dresden mit vehiclesSource=modeVehicleTypesFromVehiclesData) verlangen
     * einen registrierten VehicleType pro QSim-Hauptmodus - ohne AV/PSAV/SSAV-Eintrag
     * bricht die Simulation mit "Could not find requested vehicle type" ab. Klont
     * dafuer den CA-Fahrzeugtyp (gleiche physische Eigenschaften) fuer AV/PSAV/SSAV. Bei
     * Szenarien ohne registrierte Fahrzeugtypen (z. B. equil, defaultVehicle-Quelle)
     * ist das ein No-Op - muss trotzdem aus prepareScenario(...) aufgerufen werden.
     */
    public static void addVehicleTypesForModes(Scenario scenario) {
        Vehicles vehicles = scenario.getVehicles();
        Id<VehicleType> carTypeId = Id.create(alternatives.CA.getMatsimMode(), VehicleType.class);
        VehicleType carType = vehicles.getVehicleTypes().get(carTypeId);
        if (carType == null) {
            return;
        }

        for (alternatives mode : new alternatives[] { alternatives.AV, alternatives.PSAV, alternatives.SSAV }) {
            Id<VehicleType> typeId = Id.create(mode.getMatsimMode(), VehicleType.class);
            if (!vehicles.getVehicleTypes().containsKey(typeId)) {
                VehicleType newType = VehicleUtils.createVehicleType(typeId, mode.getMatsimMode());
                VehicleUtils.copyFromTo(carType, newType);
                vehicles.addVehicleType(newType);
            }
        }
    }

    /**
     * Flotten-seitiger Teil der Einbindung fuer Schritt 8 (SAV-Flotte). Muss aus
     * prepareScenario(...) aufgerufen werden, NACH addNetworkModesToLinks(...)
     * (die Flotte braucht Links, die PSAV/SSAV bereits erlauben) und nachdem
     * configureController(config) bereits in prepareConfig(...) lief (die
     * multiModeDrt-Config muss materialisiert sein, siehe getOrCreateTyped).
     *
     * Erzeugt fuer PSAV und SSAV je eine DRT-Flottendatei aus dem tatsaechlich
     * geladenen Netzwerk (siehe behaviourDrtFleetGenerator-Javadoc: Oberlausitz/
     * Dresden hat keine statischen, bekannten Link-IDs) und traegt deren Pfad in
     * die jeweilige DrtConfigGroup ein - der vehiclesFile-Wert aus der XML ist
     * nur ein Platzhalter, der hier ueberschrieben wird. Registriert ausserdem
     * die DrtRouteFactory, die zur (De-)Serialisierung von PSAV/SSAV-Routen
     * gebraucht wird.
     *
     * Die generierte Flottendatei landet in einem TEMP-Verzeichnis, bewusst
     * NICHT im konfigurierten Output-Verzeichnis: dessen Aufraeumen
     * (overwriteFiles=deleteDirectoryIfExists) laeuft im Controler-Lifecycle
     * later als dieser Aufruf, wuerde die Datei also wieder loeschen, bevor
     * FleetModule sie liest.
     *
     * fleetSize/capacity je Modus sind PLATZHALTER (nicht aus einer echten
     * Bedarfsanalyse kalibriert) - der Aufrufer uebergibt sie explizit, damit
     * behaviourModule selbst szenario-unabhaengig bleibt (siehe Klassen-Javadoc).
     *
     * psavStopCount (Schritt 9): PSAV ist ein gepooltes, MOIA-artiges System MIT
     * virtuellen Haltestellen (operationalScheme=stopbased in der Config) - im
     * Unterschied zu SSAV (Door-to-Door-Robotaxi ohne Pooling, capacity=1 vom
     * Aufrufer zu setzen). Erzeugt genau wie die Flotte eine PLATZHALTER-Datei
     * aus dem tatsaechlich geladenen Netzwerk (siehe behaviourDrtStopGenerator).
     */
    public static void prepareDrtFleets(Scenario scenario,
                                         int psavFleetSize, int psavCapacity, int psavStopCount,
                                         int ssavFleetSize, int ssavCapacity) {

        scenario.getPopulation().getFactory().getRouteFactories()
                .setRouteFactory(DrtRoute.class, new DrtRouteFactory());

        Config config = scenario.getConfig();
        MultiModeDrtConfigGroup multiModeDrtConfig = MultiModeDrtConfigGroup.get(config);
        long randomSeed = behaviourConfigGroup.getOrCreate(config).getRandomSeed();

        Path drtDir;
        try {
            drtDir = Files.createTempDirectory("behaviourModule-drt-fleets");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        for (DrtConfigGroup drtConfig : multiModeDrtConfig.getModalElements()) {
            // Schritt 14: travelTimeMatrixCachePath-Verzeichnis (z. B. "cache/psav-
            // ttmatrix.cache", siehe oberlausitz-dresden/config.xml) muss existieren,
            // BEVOR DVRP versucht, die Fahrzeit-Matrix dorthin zu schreiben - siehe
            // ensureTravelTimeMatrixCacheDirectoryExists-Javadoc fuer die Endlosschleife,
            // die sonst entsteht.
            ensureTravelTimeMatrixCacheDirectoryExists(config, drtConfig);

            int fleetSize;
            int capacity;
            boolean isPsav = alternatives.PSAV.getMatsimMode().equals(drtConfig.getMode());
            boolean isSsav = alternatives.SSAV.getMatsimMode().equals(drtConfig.getMode());
            if (!isPsav && !isSsav) {
                continue;
            }

            // Schritt 13: Depot-/Haltestellenlinks aus dem GLEICHEN mode-gefilterten
            // UND bereinigten Subnetz ziehen, das DVRP intern tatsaechlich benutzt
            // (useModeFilteredSubnetwork=true), nicht aus dem rohen Network - sonst
            // kann ein Link zwar den Modus erlauben, aber als nicht erreichbare
            // Sackgasse/Insel innerhalb des Bediengebiets vom
            // MultimodalNetworkCleaner aus DEM Subnetz entfernt worden sein
            // ("Start link ... is null. Please make sure the link is part of the
            // mode-filtered (and cleaned?) network!"). Siehe
            // DvrpModeRoutingNetworkModule.install() fuer die Original-Logik, die
            // hier 1:1 nachgebaut wird.
            Network cleanedSubnetwork = createCleanedModeSubnetwork(scenario.getNetwork(), drtConfig.getMode());

            if (isPsav) {
                fleetSize = psavFleetSize;
                capacity = psavCapacity;

                Path stopsFile = drtDir.resolve(drtConfig.getMode() + "-stops.xml");
                behaviourDrtStopGenerator.writeStops(cleanedSubnetwork, drtConfig.getMode(), psavStopCount,
                        randomSeed, stopsFile);
                drtConfig.setTransitStopFile(stopsFile.toUri().toString());
            } else {
                fleetSize = ssavFleetSize;
                capacity = ssavCapacity;
            }

            Path fleetFile = drtDir.resolve(drtConfig.getMode() + "-fleet.xml");
            behaviourDrtFleetGenerator.writeFleet(cleanedSubnetwork, drtConfig.getMode(), fleetSize, capacity,
                    0.0, 30 * 3600.0, randomSeed, fleetFile);
            drtConfig.setVehiclesFile(fleetFile.toUri().toString());
        }
    }

    /**
     * Baut dasselbe mode-gefilterte, bereinigte Subnetz, das DVRP intern fuer
     * useModeFilteredSubnetwork=true verwendet (siehe
     * DvrpModeRoutingNetworkModule.install(), dort identische Reihenfolge:
     * TransportModeNetworkFilter dann NetworkUtils.cleanNetwork). Nur Links,
     * die diese Bereinigung ueberleben, sind als DRT-Fahrzeug-Startlink/
     * Haltestellenlink gueltig - alles andere wirft zur Laufzeit
     * "Start link ... is null" (siehe prepareDrtFleets-Javadoc).
     */
    private static Network createCleanedModeSubnetwork(Network network, String mode) {
        Network subnetwork = NetworkUtils.createNetwork();
        new TransportModeNetworkFilter(network).filter(subnetwork, Set.of(mode));
        NetworkUtils.cleanNetwork(subnetwork, Set.of(mode));
        return subnetwork;
    }

    /**
     * Schritt 14: legt das Verzeichnis von drtConfig.getTravelTimeMatrixCachePath()
     * an, falls noch nicht vorhanden. No-Op, wenn kein Cache-Pfad konfiguriert ist.
     *
     * Hintergrund (siehe RunOberlausitzDresdenTest-Klassen-Javadoc/Bug-Analyse eines
     * echten Laufs): DVRPs FreeSpeedTravelTimeMatrix.write(...) schreibt ueber einen
     * rohen java.io.FileOutputStream (siehe MATSims IOUtils.getOutputStream) - der
     * legt KEIN fehlendes Elternverzeichnis an, sondern wirft FileNotFoundException.
     * Ohne dieses Vorab-Anlegen scheitert der erste Schreibversuch der (mehrere GB
     * grossen, ~2 Minuten dauernden) Matrix still: die Exception landet in einem
     * QSim-internen Worker-Thread (DRTs insertion-search-ForkJoinPool baut pro
     * numberOfThreads-Worker eine eigene, ungescopte DetourTimeEstimator-Instanz,
     * die ihrerseits die modale TravelTimeMatrix anfordert - siehe
     * ExtensiveInsertionSearchQSimModule/DrtInsertionSearchManager in
     * matsim-contrib-drt), sodass Guice den fehlgeschlagenen Singleton nie erfolgreich
     * cacht. Jeder Worker-Thread (und jeder der beiden Modi PSAV/SSAV) berechnet die
     * Matrix daraufhin unabhaengig komplett neu, scheitert beim Schreiben erneut usw. -
     * das haengt die Simulation VOR Iteration 0 in einer Endlosschleife auf (im Log
     * sichtbar als "Freespeed matrix cache file not found", das sich alle ~2 Minuten
     * wiederholt, ohne dass die Mobsim je startet), statt sauber abzustuerzen oder
     * die Matrix einmalig zu cachen.
     */
    private static void ensureTravelTimeMatrixCacheDirectoryExists(Config config, DrtConfigGroup drtConfig) {
        String cachePath = drtConfig.getTravelTimeMatrixCachePath();
        if (cachePath == null || cachePath.isBlank()) {
            return;
        }
        try {
            URL url = ConfigGroup.getInputFileURL(config.getContext(), cachePath);
            Path parent = Paths.get(url.toURI()).getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException | URISyntaxException e) {
            throw new UncheckedIOException(new IOException(
                    "Konnte Cache-Verzeichnis fuer travelTimeMatrixCachePath='" + cachePath
                            + "' (Modus " + drtConfig.getMode() + ") nicht anlegen.", e));
        }
    }

    /**
     * Generische Variante von behaviourConfigGroup.getOrCreate(...) fuer fremde
     * ConfigGroup-Klassen (MultiModeDrtConfigGroup/DvrpConfigGroup), deren
     * eigene statische get(config)-Methoden KEINE Materialisierung vornehmen -
     * ein direkter Cast, der eine ClassCastException wirft, wenn das Modul noch
     * generisch/ungetypt vorliegt (siehe behaviourConfigGroup.getOrCreate-Javadoc
     * fuer denselben MATSim-Mechanismus: config.addModule(...) kopiert alle
     * Params/Parametersets aus der generischen in die typisierte Instanz).
     */
    private static <T extends ConfigGroup> T getOrCreateTyped(
            Config config, String groupName, Class<T> type, Supplier<T> factory) {
        ConfigGroup existing = config.getModules().get(groupName);
        if (type.isInstance(existing)) {
            return type.cast(existing);
        }
        T group = factory.get();
        config.addModule(group);
        return group;
    }

    @Override
    public void install() {

        behaviourConfigGroup cfg = (behaviourConfigGroup)
                getConfig().getModules().get(behaviourConfigGroup.GROUP_NAME);

        if (cfg == null) {
            throw new IllegalStateException(
                    "Der Config-Block '" + behaviourConfigGroup.GROUP_NAME
                            + "' fehlt. Wurde behaviourModule.configureController(config) in "
                            + "prepareConfig(...) aufgerufen?");
        }

        // Die Nutzenfunktion als Singleton bereitstellen.
        bind(behaviourUtilityFunction.class)
                .toInstance(new behaviourUtilityFunction(cfg.getScaleParameter()));

        // behaviourConfigGroup selbst NICHT manuell binden: MATSims ExplodedConfigModule
        // bindet automatisch jede in der Config registrierte ConfigGroup auf ihre
        // konkrete Klasse (config.addModule(...) in getOrCreate(...) reicht dafuer aus).

        // discrete_mode_choice-Contrib mit einbinden, damit ein Aufrufer nur diese
        // eine Modulklasse registrieren muss.
        install(new DiscreteModeChoiceModule());

        // Eigenen TripEstimator ("verhalten") registrieren, der behaviourUtilityFunction
        // nutzt - siehe configureController(...) fuer die zugehoerige Config-Aktivierung.
        install(new behaviourDiscreteModeChoiceExtension());

        // Schritt 8 (SAV-Flotte): DVRP/DRT-Contrib fuer PSAV/SSAV als echte
        // Flotten mit Dispatch, Warteschlangen und Leerfahrten. Bindet u. a.
        // FleetSpecification/DrtOptimizer/PassengerHandler je Modus sowie die
        // DRT-eigenen RoutingModule (fuer die A-priori-Wartezeitschaetzung, die
        // discrete_mode_choice VOR der Simulation braucht) - siehe
        // MultiModeDrtModule-Javadoc. Die QSim-Komponenten selbst werden NICHT
        // hier aktiviert (Guice-Module haben keinen Controler-Zugriff), sondern
        // vom Aufrufer aus prepareControler(...) via
        // controler.configureQSimComponents(DvrpQSimComponents.activateAllModes(...)).
        install(new DvrpModule());
        install(new MultiModeDrtModule());

        // Schritt 11: PSAV-/SSAV-Flottengroesse zwischen Iterationen an die
        // beobachtete Auslastung anpassen, statt sie starr zu lassen - siehe
        // behaviourDrtFleetSizeController-Javadoc.
        addControllerListenerBinding().to(behaviourDrtFleetSizeController.class);
    }
}