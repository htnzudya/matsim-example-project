package org.matsim.project.scoring;

import java.util.List;
import java.util.Map;
import java.util.Random;

import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.components.estimators.AbstractTripRouterEstimator;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;
import org.matsim.core.router.TripRouter;
import org.matsim.core.utils.timing.TimeInterpretation;
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
 * spaeteren Einsatz die Simulation (Router, Fahrplan, Tarifmodell)"; die
 * eigentliche Extraktion lebt seit der Nullalternative-Erweiterung in
 * tripContextBuilder, gemeinsam genutzt von diesem TripEstimator UND
 * behaviourCandidateTripInserter):
 *   - waitTimeHours setzt sich seit Schritt 10 aus zwei UNABHAENGIGEN, sich
 *     gegenseitig ausschliessenden Quellen zusammen (ein gerouteter Trip hat
 *     nie beide, da discrete_mode_choice pro Alternative separat routet):
 *
 *     PT: ASSUMED_PT_WAIT_TIME_PER_BOARDING_SECONDS je TransitPassengerRoute-
 *     Leg (siehe tripContextBuilder.ptWaitTimeHours(...) fuer die Begruendung:
 *     SwissRailRaptor baeckt die reale Wartezeit bereits IN leg.getTravelTime()
 *     der PT-Leg ein, eine echte Trennung braeuchte direkten SwissRailRaptor-
 *     Zugriff statt des generischen TripRouter-Pfads - bewusst nicht Teil
 *     dieses Schritts). Wird von inVehicleTimeHours ABGEZOGEN (siehe
 *     tripContextBuilder.buildTripContext), sonst wuerde dieselbe Zeitspanne
 *     doppelt gezaehlt (einmal ueber betaInVehicleTime, einmal ueber
 *     betaWaitTime).
 *
 *     PSAV/SSAV (Schritt 8: echte DRT-Flotten): weiterhin
 *     EXPECTED_WAIT_TIME_SHARE_OF_MAX_WAIT * maxWaitDuration aus den
 *     DrtRouteConstraints (siehe tripContextBuilder.drtWaitTimeHours(...)) -
 *     eine grobe Naeherung (garantierte Obergrenze aus der Config, kein
 *     Erwartungswert), KEINE dynamische Schaetzung aus dem tatsaechlichen
 *     Systemzustand. Eine solche braeuchte entweder DRTs eigenen
 *     DrtEstimator (der aber nur im simulationType=estimateAndTeleport-Modus
 *     greift und damit die echte Flottensimulation/Leerfahrten ersetzen
 *     wuerde, siehe DrtModeRoutingModule.install()) oder eine eigene
 *     Auswertung beobachteter Wartezeiten aus Vor-Iterationen (Kandidat fuer
 *     einen spaeteren Schritt).
 *
 *     CA/AV bleiben bei 0.0 - AV hat (anders als PT/DRT) keine Quelle fuer
 *     eine geplante/erwartete Wartezeit (kein Fahrplan, keine Flotten-
 *     Constraints), obwohl betaWaitTime fuer AV aus der SLR befuellt ist.
 *   - costEuro = modeParams.costPerKm * distanceKm (echte, aus dem gerouteten
 *     Trip berechnete Distanz). Kein Grundpreis/Tarifstufen - reine lineare
 *     Distanzkosten.
 *   - inVehicleTimeHours ist die GESAMTE Wegzeit (inkl. Zu-/Abgang, Umstiege)
 *     MINUS die oben beschriebene, separat erfasste Wartezeit - kommt real
 *     aus dem geplanten/kongestionsabhaengigen bzw. schedule-basierten
 *     Routing, nicht aus einer Distanz-Umrechnung.
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
    private final behaviourConfigGroup cfg;

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
        this.cfg = cfg;
    }

    @Override
    protected double estimateTrip(Person person, String mode, DiscreteModeChoiceTrip trip,
            List<TripCandidate> previousTrips, List<? extends PlanElement> routedTrip) {

        alternatives alternative = alternatives.fromMatsimMode(mode);
        if (alternative == null) {
            // Kommt NICHT aus der eigentlichen Moduswahl - behaviourModeAvailability
            // bietet ausschliesslich die fuenf DCM-Alternativen als Kandidaten an (siehe
            // dortigen Javadoc), ModeChainGenerator kann diese Methode also nie mit einem
            // echten Wahl-Kandidaten in einem anderen Modus aufrufen. Stattdessen: DMCs
            // TourBasedModel.chooseModes(...) ruft fuer JEDE Tour, die
            // behaviourNonDcmModeTourFilter ausschliesst (BIKE/WALK/RIDE, siehe dortigen
            // Javadoc), trotzdem TourBasedModel.createFallbackCandidate(...) auf, um einen
            // bewerteten "unveraendert lassen"-Kandidaten fuer den Tagesplan zu haben -
            // und die ruft denselben TourEstimator/TripEstimator mit dem URSPRUENGLICHEN
            // Modus (hier: "bike"/"walk"/"ride") auf. Dieser Kandidat beeinflusst NIE die
            // eigentliche Wahl (die gefilterte Tour bleibt so oder so unveraendert) - der
            // Nutzenwert wird nur fuer DMCs internen Tagesplan-Zusammenbau gebraucht, daher
            // hier ein neutraler Platzhalter (0.0) statt eines Abbruchs.
            return 0.0;
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
        modeParams params = meanParams.draw(
                new Random(personSeed(person, alternative.name())), cfg.resolveIncomeTier(person));

        // Abo-/Zeitkarten-Inhaber (siehe modeParams.costPerKmWithTicket-Javadoc):
        // effektive Grenzkosten statt des vollen Distanztarifs, falls fuer diesen
        // Modus ein Override konfiguriert ist.
        double costPerKm = params.effectiveCostPerKm(cfg.hasTicket(person));
        TripContext tripContext = tripContextBuilder.buildTripContext(
                timeInterpretation, trip.getDepartureTime(), routedTrip, costPerKm);
        alternatives previousMode = alternatives.fromMatsimMode(trip.getInitialMode());

        return utilityFunction.utility(profile, params, tripContext, previousMode);
    }

    private long personSeed(Person person, String salt) {
        return tripContextBuilder.personSeed(randomSeed, person.getId(), salt);
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

}