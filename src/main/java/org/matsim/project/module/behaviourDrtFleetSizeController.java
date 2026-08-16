package org.matsim.project.module;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.dvrp.analysis.VehicleOccupancyProfileCalculator;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.DvrpVehicleSpecification;
import org.matsim.contrib.dvrp.fleet.FleetSpecification;
import org.matsim.contrib.dvrp.fleet.ImmutableDvrpVehicleSpecification;
import org.matsim.contrib.dvrp.run.DvrpMode;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.project.config.behaviourConfigGroup;
import org.matsim.project.model.alternatives;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.google.inject.Inject;

/**
 * Schritt 11: passt die PSAV-/SSAV-Flottengroesse ZWISCHEN Iterationen
 * dynamisch an die beobachtete Fahrzeugauslastung an, statt sie starr auf
 * PSAV_FLEET_SIZE/SSAV_FLEET_SIZE (siehe RunOberlausitzDresdenTest, die nur
 * noch die STARTgroesse fuer Iteration 0 vorgeben) zu belassen.
 *
 * Signal: durchschnittliche Auslastung = befoerderte "Personen-Zeit" / (im
 * Dienst befindliche Fahrzeuge * Kapazitaet * Zeit), aus
 * VehicleOccupancyProfileCalculator - eine DVRP-eigene Analysekomponente, die
 * IMMER als Event-Handler laeuft (unabhaengig von writeDetailedCustomerStats
 * in der Config, das nur das Schreiben der Ergebnisdateien steuert, nicht die
 * Datenerfassung selbst - siehe DrtModeAnalysisModule.install()).
 *
 * Politik (alle Werte PLATZHALTER, nicht aus Betriebsdaten kalibriert):
 *   Auslastung &gt; TARGET_UTILIZATION_HIGH -&gt; Flotte waechst um GROWTH_STEP_SHARE
 *   Auslastung &lt; TARGET_UTILIZATION_LOW  -&gt; Flotte schrumpft um GROWTH_STEP_SHARE
 *   sonst: unveraendert
 *
 * Weiche Obergrenze (Sicherheitsnetz gegen unbegrenztes Wachstum - das reale
 * Risiko dabei ist Speicher, nicht Rechenzeit: DRTs Fahrzeit-Matrizen/
 * QSim-Strukturen skalieren mit der Flottengroesse, und wir haben in diesem
 * Projekt schon mehrfach OOM auf dem 441k-Link-Netz gehabt): maximal
 * MAX_FLEET_SIZE_FACTOR * die bei Iteration 0 beobachtete Startgroesse.
 * Untergrenze MIN_FLEET_SIZE, damit die Flotte nie ganz verschwindet.
 *
 * Neue Fahrzeuge bekommen ihre Depotposition wie in behaviourDrtFleetGenerator
 * zufaellig aus echten, fuer den Modus freigegebenen Netzlinks - deterministisch
 * ueber denselben randomSeed (behaviourConfigGroup), zusaetzlich mit der
 * Iterationsnummer gesalzen, damit nicht jede Iteration exakt dieselben Links
 * zieht.
 */
public final class behaviourDrtFleetSizeController implements IterationEndsListener {

    private static final Logger log = LogManager.getLogger(behaviourDrtFleetSizeController.class);

    static final double TARGET_UTILIZATION_LOW = 0.3;
    static final double TARGET_UTILIZATION_HIGH = 0.6;
    static final double GROWTH_STEP_SHARE = 0.1;
    static final double MAX_FLEET_SIZE_FACTOR = 3.0;
    static final int MIN_FLEET_SIZE = 10;

    private final Network network;
    private final long randomSeed;

    private final ModeState psav;
    private final ModeState ssav;

    @Inject
    public behaviourDrtFleetSizeController(Network network, behaviourConfigGroup cfg,
            @DvrpMode("psav") FleetSpecification psavFleet,
            @DvrpMode("psav") VehicleOccupancyProfileCalculator psavOccupancy,
            @DvrpMode("ssav") FleetSpecification ssavFleet,
            @DvrpMode("ssav") VehicleOccupancyProfileCalculator ssavOccupancy) {
        this.network = network;
        this.randomSeed = cfg.getRandomSeed();
        this.psav = new ModeState(alternatives.PSAV.getMatsimMode(), psavFleet, psavOccupancy);
        this.ssav = new ModeState(alternatives.SSAV.getMatsimMode(), ssavFleet, ssavOccupancy);
    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
        resize(psav, event.getIteration());
        resize(ssav, event.getIteration());
    }

    private void resize(ModeState state, int iteration) {
        int currentSize = state.fleet.getVehicleSpecifications().size();
        if (currentSize == 0) {
            return;
        }
        if (state.initialFleetSize < 0) {
            state.initialFleetSize = currentSize;
        }

        double utilization = computeUtilization(state);
        int maxFleetSize = Math.max(MIN_FLEET_SIZE, (int) Math.round(state.initialFleetSize * MAX_FLEET_SIZE_FACTOR));

        int targetSize = currentSize;
        if (utilization > TARGET_UTILIZATION_HIGH) {
            targetSize = currentSize + Math.max(1, (int) Math.round(currentSize * GROWTH_STEP_SHARE));
        } else if (utilization < TARGET_UTILIZATION_LOW) {
            targetSize = currentSize - Math.max(1, (int) Math.round(currentSize * GROWTH_STEP_SHARE));
        }
        targetSize = Math.min(maxFleetSize, Math.max(MIN_FLEET_SIZE, targetSize));

        if (targetSize > currentSize) {
            addVehicles(state, targetSize - currentSize, iteration);
        } else if (targetSize < currentSize) {
            removeVehicles(state, currentSize - targetSize);
        }

        log.info(String.format("%s: Auslastung=%.2f, Flotte %d -> %d (max %d, Iteration %d)",
                state.mode, utilization, currentSize, targetSize, maxFleetSize, iteration));
    }

    /**
     * Grober Auslastungsindikator in [0,1]: befoerderte "Personen-Zeit" ueber
     * alle Auslastungsstufen und Zeitfenster, geteilt durch die theoretisch
     * verfuegbare Kapazitaets-Zeit aller im Dienst befindlichen Fahrzeuge.
     */
    private double computeUtilization(ModeState state) {
        List<double[]> occupancyProfiles = state.occupancy.getVehicleOccupancyProfiles();
        double[] vehiclesInService = state.occupancy.getNumberOfVehiclesInServiceProfile();
        if (occupancyProfiles.isEmpty() || vehiclesInService.length == 0) {
            return 0.0;
        }

        int capacity = vehicleCapacity(state);
        if (capacity <= 0) {
            return 0.0;
        }

        double passengerBinSum = 0.0;
        for (int level = 0; level < occupancyProfiles.size(); level++) {
            double[] profile = occupancyProfiles.get(level);
            for (double vehiclesAtLevel : profile) {
                passengerBinSum += level * vehiclesAtLevel;
            }
        }

        double capacityBinSum = 0.0;
        for (double v : vehiclesInService) {
            capacityBinSum += v * capacity;
        }

        return capacityBinSum > 0.0 ? passengerBinSum / capacityBinSum : 0.0;
    }

    private int vehicleCapacity(ModeState state) {
        DvrpVehicleSpecification anyVehicle = state.fleet.getVehicleSpecifications().values().iterator().next();
        return anyVehicle.getCapacity().getElement(0).intValue();
    }

    private void addVehicles(ModeState state, int count, int iteration) {
        List<Id<Link>> candidateLinks = new ArrayList<>();
        for (Link link : network.getLinks().values()) {
            if (link.getAllowedModes().contains(state.mode)) {
                candidateLinks.add(link.getId());
            }
        }
        if (candidateLinks.isEmpty()) {
            log.warn(state.mode + ": kein Netzlink erlaubt diesen Modus, kann Flotte nicht vergroessern.");
            return;
        }

        Random random = new Random(randomSeed + iteration + 31L * state.mode.hashCode());
        int capacity = vehicleCapacity(state);

        for (int i = 0; i < count; i++) {
            Id<Link> startLink = candidateLinks.get(random.nextInt(candidateLinks.size()));
            DvrpVehicleSpecification spec = ImmutableDvrpVehicleSpecification.newBuilder()
                    .id(Id.create(state.mode + "_veh_dyn_" + iteration + "_" + i, DvrpVehicle.class))
                    .startLinkId(startLink)
                    .capacity(capacity)
                    .serviceBeginTime(0.0)
                    .serviceEndTime(30 * 3600.0)
                    .build();
            state.fleet.addVehicleSpecification(spec);
        }
    }

    private void removeVehicles(ModeState state, int count) {
        List<Id<DvrpVehicle>> ids = new ArrayList<>(state.fleet.getVehicleSpecifications().keySet());
        for (int i = 0; i < count && i < ids.size(); i++) {
            state.fleet.removeVehicleSpecification(ids.get(i));
        }
    }

    private static final class ModeState {
        final String mode;
        final FleetSpecification fleet;
        final VehicleOccupancyProfileCalculator occupancy;
        int initialFleetSize = -1;

        ModeState(String mode, FleetSpecification fleet, VehicleOccupancyProfileCalculator occupancy) {
            this.mode = mode;
            this.fleet = fleet;
            this.occupancy = occupancy;
        }
    }
}
