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
import org.matsim.project.model.behaviourUtilityFunction.UtilityComponents;
import org.matsim.project.model.modeParams;
import org.matsim.project.scoring.tripContextBuilder;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;
import org.matsim.vehicles.Vehicles;

import com.google.inject.Inject;

/**
 * Auftraggeber-Anfrage 2026-08-30 ("Gamma-Kalibrierung ueber Sensitivitaetsanalyse"), Schritt
 * 1: reine BASELINE-Messung, wie viel Prozent des Nutzens V-ASC bei den vier Alternativen mit
 * asc=0 (CA/AV/PSAV/SSAV) aktuell auf LOS-Terme (beta_inVehicleTime/waitTime/cost) vs. latente
 * Terme (gamma*Konstrukte + Habit-Delta) entfallen - GRUNDLAGE fuer eine anschliessende
 * Neuskalierung der gamma-Werte auf eine 50/50-Aufteilung (noch NICHT Teil dieser Klasse).
 *
 * METRIK: Standardabweichung (SD) von V_LOS bzw. V_Latent ueber alle bewerteten Wege/
 * Alternativen-Kombinationen dieses Laufs - NICHT der mittlere Absolutbetrag (SD ist robuster
 * gegenueber einzelnen Ausreissern und misst, wie stark ein Term die tatsaechliche
 * Entscheidungsvarianz zwischen Agenten treibt, was fuer eine Diskrete-Wahl-Analyse die
 * relevantere Groesse ist als die reine Nutzenhoehe).
 *
 * HABIT-REFERENZ: identisch zu behaviourUtilityEstimator - der Habit-Term wirkt auf die
 * AUSGANGSLAGE (der in der erhobenen Population beobachtete, ORIGINALE Modus dieses Wegs),
 * NICHT auf einen aktuellen Iterationsstand (siehe dortigen Javadoc). Da diese Analyse als
 * StartupListener VOR Iteration 0 laeuft, ist der beobachtete Modus des Plans exakt diese
 * Ausgangslage - keine gesonderte Registry noetig. AV/PSAV/SSAV kommen in der erhobenen
 * Population nie als beobachteter Modus vor, ihr Habit-Term ist in dieser Baseline-Messung
 * deshalb strukturell immer 0 (nur CA kann ueber den beobachteten Modus "car" einen von 0
 * verschiedenen Habit-Term bekommen) - das ist eine reale Eigenschaft der Ausgangslage, kein
 * Fehler dieser Analyse.
 *
 * Beendet den Prozess nach dem Schreiben des Ergebnisses (System.exit), analog zu
 * ascNullKalibrierungAktiv/baselineAscKalibrierungAktiv - NIEMALS mehrere dieser Schalter
 * gleichzeitig auf true setzen.
 */
public final class behaviourGammaSensitivityAnalyzer implements StartupListener {

    private static final Logger log = LogManager.getLogger(behaviourGammaSensitivityAnalyzer.class);

    /** Die vier Alternativen mit asc=0, fuer die die 50/50-Aufteilung ueberhaupt Sinn ergibt. */
    private static final Set<alternatives> ANALYSIS_MODES = EnumSet.of(
            alternatives.CA, alternatives.AV, alternatives.PSAV, alternatives.SSAV);

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
    public behaviourGammaSensitivityAnalyzer(Population population, ActivityFacilities facilities,
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

    @Override
    public void notifyStartup(StartupEvent event) {
        if (!cfg.getGammaSensitivitaetsAnalyseAktiv()) {
            return;
        }
        analyze();
    }

    /** Sammelt V_LOS/V_Latent je bewerteter Alternative - fuer die SD-Berechnung in Phase 2. */
    private record sample(alternatives mode, double vLos, double vHabit, double vGammaConstructs) {
        double vLatent() {
            return vHabit + vGammaConstructs;
        }
    }

    private void analyze() {
        String segmentAttribute = cfg.getSegmentAttribute();
        long randomSeed = cfg.getRandomSeed();
        Map<alternatives, modeParams> modeParamsByAlternative = cfg.buildModeParams();
        Map<String, agentProfile> segmentsById = cfg.buildSegments();

        int totalPersons = population.getPersons().size();
        log.info("Gamma-Sensitivitaetsanalyse: Phase 1 (Routing CA/AV/PSAV/SSAV je bestehendem Weg, "
                + "einmalig) fuer " + totalPersons + " Personen...");

        List<sample> samples = new ArrayList<>();
        int skippedNoDepartureTime = 0;

        for (Person person : population.getPersons().values()) {
            agentProfile profile = resolveProfile(person, segmentAttribute, segmentsById)
                    .draw(new Random(tripContextBuilder.personSeed(randomSeed, person.getId(), "profile")));
            Collection<String> availableModes = modeAvailability.getAvailableModes(person, List.of());

            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
            for (int tripIndex = 0; tripIndex < trips.size(); tripIndex++) {
                TripStructureUtils.Trip trip = trips.get(tripIndex);
                Activity originActivity = trip.getOriginActivity();
                Activity destinationActivity = trip.getDestinationActivity();
                if (originActivity.getEndTime().isUndefined()) {
                    skippedNoDepartureTime++;
                    continue;
                }

                // Habit-Referenz: der in der erhobenen Population beobachtete (Ausgangs-)Modus
                // dieses Wegs - identisch zu behaviourBaselineAscCalibrator.calibrate(). AV/
                // PSAV/SSAV kommen hier nie vor (siehe Klassen-Javadoc).
                Leg firstLeg = (Leg) trip.getTripElements().get(0);
                String routingMode = TripStructureUtils.getRoutingMode(firstLeg);
                alternatives observedMode = alternatives.fromMatsimMode(routingMode);

                double departureTime = originActivity.getEndTime().seconds();
                Facility originFacility = FacilitiesUtils.toFacility(originActivity, facilities);
                Facility destinationFacility = FacilitiesUtils.toFacility(destinationActivity, facilities);

                for (alternatives alternative : ANALYSIS_MODES) {
                    if (!availableModes.contains(alternative.getMatsimMode())) {
                        continue;
                    }
                    if (alternative == alternatives.CA || alternative == alternatives.AV) {
                        behaviourCandidateTripInserter.ensureVehicleId(vehicles, person, alternative.getMatsimMode());
                    }
                    modeParams meanParams = modeParamsByAlternative.get(alternative);
                    modeParams params = meanParams.draw(
                            new Random(tripContextBuilder.personSeed(randomSeed, person.getId(),
                                    "gammaSensitivity" + tripIndex + alternative.name())),
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
                    UtilityComponents components = utilityFunction.utilityComponents(profile, params, tripContext,
                            observedMode);
                    samples.add(new sample(alternative, components.vLos(), components.vHabit(),
                            components.vGammaConstructs()));
                }
            }
        }

        log.info("Gamma-Sensitivitaetsanalyse: Phase 1 fertig (" + samples.size() + " Alternativen-Bewertungen, "
                + skippedNoDepartureTime + " Wege ohne verlaesslichen Abfahrtszeitpunkt uebersprungen), "
                + "Phase 2 (SD-Berechnung je Alternative)...");

        writeResult(samples);

        log.info("Gamma-Sensitivitaetsanalyse: beende den Prozess (keine MATSim-Iterationen fuer einen "
                + "reinen Analyselauf noetig).");
        System.exit(0);
    }

    private void writeResult(List<sample> samples) {
        try {
            Path directory = Path.of(config.controller().getOutputDirectory());
            Files.createDirectories(directory);
            Path csvPath = directory.resolve("gamma_sensitivitaet_baseline.csv");

            StringBuilder sb = new StringBuilder();
            sb.append("mode;n;sdVLos;sdVHabit;sdVGamma;sdVLatent;losAnteil;latentAnteil\n");
            log.info(String.format(Locale.ROOT, "%-6s %8s %10s %10s %10s %10s %10s %10s",
                    "mode", "n", "sdVLos", "sdVHabit", "sdVGamma", "sdVLat", "losAnteil", "latAnteil"));

            for (alternatives mode : ANALYSIS_MODES) {
                List<Double> vLosValues = new ArrayList<>();
                List<Double> vHabitValues = new ArrayList<>();
                List<Double> vGammaValues = new ArrayList<>();
                List<Double> vLatentValues = new ArrayList<>();
                for (sample s : samples) {
                    if (s.mode() == mode) {
                        vLosValues.add(s.vLos());
                        vHabitValues.add(s.vHabit());
                        vGammaValues.add(s.vGammaConstructs());
                        vLatentValues.add(s.vLatent());
                    }
                }
                if (vLosValues.isEmpty()) {
                    log.warn("Gamma-Sensitivitaetsanalyse: keine bewerteten Wege fuer " + mode
                            + " - fehlt in der CSV.");
                    continue;
                }
                double sdLos = standardDeviation(vLosValues, mean(vLosValues));
                double sdHabit = standardDeviation(vHabitValues, mean(vHabitValues));
                double sdGamma = standardDeviation(vGammaValues, mean(vGammaValues));
                double sdLatent = standardDeviation(vLatentValues, mean(vLatentValues));
                double sdSum = sdLos + sdLatent;
                double losAnteil = sdSum > 0 ? sdLos / sdSum : Double.NaN;
                double latentAnteil = sdSum > 0 ? sdLatent / sdSum : Double.NaN;

                sb.append(mode.name()).append(';')
                        .append(vLosValues.size()).append(';')
                        .append(String.format(Locale.ROOT, "%.6f", sdLos)).append(';')
                        .append(String.format(Locale.ROOT, "%.6f", sdHabit)).append(';')
                        .append(String.format(Locale.ROOT, "%.6f", sdGamma)).append(';')
                        .append(String.format(Locale.ROOT, "%.6f", sdLatent)).append(';')
                        .append(String.format(Locale.ROOT, "%.6f", losAnteil)).append(';')
                        .append(String.format(Locale.ROOT, "%.6f", latentAnteil))
                        .append('\n');

                log.info(String.format(Locale.ROOT, "%-6s %8d %10.4f %10.4f %10.4f %10.4f %10.4f %10.4f",
                        mode.name(), vLosValues.size(), sdLos, sdHabit, sdGamma, sdLatent, losAnteil, latentAnteil));
            }

            Files.writeString(csvPath, sb.toString(), StandardCharsets.UTF_8);
            log.info("Gamma-Sensitivitaetsanalyse: Ergebnis nach " + csvPath + " geschrieben.");
        } catch (IOException e) {
            throw new UncheckedIOException("gamma_sensitivitaet_baseline.csv konnte nicht geschrieben werden.", e);
        }
    }

    private static double mean(List<Double> values) {
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    private static double standardDeviation(List<Double> values, double mean) {
        if (values.size() < 2) {
            return 0.0;
        }
        double sumSq = 0.0;
        for (double v : values) {
            double diff = v - mean;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / (values.size() - 1));
    }

    /** Identisch zu behaviourCandidateTripInserter.resolveProfile - siehe dortigen Javadoc. */
    private static agentProfile resolveProfile(Person person, String segmentAttribute, Map<String, agentProfile> segmentsById) {
        Object value = person.getAttributes().getAttribute(segmentAttribute);
        agentProfile profile = value == null ? null : segmentsById.get(value.toString());
        return profile != null ? profile : new agentProfile("__neutral__", Map.of());
    }
}
