package org.matsim.project.scoring;

import java.util.List;
import java.util.Objects;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.core.utils.timing.TimeTracker;
import org.matsim.pt.routes.TransitPassengerRoute;
import org.matsim.project.model.TripContext;

/**
 * Gemeinsame LOS-Extraktion aus einem gerouteten Trip (Liste von PlanElements).
 *
 * Ausgelagert aus {@link behaviourUtilityEstimator}, damit exakt dieselbe
 * Berechnung von zwei Aufrufstellen genutzt wird: dem laufenden DMC-TripEstimator
 * ("verhalten", bewertet Wege im bestehenden Wegeprogramm) UND
 * behaviourCandidateTripInserter (Nullalternative, bewertet den vor Iteration 0
 * konstruierten Kandidatenweg). Beide muessen nach identischen Regeln rechnen -
 * siehe dortige Javadocs zu den PLATZHALTER-Annahmen (PT-/DRT-Wartezeit,
 * lineares Distanzkosten-Modell).
 */
public final class tripContextBuilder {

    private tripContextBuilder() {
    }

    /**
     * Anteil von drtOptimizationConstraints.maxWaitTime, der als erwartete
     * Wartezeit fuer PSAV/SSAV angenommen wird. PLATZHALTER, noch nicht aus
     * beobachteten Wartezeiten kalibriert - siehe behaviourUtilityEstimator-
     * Klassen-Javadoc.
     */
    public static final double EXPECTED_WAIT_TIME_SHARE_OF_MAX_WAIT = 0.5;

    /**
     * Angenommene durchschnittliche Wartezeit je PT-Einstieg. PLATZHALTER,
     * siehe behaviourUtilityEstimator-Klassen-Javadoc/ptWaitTimeHours(...) fuer
     * die Begruendung.
     */
    public static final double ASSUMED_PT_WAIT_TIME_PER_BOARDING_SECONDS = 300.0;

    /** Deterministischer, personen-/salt-gebundener Seed - siehe behaviourUtilityEstimator-Klassen-Javadoc "FROZEN". */
    public static long personSeed(long randomSeed, Id<?> personId, String salt) {
        return Objects.hash(randomSeed, personId.toString(), salt);
    }

    public static TripContext buildTripContext(TimeInterpretation timeInterpretation, double departureTime,
            List<? extends PlanElement> routedTrip, double costPerKm) {

        TimeTracker timeTracker = new TimeTracker(timeInterpretation);
        timeTracker.setTime(departureTime);
        timeTracker.addElements(routedTrip);
        double totalTravelTimeHours = (timeTracker.getTime().seconds() - departureTime) / 3600.0;

        double ptWaitTimeHours = ptWaitTimeHours(routedTrip);
        double inVehicleTimeHours = totalTravelTimeHours - ptWaitTimeHours;

        double distanceKm = 0.0;
        for (PlanElement element : routedTrip) {
            if (element instanceof Leg leg && leg.getRoute() != null) {
                distanceKm += leg.getRoute().getDistance() / 1000.0;
            }
        }

        double costEuro = costPerKm * distanceKm;
        double waitTimeHours = ptWaitTimeHours + drtWaitTimeHours(routedTrip);

        return new TripContext(inVehicleTimeHours, waitTimeHours, costEuro, distanceKm);
    }

    /** Siehe behaviourUtilityEstimator-Klassen-Javadoc fuer die Begruendung dieser Annahme. */
    public static double ptWaitTimeHours(List<? extends PlanElement> routedTrip) {
        double waitTimeSeconds = 0.0;
        for (PlanElement element : routedTrip) {
            if (element instanceof Leg leg && leg.getRoute() instanceof TransitPassengerRoute
                    && leg.getTravelTime().isDefined()) {
                waitTimeSeconds += Math.min(ASSUMED_PT_WAIT_TIME_PER_BOARDING_SECONDS, leg.getTravelTime().seconds());
            }
        }
        return waitTimeSeconds / 3600.0;
    }

    /** Siehe behaviourUtilityEstimator-Klassen-Javadoc fuer die Begruendung dieser Annahme. */
    public static double drtWaitTimeHours(List<? extends PlanElement> routedTrip) {
        double waitTimeSeconds = 0.0;
        for (PlanElement element : routedTrip) {
            if (element instanceof Leg leg && leg.getRoute() instanceof DrtRoute drtRoute) {
                waitTimeSeconds += EXPECTED_WAIT_TIME_SHARE_OF_MAX_WAIT * drtRoute.getConstraints().maxWaitDuration();
            }
        }
        return waitTimeSeconds / 3600.0;
    }
}
