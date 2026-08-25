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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.Config;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.StartupListener;
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
import org.matsim.vehicles.Vehicles;

import com.google.inject.Inject;

/**
 * Kalibriert die ASC-Werte von PT/BIKE/WALK/RIDE gemeinsam so, dass die
 * Softmax-Verteilung ueber das Choice-Set {CA, PT, BIKE, WALK, RIDE} - die
 * heute reale, ohne jede AVM-Alternative existierende Modus-Welt - den
 * TATSAECHLICH ERHOBENEN Modal Split der Population trifft.
 *
 * HINTERGRUND (Auftraggeber-Vorgabe): BIKE/WALK/RIDE haben keine SLR-
 * Konstrukt-/Zeit-/Kostendaten (die gamma/beta-Struktur stammt aus einer
 * Studie speziell zur AVM-Akzeptanz, siehe alternatives-Klassen-Javadoc) -
 * sie laufen deshalb NUR ueber ihren ASC-Wert (modeParams mit allen Beta-/
 * Gamma-Koeffizienten auf 0). Ein hartcodierter ASC=0-Platzhalter wuerde
 * aber willkuerlich einen Modal Split erzeugen, der nichts mit der Realitaet
 * zu tun hat. Die Loesung: GENAU wie beim ascNull-Zusatzweg (siehe
 * behaviourCandidateTripInserter.calibrateAscNull) wird NICHT gegen eine
 * angenommene ZUKUNFT kalibriert (das waere zirkulaer), sondern gegen die
 * bereits real erhobene, HEUTIGE Modus-Verteilung - das ist ein bekannter,
 * unabhaengiger Datenpunkt, keine Annahme ueber das Ergebnis des Modells.
 *
 * CA bleibt FESTE REFERENZ (ASC_CA = 0, unveraendert) - ein Modus muss bei
 * einer Mehrfach-ASC-Kalibrierung immer fix bleiben, sonst ist das
 * Gleichungssystem nicht eindeutig loesbar (nur ASC-DIFFERENZEN sind in
 * einem Logit-Modell identifizierbar, nicht die absoluten Werte). PT behaelt
 * dabei seine echten gamma-/beta-Terme (aus den 5 AVM-Alternativen
 * uebernommen) UND bekommt zusaetzlich einen kalibrierten ASC-Offset;
 * AV/PSAV/SSAV sind NICHT Teil dieser Kalibrierung (sie existieren in der
 * erhobenen Population gar nicht, koennten also auch keinen beobachteten
 * Anteil beisteuern - ihr ASC bleibt unabhaengig davon bei 0, siehe
 * behaviourModule-Klassen-Javadoc "Zirkularitaet" fuer die Begruendung).
 *
 * ALGORITHMUS (Standard-ASC-Kalibrierung aus der Verkehrsmodellierung,
 * iterative Erfolgsquoten-Anpassung/"IPF-Stil"):
 *   1. Phase 1 (teuer, einmalig): fuer JEDEN bestehenden Weg JEDER Person
 *      den beobachteten Modus UND - sofern CA/PT verfuegbar/routbar - deren
 *      gerouteten, gamma-basierten Nutzenwert (OHNE ASC) berechnen. Analog
 *      zu calibrateAscNull's Baseline-Wege-Phase, aber hier zusaetzlich mit
 *      dem beobachteten Modus fuer die Zielanteile.
 *   2. Phase 2 (billig, iterativ): mit einem ASC-Vektor (Start 0 fuer PT/
 *      BIKE/WALK/RIDE, CA fest 0) wird je Iteration die erwartete
 *      (Softmax-)Modusverteilung ueber ALLE Wege gemittelt und mit
 *      ASC_j += ln(Zielanteil_j / erwarteter_Anteil_j) aktualisiert - der
 *      Standardweg, um ASCs gegen beobachtete Anteile zu kalibrieren (macht
 *      aus dem Modell bei Konvergenz eine Softmax-Verteilung, deren
 *      MARGINALE Anteile exakt die Zielanteile treffen).
 *
 * Beendet den Prozess nach dem Schreiben des Ergebnisses (System.exit) -
 * siehe behaviourConfigGroup.baselineAscKalibrierungAktiv-Javadoc, NIEMALS
 * gleichzeitig mit ascNullKalibrierungAktiv aktivieren.
 */
public final class behaviourBaselineAscCalibrator implements StartupListener {

    private static final Logger log = LogManager.getLogger(behaviourBaselineAscCalibrator.class);

    /** Das Choice-Set dieser Kalibrierung - die heute reale, AVM-freie Modus-Welt. */
    private static final Set<alternatives> CALIBRATION_MODES = EnumSet.of(
            alternatives.CA, alternatives.PT, alternatives.BIKE, alternatives.WALK, alternatives.RIDE);

    /** Fixe Referenzalternative (ASC bleibt 0) - siehe Klassen-Javadoc. */
    private static final alternatives REFERENCE_MODE = alternatives.CA;

    private static final int MAX_ITERATIONS = 500;
    private static final double CONVERGENCE_TOLERANCE = 1e-6;

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
    public behaviourBaselineAscCalibrator(Population population, ActivityFacilities facilities,
            TripRouter tripRouter, TimeInterpretation timeInterpretation, behaviourUtilityFunction utilityFunction,
            behaviourModeAvailability modeAvailability, behaviourConfigGroup cfg, Vehicles vehicles, Config config) {
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

    /** Phase-1-Ergebnis eines Weges: beobachteter Modus + gamma-basierte Nutzenwerte OHNE ASC (CA/PT, sofern verfuegbar/routbar). */
    private record baselineTripContext(alternatives observedMode, Double caUtilityWithoutAsc, Double ptUtilityWithoutAsc) {
    }

    @Override
    public void notifyStartup(StartupEvent event) {
        if (!cfg.getBaselineAscKalibrierungAktiv()) {
            return;
        }
        calibrate();
    }

    private void calibrate() {
        String segmentAttribute = cfg.getSegmentAttribute();
        long randomSeed = cfg.getRandomSeed();
        Map<alternatives, modeParams> modeParamsByAlternative = cfg.buildModeParams();
        Map<String, agentProfile> segmentsById = cfg.buildSegments();

        modeParams caParams = modeParamsByAlternative.get(alternatives.CA);
        modeParams ptParams = modeParamsByAlternative.get(alternatives.PT);

        int totalPersons = population.getPersons().size();
        log.info("Baseline-ASC-Kalibrierung: Phase 1 (Routing CA/PT je bestehendem Weg, einmalig) fuer "
                + totalPersons + " Personen...");

        List<baselineTripContext> contexts = new ArrayList<>();
        Map<alternatives, Integer> observedCounts = new EnumMap<>(alternatives.class);
        for (alternatives mode : CALIBRATION_MODES) {
            observedCounts.put(mode, 0);
        }
        int skippedUnknownMode = 0;
        int skippedNoDepartureTime = 0;

        for (Person person : population.getPersons().values()) {
            agentProfile profile = resolveProfile(person, segmentAttribute, segmentsById)
                    .draw(new Random(tripContextBuilder.personSeed(randomSeed, person.getId(), "profile")));
            Collection<String> availableModes = modeAvailability.getAvailableModes(person, List.of());

            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
            for (int tripIndex = 0; tripIndex < trips.size(); tripIndex++) {
                TripStructureUtils.Trip trip = trips.get(tripIndex);
                Leg firstLeg = (Leg) trip.getTripElements().get(0);
                String routingMode = TripStructureUtils.getRoutingMode(firstLeg);
                alternatives observed = alternatives.fromMatsimMode(routingMode);
                if (observed == null || !CALIBRATION_MODES.contains(observed)) {
                    // AV/PSAV/SSAV existieren in der erhobenen Population nicht - defensiv,
                    // falls doch ein unbekannter/nicht abgedeckter Modus auftaucht.
                    skippedUnknownMode++;
                    continue;
                }
                Activity originActivity = trip.getOriginActivity();
                Activity destinationActivity = trip.getDestinationActivity();
                if (originActivity.getEndTime().isUndefined()) {
                    // kein verlaesslicher Abfahrtszeitpunkt - siehe collectTemplateProviders-Javadoc.
                    // WICHTIG: observedCounts erst NACH diesem Check erhoehen, sonst zaehlt der
                    // Zielanteil (Nenner = observedCounts-Summe) mehr Wege als tatsaechlich in
                    // contexts landen (Nenner von "total" unten) - die beiden Groessen muessen
                    // dieselbe Wegemenge beschreiben, sonst summieren sich die Zielanteile nicht
                    // auf 1.0 und Phase 2 kalibriert gegen eine inkonsistente Zielverteilung.
                    skippedNoDepartureTime++;
                    continue;
                }
                observedCounts.merge(observed, 1, Integer::sum);
                double departureTime = originActivity.getEndTime().seconds();
                Facility originFacility = FacilitiesUtils.toFacility(originActivity, facilities);
                Facility destinationFacility = FacilitiesUtils.toFacility(destinationActivity, facilities);

                Double caUtility = null;
                if (availableModes.contains(alternatives.CA.getMatsimMode())) {
                    caUtility = routeAndEstimate(person, alternatives.CA, caParams, profile, randomSeed,
                            originFacility, destinationFacility, departureTime, tripIndex);
                }
                Double ptUtility = null;
                if (availableModes.contains(alternatives.PT.getMatsimMode())) {
                    ptUtility = routeAndEstimate(person, alternatives.PT, ptParams, profile, randomSeed,
                            originFacility, destinationFacility, departureTime, tripIndex);
                }

                contexts.add(new baselineTripContext(observed, caUtility, ptUtility));
            }
        }

        int total = contexts.size();
        log.info("Baseline-ASC-Kalibrierung: Phase 1 fertig (" + total + " Wege, " + skippedUnknownMode
                + " mit unbekanntem/nicht abgedecktem Modus uebersprungen, " + skippedNoDepartureTime
                + " ohne verlaesslichen Abfahrtszeitpunkt uebersprungen), Phase 2 (iterative ASC-Anpassung)...");

        Map<alternatives, Double> targetShare = new EnumMap<>(alternatives.class);
        for (alternatives mode : CALIBRATION_MODES) {
            targetShare.put(mode, observedCounts.get(mode) / (double) total);
        }

        Map<alternatives, Double> asc = new EnumMap<>(alternatives.class);
        for (alternatives mode : CALIBRATION_MODES) {
            asc.put(mode, 0.0);
        }

        double scaleParameter = cfg.getScaleParameter();
        Map<alternatives, Double> predictedShare = new EnumMap<>(alternatives.class);
        int iterationsRun = 0;
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            iterationsRun = iteration + 1;
            Map<alternatives, Double> predictedSum = new EnumMap<>(alternatives.class);
            for (alternatives mode : CALIBRATION_MODES) {
                predictedSum.put(mode, 0.0);
            }

            for (baselineTripContext ctx : contexts) {
                Map<alternatives, Double> utilities = new EnumMap<>(alternatives.class);
                if (ctx.caUtilityWithoutAsc() != null) {
                    utilities.put(alternatives.CA, ctx.caUtilityWithoutAsc() + asc.get(alternatives.CA));
                }
                if (ctx.ptUtilityWithoutAsc() != null) {
                    utilities.put(alternatives.PT, ctx.ptUtilityWithoutAsc() + asc.get(alternatives.PT));
                }
                utilities.put(alternatives.BIKE, asc.get(alternatives.BIKE));
                utilities.put(alternatives.WALK, asc.get(alternatives.WALK));
                utilities.put(alternatives.RIDE, asc.get(alternatives.RIDE));

                Map<alternatives, Double> probabilities = behaviourUtilityFunction.softmax(utilities, scaleParameter);
                for (Map.Entry<alternatives, Double> entry : probabilities.entrySet()) {
                    predictedSum.merge(entry.getKey(), entry.getValue(), Double::sum);
                }
            }

            double maxAbsDelta = 0.0;
            for (alternatives mode : CALIBRATION_MODES) {
                double share = predictedSum.get(mode) / total;
                predictedShare.put(mode, share);
                if (mode == REFERENCE_MODE) {
                    continue;
                }
                double safeShare = Math.max(share, 1e-9);
                double delta = Math.log(targetShare.get(mode) / safeShare);
                asc.merge(mode, delta, Double::sum);
                maxAbsDelta = Math.max(maxAbsDelta, Math.abs(delta));
            }

            if (maxAbsDelta < CONVERGENCE_TOLERANCE) {
                break;
            }
        }

        log.info(String.format(Locale.ROOT,
                "Baseline-ASC-Kalibrierung fertig nach %d Iterationen:", iterationsRun));
        for (alternatives mode : CALIBRATION_MODES) {
            log.info(String.format(Locale.ROOT, "  %-4s: ASC=%.6f  Ziel-Anteil=%.4f  erreichter Anteil=%.4f",
                    mode.name(), asc.get(mode), targetShare.get(mode), predictedShare.get(mode)));
        }

        writeCalibrationResult(asc, targetShare, predictedShare, iterationsRun, total);

        log.info("Baseline-ASC-Kalibrierung: beende den Prozess (keine MATSim-Iterationen fuer einen "
                + "reinen Kalibrierungslauf noetig).");
        System.exit(0);
    }

    private Double routeAndEstimate(Person person, alternatives alternative, modeParams meanParams,
            agentProfile profile, long randomSeed, Facility originFacility, Facility destinationFacility,
            double departureTime, int tripIndex) {
        if (alternative == alternatives.CA) {
            behaviourCandidateTripInserter.ensureVehicleId(vehicles, person, alternative.getMatsimMode());
        }
        modeParams params = meanParams.draw(
                new Random(tripContextBuilder.personSeed(randomSeed, person.getId(), "baselineAsc" + tripIndex + alternative.name())),
                cfg.resolveIncomeTier(person));
        List<? extends PlanElement> routed;
        try {
            routed = tripRouter.calcRoute(alternative.getMatsimMode(), originFacility, destinationFacility,
                    departureTime, person, new AttributesImpl());
        } catch (RuntimeException e) {
            return null;
        }
        double costPerKm = params.effectiveCostPerKm(cfg.hasTicket(person));
        TripContext tripContext = tripContextBuilder.buildTripContext(timeInterpretation, departureTime, routed, costPerKm);
        return utilityFunction.utility(profile, params, tripContext, null);
    }

    /** Identisch zu behaviourCandidateTripInserter.resolveProfile - siehe dortigen Javadoc. */
    private static agentProfile resolveProfile(Person person, String segmentAttribute, Map<String, agentProfile> segmentsById) {
        Object value = person.getAttributes().getAttribute(segmentAttribute);
        agentProfile profile = value == null ? null : segmentsById.get(value.toString());
        return profile != null ? profile : new agentProfile("__neutral__", Map.of());
    }

    private void writeCalibrationResult(Map<alternatives, Double> asc, Map<alternatives, Double> targetShare,
            Map<alternatives, Double> predictedShare, int iterationsRun, int totalWege) {
        try {
            Path directory = Path.of(config.controller().getOutputDirectory());
            Files.createDirectories(directory);
            Path csvPath = directory.resolve("baseline_asc_kalibrierung.csv");
            StringBuilder sb = new StringBuilder();
            sb.append("modus;asc;zielAnteil;erreichterAnteil\n");
            for (alternatives mode : CALIBRATION_MODES) {
                sb.append(mode.name()).append(';')
                        .append(String.format(Locale.ROOT, "%.6f", asc.get(mode))).append(';')
                        .append(String.format(Locale.ROOT, "%.6f", targetShare.get(mode))).append(';')
                        .append(String.format(Locale.ROOT, "%.6f", predictedShare.get(mode)))
                        .append('\n');
            }
            sb.append("# iterationen=").append(iterationsRun).append(";wegeGesamt=").append(totalWege).append('\n');
            Files.writeString(csvPath, sb.toString(), StandardCharsets.UTF_8);
            log.info("Baseline-ASC-Kalibrierung: Ergebnis nach " + csvPath + " geschrieben.");
        } catch (IOException e) {
            throw new UncheckedIOException("baseline_asc_kalibrierung.csv konnte nicht geschrieben werden.", e);
        }
    }
}
