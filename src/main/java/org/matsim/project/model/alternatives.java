package org.matsim.project.model;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Die fuenf Modusalternativen des Wahlmodells.
 *
 * CA   = Conventional Automobile (konventioneller Pkw)
 * AV   = Automated Vehicle (privates automatisiertes Fahrzeug)
 * PT   = Public Transport (OEPNV)
 * PSAV = Pooled Shared Automated Vehicle (dynamisches Ride-Pooling, Door-to-
 *        Door, kleine Flotte - wie UberPool/MOIA, nur autonom)
 * SSAV = Shuttle Shared Automated Vehicle (autonomer Shuttle auf Semi-
 *        Fixroute mit virtuellen Haltestellen, Corner-to-Corner, groessere
 *        Fahrzeugkapazitaet)
 *
 * PSAV und SSAV sind beide "geteilt" (das S in SAV) und ersetzen die
 * vormalige, undifferenzierte SAV-Alternative - sie unterscheiden sich im
 * Betriebskonzept (dynamisches Pooling vs. halbfeste Linie), nicht darin,
 * ob geteilt wird.
 *
 * Jede Alternative traegt den MATSim-Modusstring, unter dem sie im Netzwerk/
 * bei der Verkehrsmittelwahl (discrete_mode_choice) auftaucht. CA und PT sind
 * MATSim-Standardmodi ("car"/"pt"); AV/PSAV/SSAV sind Platzhalter-Modusstrings,
 * die erst mit der Routing-/Fahrzeugkonfiguration aus Schritt 7 tatsaechlich
 * simulierbar werden (aktuell z. B. noch nicht im equil-Szenario aktiv).
 */
public enum alternatives {
    CA("car"),
    AV("av"),
    PT("pt"),
    PSAV("psav"),
    SSAV("ssav");

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
