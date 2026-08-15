package org.matsim.project.scoring;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contribs.discrete_mode_choice.components.estimators.AbstractTripRouterEstimator;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;
import org.matsim.core.router.TripRouter;
import org.matsim.core.utils.timing.TimeInterpretation;
import org.matsim.core.utils.timing.TimeTracker;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.project.config.behaviourConfigGroup;
import org.matsim.project.model.TripContext;
import org.matsim.project.model.agentProfile;
import org.matsim.project.model.alternatives;
import org.matsim.project.model.behaviourUtilityFunction;
import org.matsim.project.model.modeParams;

import com.google.inject.Inject;

/**
 * KERN DES SCHRITT-4-ANSCHLUSSES: haengt behaviourUtilityFunction als
 * discrete_mode_choice-TripEstimator("verhalten") in die Simulation ein.
 *
 * AbstractTripRouterEstimator routet den Trip fuer den zu bewertenden Modus
 * bereits selbst (TripRouter.calcRoute(...)) und ruft danach estimateTrip(...)
 * mit den gerouteten PlanElements auf - wir muessen daraus nur noch die
 * Utility berechnen, nicht selbst routen.
 *
 * BEKANNTE PLATZHALTER (siehe TripContext-Javadoc: "Diese Werte liefert im
 * spaeteren Einsatz die Simulation (Router, Fahrplan, Tarifmodell)"):
 *   - waitTimeHours ist fuer PSAV/SSAV (Schritt 8: echte DRT-Flotten) seit
 *     Schritt 9 EXPECTED_WAIT_TIME_SHARE_OF_MAX_WAIT * maxWaitDuration aus den
 *     DrtRouteConstraints des gerouteten Trips (siehe waitTimeHours(...)) -
 *     eine grobe Naeherung, KEINE dynamische Schaetzung aus dem tatsaechlichen
 *     Systemzustand: DrtRouteCreator setzt diese Constraint bereits bei jeder
 *     Routung (auch ohne DRT-Estimator-Konfiguration), sie ist aber eine
 *     GARANTIERTE OBERGRENZE (drtOptimizationConstraints.maxWaitTime aus der
 *     Config), kein Erwartungswert. Eine echte, aus beobachteten Wartezeiten
 *     abgeleitete Schaetzung braeuchte entweder DRTs eigenen DrtEstimator (der
 *     aber nur im simulationType=estimateAndTeleport-Modus greift und damit
 *     die echte Flottensimulation/Leerfahrten ersetzen wuerde, siehe
 *     DrtModeRoutingModule.install()) oder eine eigene Auswertung beobachteter
 *     Wartezeiten aus Vor-Iterationen (Kandidat fuer einen spaeteren Schritt).
 *     Fuer CA/AV/PT bleibt waitTimeHours weiterhin 0.0 - eine echte PT-Zugangs-
 *     /Wartezeit braucht den PTWaitingTimeEstimator des Contribs.
 *   - costEuro = modeParams.costPerKm * distanceKm (echte, aus dem gerouteten
 *     Trip berechnete Distanz). Kein Grundpreis/Tarifstufen - reine lineare
 *     Distanzkosten. Solange costPerKm in der Config auf 0.0 steht (aktueller
 *     Stand), ist der beta_cost-Term weiterhin wirkungslos, bis echte
 *     Euro/km-Saetze eingetragen werden.
 *   - inVehicleTimeHours ist aktuell die GESAMTE Wegzeit (inkl. evtl. Zugang/
 *     Umstieg), nicht nur reine Fahrzeugzeit - dieselbe Vereinfachung wie bei
 *     der Wartezeit. ANDERS als costEuro kommt diese Zeit aber bereits real
 *     aus dem geplanten/kongestionsabhaengigen Routing, nicht aus einer
 *     Distanz-Umrechnung.
 *
 * SCHRITT 6 - FROZEN MIXED-LOGIT-ZIEHUNGEN: agentProfile.draw(...)/modeParams.
 * draw(...) ziehen agentenindividuelle Abweichungen von den Mittelwerten aus
 * N(mean, sd). Diese Ziehung ist "frozen": der Seed wird deterministisch aus
 * Personen-ID + Modus abgeleitet (siehe personSeed(...)), nicht aus einem
 * geteilten, mutierenden Random-Strom. Dieselbe Person+Modus-Kombination
 * liefert dadurch bei jeder Auswertung - jede Iteration, jede Wahlsituation -
 * exakt dieselben gezogenen Koeffizienten, unabhaengig von Aufrufreihenfolge
 * oder Parallelisierung. Bei allen *Sd-Werten = 0 (aktueller Stand der
 * segmentParams) ist die Ziehung ein No-Op und degeneriert sauber zu MNL.
 *
 * Die eigentliche Wahlwahrscheinlichkeits-Ziehung (MultinomialLogitSelector)
 * bleibt bewusst unveraendert: sie realisiert den Gumbel-Fehler bereits
 * korrekt ueber die MNL-Formel, arbeitet aber mit einem DMC-internen,
 * personenunabhaengigen Random-Strom (UtilitySelector.select(Random) kennt die
 * Person nicht). Das dort ebenfalls einzufrieren wuerde einen Eingriff in
 * DMC-Interna erfordern und ist bewusst nicht Teil dieses Schritts.
 */
public class behaviourUtilityEstimator extends AbstractTripRouterEstimator {

    private final behaviourUtilityFunction utilityFunction;
    private final Map<alternatives, modeParams> modeParamsByAlternative;
    private final Map<String, agentProfile> segmentsById;
    private final String segmentAttribute;
    private final long randomSeed;
    private final TimeInterpretation timeInterpretation;

    @Inject
    public behaviourUtilityEstimator(TripRouter tripRouter, ActivityFacilities facilities,
            TimeInterpretation timeInterpretation, behaviourUtilityFunction utilityFunction,
            behaviourConfigGroup cfg) {
        super(tripRouter, facilities, timeInterpretation, List.of());

        this.timeInterpretation = timeInterpretation;
        this.utilityFunction = utilityFunction;
        this.modeParamsByAlternative = cfg.buildModeParams();
        this.segmentsById = cfg.buildSegments();
        this.segmentAttribute = cfg.getSegmentAttribute();
        this.randomSeed = cfg.getRandomSeed();
    }

    @Override
    protected double estimateTrip(Person person, String mode, DiscreteModeChoiceTrip trip,
            List<TripCandidate> previousTrips, List<? extends PlanElement> routedTrip) {

        alternatives alternative = alternatives.fromMatsimMode(mode);
        if (alternative == null) {
            throw new IllegalArgumentException(
                    "Kein Alternativen-Mapping fuer MATSim-Modus '" + mode + "' (siehe alternatives.java). "
                            + "availableModes in der DMC-Config darf nur Modi enthalten, die alternatives kennt.");
        }

        modeParams meanParams = modeParamsByAlternative.get(alternative);
        if (meanParams == null) {
            throw new IllegalArgumentException("Keine modeParams fuer Alternative " + alternative
                    + " konfiguriert (Block <parameterset type=\"modeParams\"> im Modul '"
                    + behaviourConfigGroup.GROUP_NAME + "').");
        }

        // Frozen Mixed-Logit-Ziehung, siehe Klassen-Javadoc. profile wird NICHT nach
        // Modus geseedet (eine Person hat nur EIN Set latenter Konstrukte), params
        // dagegen schon (Koeffizienten-Abweichungen duerfen je Modus unabhaengig sein).
        agentProfile profile = resolveProfile(person).draw(new Random(personSeed(person, "profile")));
        modeParams params = meanParams.draw(new Random(personSeed(person, alternative.name())));

        TripContext tripContext = buildTripContext(trip, routedTrip, params.getCostPerKm());
        alternatives previousMode = alternatives.fromMatsimMode(trip.getInitialMode());

        return utilityFunction.utility(profile, params, tripContext, previousMode);
    }

    private long personSeed(Person person, String salt) {
        return Objects.hash(randomSeed, person.getId().toString(), salt);
    }

    /**
     * Segment-Aufloesung ueber das in der Config benannte Personen-Attribut.
     * Fehlt das Attribut oder ist das Segment unbekannt, wird ein neutrales
     * Profil (alle Konstrukte = 0.0) verwendet - agentProfile.get(...) definiert
     * das ohnehin als Verhalten fuer nicht gesetzte Konstrukte, siehe dortigen
     * Javadoc ("wirken also im z-standardisierten Raum wie ein durchschnittlicher
     * Agent").
     */
    private agentProfile resolveProfile(Person person) {
        Object value = person.getAttributes().getAttribute(segmentAttribute);
        agentProfile profile = value == null ? null : segmentsById.get(value.toString());
        return profile != null ? profile : new agentProfile("__neutral__", Map.of());
    }

    /**
     * Anteil von drtOptimizationConstraints.maxWaitTime, der als erwartete
     * Wartezeit fuer PSAV/SSAV angenommen wird (siehe waitTimeHours(...) und
     * Klassen-Javadoc: maxWaitTime ist eine garantierte Obergrenze, kein
     * Erwartungswert). PLATZHALTER, noch nicht aus beobachteten Wartezeiten
     * kalibriert.
     */
    static final double EXPECTED_WAIT_TIME_SHARE_OF_MAX_WAIT = 0.5;

    private TripContext buildTripContext(DiscreteModeChoiceTrip trip, List<? extends PlanElement> routedTrip,
            double costPerKm) {

        TimeTracker timeTracker = new TimeTracker(timeInterpretation);
        timeTracker.setTime(trip.getDepartureTime());
        timeTracker.addElements(routedTrip);
        double totalTravelTimeHours = (timeTracker.getTime().seconds() - trip.getDepartureTime()) / 3600.0;

        double distanceKm = 0.0;
        for (PlanElement element : routedTrip) {
            if (element instanceof Leg leg && leg.getRoute() != null) {
                distanceKm += leg.getRoute().getDistance() / 1000.0;
            }
        }

        double costEuro = costPerKm * distanceKm;

        return new TripContext(totalTravelTimeHours, waitTimeHours(routedTrip), costEuro, distanceKm);
    }

    /**
     * Erwartete Wartezeit fuer DRT-Modi (PSAV/SSAV), siehe Klassen-Javadoc und
     * EXPECTED_WAIT_TIME_SHARE_OF_MAX_WAIT. Fuer alle anderen Modi (CA/AV/PT,
     * keine DrtRoute) weiterhin 0.0 - unveraendertes Verhalten.
     */
    private double waitTimeHours(List<? extends PlanElement> routedTrip) {
        double waitTimeSeconds = 0.0;
        for (PlanElement element : routedTrip) {
            if (element instanceof Leg leg && leg.getRoute() instanceof DrtRoute drtRoute) {
                waitTimeSeconds += EXPECTED_WAIT_TIME_SHARE_OF_MAX_WAIT * drtRoute.getConstraints().maxWaitDuration();
            }
        }
        return waitTimeSeconds / 3600.0;
    }
}