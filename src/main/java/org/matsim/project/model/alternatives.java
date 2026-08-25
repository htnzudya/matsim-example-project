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
 * PSAV = Pooled Shared Automated Vehicle (dynamisches Ride-Pooling MIT
 *        virtuellen Haltestellen - wie MOIA, nur autonom; mehrere Fahrgaeste
 *        mit unterschiedlichem Ziel teilen sich gleichzeitig ein Fahrzeug,
 *        Zu-/Abgang zu Fuss zur naechsten virtuellen Haltestelle statt
 *        reinem Door-to-Door)
 * SSAV = Shuttle Shared Automated Vehicle (geshuttelter Robotaxi-Dienst,
 *        Door-to-Door, aber OHNE Pooling - befoerdert immer nur einen
 *        Kunden/eine Buchung gleichzeitig, "shared" im Sinne einer
 *        flottenbetriebenen, nicht privat besessenen Fahrzeugflotte)
 *
 * KORREKTUR (Schritt 9): PSAV/SSAV waren zuvor vertauscht implementiert
 * (PSAV faelschlich Door-to-Door, SSAV faelschlich mit Haltestellen UND
 * groesserer Kapazitaet). Das S in SAV bezieht sich bei SSAV auf die
 * geteilte FLOTTE (kein privater Besitz), nicht auf geteilte FAHRTEN -
 * PSAV und SSAV unterscheiden sich im Betriebskonzept (gepooltes
 * Haltestellensystem vs. exklusives Door-to-Door), nicht beide darin, ob
 * gleichzeitig mehrere Fahrgaeste an Bord sind.
 *
 * Jede Alternative traegt den MATSim-Modusstring, unter dem sie im Netzwerk/
 * bei der Verkehrsmittelwahl (discrete_mode_choice) auftaucht. CA und PT sind
 * MATSim-Standardmodi ("car"/"pt"); AV/PSAV/SSAV sind Platzhalter-Modusstrings,
 * die erst mit der Routing-/Fahrzeugkonfiguration aus Schritt 7 tatsaechlich
 * simulierbar werden (aktuell z. B. noch nicht im equil-Szenario aktiv).
 *
 * BIKE/WALK/RIDE (Auftraggeber-Vorgabe): die drei erhobenen "klassischen"
 * Modi, fuer die es KEINE SLR-Konstrukt-/Zeit-/Kostendaten gibt (die gesamte
 * gamma/beta-Struktur stammt aus einer Studie speziell zur AVM-Akzeptanz).
 * Sie laufen deshalb NUR ueber ihren ASC-Wert (modeParams mit allen
 * Beta- und Gamma-Koeffizienten auf 0, siehe behaviourBaselineAscCalibrator-Klassen-Javadoc
 * fuer die Kalibrierung dieser ASC-Werte gegen den real erhobenen Modal
 * Split). Vorher (siehe Git-History) waren Touren mit diesen Modi komplett
 * von der Umplanung ausgeschlossen (behaviourNonDcmModeTourFilter) - das ist
 * damit obsolet, DCM_MODES dort baut sich aus alternatives.values() und
 * schliesst diese drei jetzt automatisch mit ein.
 */
public enum alternatives {
    CA("car"),
    AV("av"),
    PT("pt"),
    PSAV("psav"),
    SSAV("ssav"),
    BIKE("bike"),
    WALK("walk"),
    RIDE("ride");

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
