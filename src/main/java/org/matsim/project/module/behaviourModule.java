package org.matsim.project.module;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contribs.discrete_mode_choice.modules.DiscreteModeChoiceConfigurator;
import org.matsim.contribs.discrete_mode_choice.modules.DiscreteModeChoiceModule;
import org.matsim.contribs.discrete_mode_choice.modules.EstimatorModule;
import org.matsim.contribs.discrete_mode_choice.modules.SelectorModule;
import org.matsim.contribs.discrete_mode_choice.modules.config.DiscreteModeChoiceConfigGroup;
import org.matsim.core.config.Config;
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

        // Schritt 7 (Netzwerkrouting): AV/SAV nutzen dasselbe Strassennetz wie CA und
        // erleben dieselbe Stau-Dynamik, statt teleportiert zu werden. Dazu reicht es,
        // sie zu routing().networkModes und qsim().mainModes hinzuzufuegen - MATSims
        // eigene TravelTimeCalculatorModule/TravelDisutilityModule iterieren beide
        // automatisch ueber routing().getNetworkModes() und binden pro Modus TravelTime/
        // TravelDisutility (FreeSpeedTravelTime, falls der Modus nicht zusaetzlich in
        // travelTimeCalculator().analyzedModes steht - siehe unten). Kein eigenes
        // Guice-Binding noetig. Die Netzlinks selbst muessen AV/SAV zusaetzlich zu CA
        // erlauben - das passiert nicht hier (kein Network in der Config-Phase
        // verfuegbar), sondern in addNetworkModesToLinks(scenario.getNetwork()), vom
        // Aufrufer aus prepareScenario(...) heraus.
        RoutingConfigGroup routingConfig = config.routing();
        Set<String> networkModes = new LinkedHashSet<>(routingConfig.getNetworkModes());
        networkModes.add(alternatives.AV.getMatsimMode());
        networkModes.add(alternatives.PSAV.getMatsimMode());
        networkModes.add(alternatives.SSAV.getMatsimMode());
        routingConfig.setNetworkModes(networkModes);

        Set<String> mainModes = new LinkedHashSet<>(config.qsim().getMainModes());
        mainModes.add(alternatives.AV.getMatsimMode());
        mainModes.add(alternatives.PSAV.getMatsimMode());
        mainModes.add(alternatives.SSAV.getMatsimMode());
        config.qsim().setMainModes(mainModes);

        // AV/PSAV/SSAV auch bei der Reisezeitmessung mitzaehlen, damit sie ihre EIGENE
        // beobachtete (kongestionsabhaengige) Reisezeit fuers Routing bekommen, statt
        // stillschweigend auf FreeSpeedTravelTime zurueckzufallen (siehe
        // TravelTimeCalculatorModule: nur Modi in analyzedModes bekommen einen echten
        // TravelTimeCalculator).
        Set<String> analyzedModes = new LinkedHashSet<>(config.travelTimeCalculator().getAnalyzedModes());
        analyzedModes.add(alternatives.AV.getMatsimMode());
        analyzedModes.add(alternatives.PSAV.getMatsimMode());
        analyzedModes.add(alternatives.SSAV.getMatsimMode());
        config.travelTimeCalculator().setAnalyzedModes(analyzedModes);

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
     * Erlaubt AV/PSAV/SSAV auf jedem Link, der CA bereits erlaubt - dieselben Strassen,
     * dieselbe Stau-Dynamik. Modifiziert das uebergebene Network in-place.
     */
    public static void addNetworkModesToLinks(Network network) {
        String carMode = alternatives.CA.getMatsimMode();
        Set<String> extraModes = Set.of(alternatives.AV.getMatsimMode(),
                alternatives.PSAV.getMatsimMode(), alternatives.SSAV.getMatsimMode());

        for (Link link : network.getLinks().values()) {
            if (link.getAllowedModes().contains(carMode)) {
                Set<String> modes = new LinkedHashSet<>(link.getAllowedModes());
                modes.addAll(extraModes);
                link.setAllowedModes(modes);
            }
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
    }
}