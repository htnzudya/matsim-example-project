package org.matsim.project.module;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourFilter;
import org.matsim.project.model.alternatives;

/**
 * DiscreteModeChoice (DMC) wandelt bei jeder Ziehung IMMER in einen der fuenf
 * DCM-Alternativen (CA/AV/PT/PSAV/SSAV, siehe alternatives.java) um -
 * unabhaengig vom bisherigen Modus der Tour. Fuer Touren, deren erhobener
 * Modus BIKE/WALK/RIDE ist, kann das aber niemals in die Gegenrichtung
 * passieren (diese drei Modi sind keine DCM-Alternativen) - das ist eine
 * EINSEITIGE, rein mechanische Ausblutung Richtung 0%, kein
 * Verhaltensgleichgewicht: mit genug Iterationen wird JEDE BIKE/WALK/RIDE-
 * Tour irgendwann zufaellig fuer DiscreteModeChoice gezogen (20 %/Iteration)
 * und dabei umverteilt, aber nie faellt eine Tour in die andere Richtung
 * zurueck. Der resultierende BIKE/WALK/RIDE-Anteil bei Iteration N ist damit
 * ein Artefakt der Iterationszahl, kein Modellbefund.
 *
 * Deshalb bewusste Design-Entscheidung: Touren, die schon in der Erhebung
 * BIKE/WALK/RIDE nutzen, bleiben komplett ausserhalb des DCM (unveraendert
 * wie erhoben) - das DCM modelliert nur Entscheidungen INNERHALB der fuenf
 * Alternativen, fuer die es tatsaechlich eine Nutzenfunktion hat. Eine Tour
 * gilt als ausgeschlossen, sobald AUCH NUR EIN Weg darin BIKE/WALK/RIDE nutzt
 * (konservativ: eine gemischte Tour wird nicht teilweise umgeplant).
 *
 * Als DMC-TourFilter eingebunden (siehe behaviourDiscreteModeChoiceExtension,
 * DiscreteModeChoiceConfigGroup.setTourFilters in behaviourModule) statt in
 * behaviourModeAvailability: ModeAvailability entscheidet ueber das Choice-Set
 * EINER bereits zur Umplanung ausgewaehlten Tour, TourFilter entscheidet
 * bereits VORHER, ob eine Tour ueberhaupt zur Umplanung ausgewaehlt wird -
 * genau das brauchen wir hier (Tour bleibt sonst trotzdem ungeplant, aber mit
 * einer sinnlos leeren Verfuegbarkeitspruefung).
 */
public final class behaviourNonDcmModeTourFilter implements TourFilter {

    /** Der Nullalternative-Mechanismus (behaviourCandidateTripInserter) legt neue Legs immer mit einem DCM-Modus an - betrifft nur die erhobenen Touren. */
    private static final Set<String> DCM_MODES = Arrays.stream(alternatives.values())
            .map(alternatives::getMatsimMode)
            .collect(Collectors.toCollection(HashSet::new));

    @Override
    public boolean filter(Person person, List<DiscreteModeChoiceTrip> trips) {
        for (DiscreteModeChoiceTrip trip : trips) {
            if (!DCM_MODES.contains(trip.getInitialMode())) {
                return false;
            }
        }
        return true;
    }
}
