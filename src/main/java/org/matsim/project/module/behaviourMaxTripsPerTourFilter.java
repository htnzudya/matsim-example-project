package org.matsim.project.module;

import java.util.List;

import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourFilter;
import org.matsim.project.config.behaviourConfigGroup;

import com.google.inject.Inject;

/**
 * Begrenzt die vom TourBasedModel exhaustiv durchprobierte Modus-Kombinatorik
 * (Auftraggeber-Feedback nach einem echten OutOfMemoryError, siehe
 * behaviourBaselineAscCalibrator-Kontext): DMCs Standard-TourBasedModel
 * probiert fuer eine Tour mit k Wegen ALLE ModusAnzahl^k Kombinationen durch
 * (DefaultModeChainGenerator-Javadoc) - bei den urspruenglich 5 Alternativen
 * war das fuer die laengsten real vorkommenden Touren (bis zu 9 Wege) schon
 * grenzwertig (5^9 ≈ 2 Mio.), seit BIKE/WALK/RIDE als echte DCM-Alternativen
 * dazugekommen sind (8 statt 5 Alternativen, siehe alternatives-Klassen-
 * Javadoc) UND alle Touren (nicht mehr nur die ohne BIKE/WALK/RIDE) der
 * Umplanung unterliegen, ist 8^9 ≈ 134 Mio. - beobachtet als
 * OutOfMemoryError bereits nach wenigen verarbeiteten Plaenen.
 *
 * Touren mit MEHR als maxWegeProTourFuerDcm Wegen bleiben deshalb komplett
 * ausserhalb der Umplanung (unveraendert wie erhoben) - betrifft nur einen
 * kleinen Anteil der Population (in der 1pct-Population z. B. ~6% der
 * Touren bei einer Grenze von 5 Wegen), eliminiert aber den weit
 * ueberwiegenden Teil der Kombinatorik (8^6 ≈ 262 Tsd. statt 8^9 ≈ 134 Mio.
 * fuer die laengsten Touren).
 *
 * Als zusaetzlicher TourFilter neben behaviourNonDcmModeTourFilter eingebunden
 * (siehe behaviourModule.configureController, dmcConfig.setTourFilters) -
 * DMCs CompositeTourFilter kombiniert mehrere benannte Filter per UND (eine
 * Tour muss ALLE Filter passieren, um zur Umplanung zugelassen zu werden).
 */
public final class behaviourMaxTripsPerTourFilter implements TourFilter {

    private final int maxTripsPerTour;

    @Inject
    public behaviourMaxTripsPerTourFilter(behaviourConfigGroup cfg) {
        this.maxTripsPerTour = cfg.getMaxWegeProTourFuerDcm();
    }

    @Override
    public boolean filter(Person person, List<DiscreteModeChoiceTrip> trips) {
        return trips.size() <= maxTripsPerTour;
    }
}
