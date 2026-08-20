package org.matsim.project.module;

import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Collectors;

import org.matsim.contribs.discrete_mode_choice.modules.AbstractDiscreteModeChoiceExtension;
import org.matsim.project.config.behaviourConfigGroup;
import org.matsim.project.model.alternatives;
import org.matsim.project.scoring.behaviourUtilityEstimator;

import com.google.inject.Provides;
import com.google.inject.Singleton;

/**
 * Registriert die Add-on-eigenen DMC-Komponenten unter dem Namen "verhalten":
 *  - behaviourUtilityEstimator als TripEstimator
 *  - behaviourModeAvailability als ModeAvailability (Fuehrerschein-/Fahrzeug-
 *    zugangs-Constraint fuer CA/AV, siehe dortigen Javadoc)
 * Wird von behaviourModule.install() eingebunden.
 */
public final class behaviourDiscreteModeChoiceExtension extends AbstractDiscreteModeChoiceExtension {

    public static final String TRIP_ESTIMATOR_NAME = "verhalten";
    public static final String MODE_AVAILABILITY_NAME = "verhalten";

    @Override
    protected void installExtension() {
        bindTripEstimator(TRIP_ESTIMATOR_NAME).to(behaviourUtilityEstimator.class);
        bindModeAvailability(MODE_AVAILABILITY_NAME).to(behaviourModeAvailability.class);
    }

    /**
     * Die fuenf Alternativen des Addons (siehe alternatives.java) sind die
     * Grundmenge der verfuegbaren Modi, eingeschraenkt auf CA/PT, wenn
     * verhaltensmodell.avmModesEnabled=false gesetzt ist (Basislauf des
     * Basis-vs-AVM-Vergleichs, siehe behaviourConfigGroup.avmModesEnabled-
     * Javadoc). behaviourModeAvailability filtert daraus zusaetzlich je Person
     * CA/AV nach Fuehrerschein/Fahrzeugzugang heraus.
     */
    @Provides
    @Singleton
    public behaviourModeAvailability provideModeAvailability(behaviourConfigGroup cfg) {
        boolean avmModesEnabled = cfg.getAvmModesEnabled();
        Collection<String> modes = Arrays.stream(alternatives.values())
                .filter(alternative -> avmModesEnabled
                        || alternative == alternatives.CA || alternative == alternatives.PT)
                .map(alternatives::getMatsimMode)
                .collect(Collectors.toSet());
        return new behaviourModeAvailability(modes);
    }
}