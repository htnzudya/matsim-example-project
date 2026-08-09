package org.matsim.project.module;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.mode_availability.DefaultModeAvailability;
import org.matsim.core.population.PersonUtils;
import org.matsim.project.model.alternatives;

/**
 * Fuehrerschein-/Fahrzeugzugangs-Constraint fuer CA und AV (Schritt 7):
 *
 *   CA (konventioneller Pkw)      - braucht Fuehrerschein UND Fahrzeugzugang
 *                                    (identisch zu DMCs eingebauter CarModeAvailability)
 *   AV (privates automatisiertes
 *       Fahrzeug)                 - braucht NUR Fahrzeugzugang, KEINEN Fuehrerschein
 *                                    (das Fahrzeug faehrt selbst)
 *   PT, PSAV, SSAV                 - fuer alle Personen verfuegbar, keine Einschraenkung
 *
 * Fahrzeugzugang = PersonUtils.getCarAvail(person) != "never".
 * Fuehrerschein  = PersonUtils.getLicense(person) != "no".
 */
public final class behaviourModeAvailability extends DefaultModeAvailability {

    public behaviourModeAvailability(Collection<String> modes) {
        super(modes);
    }

    @Override
    public Collection<String> getAvailableModes(Person person, List<DiscreteModeChoiceTrip> trips) {
        boolean hasVehicleAccess = !"never".equals(PersonUtils.getCarAvail(person));
        boolean hasLicense = !"no".equals(PersonUtils.getLicense(person));

        return super.getAvailableModes(person, trips).stream()
                .filter(mode -> !(alternatives.CA.getMatsimMode().equals(mode) && !(hasVehicleAccess && hasLicense)))
                .filter(mode -> !(alternatives.AV.getMatsimMode().equals(mode) && !hasVehicleAccess))
                .collect(Collectors.toSet());
    }
}