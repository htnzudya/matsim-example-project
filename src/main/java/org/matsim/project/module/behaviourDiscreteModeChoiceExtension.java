package org.matsim.project.module;

import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Collectors;

import org.matsim.contribs.discrete_mode_choice.modules.AbstractDiscreteModeChoiceExtension;
import org.matsim.project.model.alternatives;
import org.matsim.project.scoring.behaviourCachedTripEstimator;
import org.matsim.project.scoring.behaviourHabitBaselineRegistry;
import org.matsim.project.scoring.behaviourUtilityEstimator;

import com.google.inject.Provides;
import com.google.inject.Singleton;

/**
 * Registriert die Add-on-eigenen DMC-Komponenten unter dem Namen "verhalten":
 *  - behaviourCachedTripEstimator als TripEstimator (umhuellt behaviourUtilityEstimator
 *    mit einer threadsicheren Cache-Schicht, siehe dortigen Klassen-Javadoc - ersetzt
 *    das entfernte DiscreteModeChoiceConfigGroup.setCachedModes(...)). BEWUSST OHNE
 *    .in(Singleton.class) gebunden - genau wie DMCs eigenes EstimatorModule.
 *    provideTripEstimator(...) seinen CachedTripEstimator undekoriert bindet: bei
 *    jeder Injektion entsteht eine neue Instanz mit leerem Cache. Mit Singleton-Scope
 *    wuerde der Cache ueber den GESAMTEN Lauf (alle Iterationen, alle Threads) nie
 *    geleert - beobachtet als OutOfMemoryError bereits in Iteration 1, da Millionen
 *    gerouteter TripCandidate-Objekte auf dem grossen Strassen-/OEPNV-Netz nie wieder
 *    freigegeben wurden.
 *  - behaviourModeAvailability als ModeAvailability (Fuehrerschein-/Fahrzeug-
 *    zugangs-Constraint fuer CA/AV, siehe dortigen Javadoc)
 *  - behaviourNonDcmModeTourFilter als TourFilter (schliesst BIKE/WALK/RIDE-
 *    Touren von der Umplanung aus, siehe dortigen Klassen-Javadoc)
 *  - behaviourHabitBaselineRegistry (siehe dortigen Klassen-Javadoc) BEWUSST
 *    ALS SINGLETON gebunden - anders als behaviourCachedTripEstimator/
 *    behaviourUtilityEstimator oben: hier soll der Zustand GENAU ueber den
 *    gesamten Lauf hinweg erhalten bleiben (Ausgangslage-Referenz fuer den
 *    Habit-Term), nicht pro Injektion neu entstehen.
 * Wird von behaviourModule.install() eingebunden.
 */
public final class behaviourDiscreteModeChoiceExtension extends AbstractDiscreteModeChoiceExtension {

    public static final String TRIP_ESTIMATOR_NAME = "verhalten";
    public static final String MODE_AVAILABILITY_NAME = "verhalten";
    public static final String TOUR_FILTER_NAME = "verhalten";

    @Override
    protected void installExtension() {
        bind(behaviourHabitBaselineRegistry.class).in(Singleton.class);
        bind(behaviourUtilityEstimator.class);
        bindTripEstimator(TRIP_ESTIMATOR_NAME).to(behaviourCachedTripEstimator.class);
        bindModeAvailability(MODE_AVAILABILITY_NAME).to(behaviourModeAvailability.class);
        bindTourFilter(TOUR_FILTER_NAME).to(behaviourNonDcmModeTourFilter.class);
    }

    /**
     * Die vier Alternativen des Addons (siehe alternatives.java) sind die
     * Grundmenge der verfuegbaren Modi - das ist die feste Identitaet des
     * Add-ons, nicht szenarienabhaengig konfigurierbar. behaviourModeAvailability
     * filtert daraus je Person CA/AV nach Fuehrerschein/Fahrzeugzugang heraus.
     */
    @Provides
    @Singleton
    public behaviourModeAvailability provideModeAvailability() {
        Collection<String> modes = Arrays.stream(alternatives.values())
                .map(alternatives::getMatsimMode)
                .collect(Collectors.toSet());
        return new behaviourModeAvailability(modes);
    }
}