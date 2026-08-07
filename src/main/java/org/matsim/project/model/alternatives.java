package org.matsim.project.model;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Die vier Modusalternativen des Wahlmodells.
 *
 * CA  = Conventional Automobile (konventioneller Pkw)
 * AV  = Automated Vehicle (privates automatisiertes Fahrzeug)
 * PT  = Public Transport (OEPNV)
 * SAV = Shared Automated Vehicle (geteiltes automatisiertes Fahrzeug)
 *
 * Jede Alternative traegt den MATSim-Modusstring, unter dem sie im Netzwerk/
 * bei der Verkehrsmittelwahl (discrete_mode_choice) auftaucht. CA und PT sind
 * MATSim-Standardmodi ("car"/"pt"); AV/SAV sind Platzhalter-Modusstrings, die
 * erst mit der Routing-/Fahrzeugkonfiguration aus Schritt 7 tatsaechlich
 * simulierbar werden (aktuell z. B. noch nicht im equil-Szenario aktiv).
 */
public enum alternatives {
    CA("car"),
    AV("av"),
    PT("pt"),
    SAV("sav");

    private final String matsimMode;

    alternatives(String matsimMode) {
        this.matsimMode = matsimMode;
    }

    /** MATSim-Modusstring, wie er in Legs/DiscreteModeChoiceTrip auftaucht. */
    public String getMatsimMode() {
        return matsimMode;
    }

    private static final Map<String, alternatives> BY_MATSIM_MODE = Arrays.stream(values())
            .collect(Collectors.toMap(alternatives::getMatsimMode, a -> a));

    /** Liefert die Alternative zu einem MATSim-Modusstring, oder null wenn unbekannt/nicht gemappt. */
    public static alternatives fromMatsimMode(String matsimMode) {
        return BY_MATSIM_MODE.get(matsimMode);
    }
}
