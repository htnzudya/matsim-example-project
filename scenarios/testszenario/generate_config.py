#!/usr/bin/env python3
"""
Erzeugt den <module name="verhaltensmodell">-Block in config.xml aus
fertigeabmparameter.xlsx (Nutzenschicht: gamma, beta, delta) und
ferigeSegmentierungParameter.xlsx (Profilschicht: X* je Segment).

Quelle der Wahrheit sind die beiden Excel-Dateien. Workflow:
  1. Excel bearbeiten.
  2. python3 scenarios/testszenario/generate_config.py
  3. Diff von config.xml pruefen, committen.

Nur Standardbibliothek (kein openpyxl noetig) - beide xlsx sind ZIPs mit
SpreadsheetML-XML, das wird hier direkt geparst.

Was NICHT aus den Excel-Dateien kommt (in diesem Skript als Konstante
gepflegt): asc/ascSd/costPerKm/costPerKmWithTicket je Modus, betaCostSd,
scaleParameter, randomSeed, segmentAttribute, homeActivityType,
ticketAttribute, ticketOwnedValue.

Konstrukte: beide Excel-Dateien sind auf denselben Satz von 11 SLR-Konstrukten
abgestimmt (TAM/TPB/PMT) - Zeilen 2-12 in fertigeabmparameter.xlsx
(Konstrukt x Modus -> gamma) entsprechen Zeilen 5-15 in
ferigeSegmentierungParameter.xlsx (Konstrukt x Cluster -> X*, M und SD).
Fruehere Platzhalterkonstrukte (ptAffinity/carsharingAffinity/carAffinity/
attitude/habitDisposition/strangerDiscomfort) sind entfallen, weil sie nur
fehlbenannte Fassungen von vier dieser 11 Konstrukte waren (siehe
Git-History: die alten SEGMENTS-Werte fuer ptAffinity/carsharingAffinity/
carAffinity stimmen zahlenwertgleich mit Umweltbewusstsein/Sharing-
Bereitschaft/Fahrfreude aus der finalen Segmentierungstabelle ueberein).

Kosten: die Excel gibt beta_Kosten je Einkommensklasse (niedrig/mittel/
hoch), aber denselben Wert ueber alle Modi hinweg. Auf Wunsch wird hier
bewusst NUR die Spalte "mittleres Einkommen" uebernommen; ein eigenes
Einkommenskonzept im Code gibt es (noch) nicht.
"""

import re
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

SCENARIO_DIR = Path(__file__).resolve().parent
ABM_XLSX_PATH = SCENARIO_DIR / "fertigeabmparameter.xlsx"
SEGMENT_XLSX_PATH = SCENARIO_DIR / "ferigeSegmentierungParameter.xlsx"
CONFIG_PATH = SCENARIO_DIR / "config.xml"

NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}

# Excel-Spalte (Buchstabe) -> alternatives-Name. Excel nennt AV "PAV", das
# Enum org.matsim.project.model.alternatives kennt es als "AV".
MODE_COLUMNS = {
    "C": "CA",
    "D": "PT",
    "E": "AV",
    "F": "PSAV",
    "G": "SSAV",
}

# Alle Modi in fester Ausgabe-Reihenfolge (entspricht der bisherigen config.xml).
MODES = ["CA", "AV", "PT", "PSAV", "SSAV"]

# fertigeabmparameter.xlsx, Zeilen 2-12 (Konstrukt x Modus -> gamma).
# Schluessel identisch zu den Konstrukt-Zeilen in ferigeSegmentierungParameter.xlsx
# (siehe ROW_TO_SEGMENT_CONSTRUCT), Reihenfolge = Ausgabereihenfolge in
# constructs/gamma.
ROW_TO_CONSTRUCT = {
    2: "techAffinity",
    3: "environmentalAwareness",
    4: "sharingWillingness",
    5: "drivingEnjoyment",
    6: "perceivedUsefulness",
    7: "perceivedEaseOfUse",
    8: "trust",
    9: "safetyPerception",
    10: "perceivedRisk",
    11: "perceivedBehaviouralControl",
    12: "socialNorm",
}

ALL_CONSTRUCTS = list(ROW_TO_CONSTRUCT.values())

# ferigeSegmentierungParameter.xlsx, Zeilen 5-15 (Konstrukt x Cluster -> X*).
# Gleiche Konstrukt-Schluessel wie ROW_TO_CONSTRUCT, andere Zeilennummern
# (die Segmentierungstabelle hat 4 zusaetzliche Kopfzeilen).
ROW_TO_SEGMENT_CONSTRUCT = {
    5: "techAffinity",
    6: "environmentalAwareness",
    7: "sharingWillingness",
    8: "drivingEnjoyment",
    9: "perceivedUsefulness",
    10: "perceivedEaseOfUse",
    11: "trust",
    12: "safetyPerception",
    13: "perceivedRisk",
    14: "perceivedBehaviouralControl",
    15: "socialNorm",
}

# ferigeSegmentierungParameter.xlsx: die 8 Cluster in Spaltenreihenfolge
# (Cluster i -> M-Spalte B + 3*(i-1), SD-Spalte direkt danach), mit
# sprechendem segmentId fuer die Config.
SEGMENT_IDS = [
    "multioptionaler_urbanist",
    "aktiver_aelterer",
    "pragmatischer_urbanist",
    "oepnv_gebundener",
    "familienmitglied_mit_kindern",
    "hochmobiler",
    "inaktiver_aelterer",
    "laendlicher",
]
SEGMENT_PROBABILITY_ROW = 4

# ---------------------------------------------------------------------
# Nicht aus der Excel: ASC/ascSd/costPerKm je Modus (bisheriger
# Config-Stand, siehe Git-History von config.xml).
ASC = {"CA": 0.0, "AV": -0.50, "PT": -1.20, "PSAV": -0.70, "SSAV": -1.10}
ASC_SD = {"CA": 0.0, "AV": 0.60, "PT": 0.50, "PSAV": 0.65, "SSAV": 0.75}

# Kostensaetze je gefahrenem/gereistem Kilometer (Euro), Stand [Kostentabelle
# der Arbeit]. CA/AV sind als Euro/Fahrzeug-km angegeben (Betriebskosten des
# privaten Fahrzeugs) - fuer den alleinfahrenden Halter ist Fahrzeug-km ==
# Personen-km, deshalb 1:1 uebernehmbar in costPerKm (das im Modell IMMER mit
# der individuell gefahrenen Distanz des Agenten multipliziert wird, siehe
# behaviourUtilityEstimator.buildTripContext). PT/PSAV/SSAV sind bereits als
# Euro/Personen-km angegeben (Tarif fuer den einzelnen Fahrgast), passen also
# direkt.
#
# PSAV: nur der Kilometersatz genannt (0,38 EUR/Pkm), keine feste
# Grundgebuehr/-fahrt bekannt (anders als SSAV) - falls PSAV auch eine
# Grundgebuehr hat, bitte nachreichen.
#
# SSAV hat ZUSAETZLICH eine feste Grundgebuehr von 1,50 EUR/Fahrt - das
# aktuelle Modell kennt nur einen linearen Distanztarif (costEuro = costPerKm
# * distanceKm, siehe modeParams-Javadoc), keine fixe Grundgebuehr pro Fahrt.
# Die 1,50 EUR/Fahrt sind hier NICHT eingerechnet - fehlt also noch ein
# eigenes baseFare-Feld im Modell, wenn das beruecksichtigt werden soll.
COST_PER_KM = {"CA": 0.20, "AV": 0.21, "PT": 0.14, "PSAV": 0.38, "SSAV": 0.50}
BETA_COST_SD = {"CA": 0.05, "AV": 0.05, "PT": 0.06, "PSAV": 0.05, "SSAV": 0.05}

# costPerKm-Override fuer Personen mit bereits vorhandenem Abo/Zeitkarte (siehe
# modeParams.costPerKmWithTicket-Javadoc bzw. behaviourConfigGroup.ticketAttribute:
# Oberlausitz/Dresden liefert das Personenattribut "ptTicket" mit, ~14% der
# Population "full"). NO_TICKET_OVERRIDE (-1.0) = kein Override, es gilt immer
# costPerKm. Nur fuer PT gesetzt (0.0 EUR/km, PLATZHALTER: die zusaetzliche
# Fahrt eines Abo-Inhabers kostet marginal effektiv nichts, der Fixpreis ist
# bereits bezahlt) - CA/AV/PSAV/SSAV kennen kein Abo-Konzept in diesem Modell.
NO_TICKET_OVERRIDE = -1.0
COST_PER_KM_WITH_TICKET = {"CA": NO_TICKET_OVERRIDE, "AV": NO_TICKET_OVERRIDE, "PT": 0.0,
                           "PSAV": NO_TICKET_OVERRIDE, "SSAV": NO_TICKET_OVERRIDE}

MINUTES_TO_HOURS = 60.0


# ----------------------------------------------------------------- Excel-Parser

def col_to_idx(col_letters):
    idx = 0
    for ch in col_letters:
        idx = idx * 26 + (ord(ch) - ord("A") + 1)
    return idx


def idx_to_col(idx):
    letters = ""
    while idx > 0:
        idx, remainder = divmod(idx - 1, 26)
        letters = chr(65 + remainder) + letters
    return letters


def read_grid(xlsx_path):
    """Liest sheet1 als {(row, col_idx): value}-Grid, Shared Strings aufgeloest."""
    with zipfile.ZipFile(xlsx_path) as z:
        shared = []
        if "xl/sharedStrings.xml" in z.namelist():
            ss_root = ET.fromstring(z.read("xl/sharedStrings.xml"))
            for si in ss_root.findall("m:si", NS):
                texts = si.findall(".//m:t", NS)
                shared.append("".join(t.text or "" for t in texts))
        sheet_root = ET.fromstring(z.read("xl/worksheets/sheet1.xml"))

    grid = {}
    for row in sheet_root.findall(".//m:sheetData/m:row", NS):
        r = int(row.get("r"))
        for c in row.findall("m:c", NS):
            ref = c.get("r")
            m = re.match(r"([A-Z]+)(\d+)", ref)
            col_letters = m.group(1)
            colidx = col_to_idx(col_letters)
            t = c.get("t")
            v_el = c.find("m:v", NS)
            val = v_el.text if v_el is not None else None
            if t == "s" and val is not None:
                val = shared[int(val)]
            grid[(r, colidx)] = val
    return grid


def cell(grid, row, col_letter):
    return grid.get((row, col_to_idx(col_letter)))


def cell_idx(grid, row, col_idx):
    return grid.get((row, col_idx))


def to_float(raw, default=0.0):
    """'fix' und '--' (keine Streuung/kein Wert) werden zu 0.0."""
    if raw is None:
        return default
    raw = raw.strip()
    if raw in ("fix", "--", ""):
        return default
    return float(raw)


# ----------------------------------------------------------------- Extraktion: ABM-Parameter

def extract_abm(grid):
    gamma = {mode: {} for mode in MODES}
    for row, construct in ROW_TO_CONSTRUCT.items():
        for col_letter, mode in MODE_COLUMNS.items():
            gamma[mode][construct] = to_float(cell(grid, row, col_letter))

    # beta_Kosten: nur "mittleres Einkommen" (Zeile 13), identisch ueber alle Modi -
    # zur Absicherung gegen die Excel pruefen statt den Wert nur einmal zu lesen.
    beta_cost_values = {mode: to_float(cell(grid, 13, col_letter))
                         for col_letter, mode in MODE_COLUMNS.items()}
    distinct = set(beta_cost_values.values())
    if len(distinct) != 1:
        raise ValueError(f"beta_Kosten (mittleres Einkommen) ist nicht ueber alle Modi "
                          f"identisch, wie erwartet: {beta_cost_values}")
    beta_cost = distinct.pop()

    beta_ivt = {}
    beta_ivt_sd = {}
    beta_wait = {}
    beta_wait_sd = {}
    for col_letter, mode in MODE_COLUMNS.items():
        beta_ivt[mode] = to_float(cell(grid, 16, col_letter)) * MINUTES_TO_HOURS
        beta_ivt_sd[mode] = to_float(cell(grid, 17, col_letter)) * MINUTES_TO_HOURS
        beta_wait[mode] = to_float(cell(grid, 18, col_letter)) * MINUTES_TO_HOURS
        beta_wait_sd[mode] = to_float(cell(grid, 19, col_letter)) * MINUTES_TO_HOURS

    # Vormodus-Matrix: Zeilen 20-24 = "Vormodus: <Modus>", Spalten C-G = Zielmodus.
    prev_mode_rows = {20: "CA", 21: "PT", 22: "AV", 23: "PSAV", 24: "SSAV"}
    delta_by_previous_mode = {mode: {} for mode in MODES}
    for row, previous_mode in prev_mode_rows.items():
        for col_letter, target_mode in MODE_COLUMNS.items():
            delta_by_previous_mode[target_mode][previous_mode] = to_float(cell(grid, row, col_letter))

    return {
        "gamma": gamma,
        "beta_cost": beta_cost,
        "beta_ivt": beta_ivt,
        "beta_ivt_sd": beta_ivt_sd,
        "beta_wait": beta_wait,
        "beta_wait_sd": beta_wait_sd,
        "delta_by_previous_mode": delta_by_previous_mode,
    }


# ----------------------------------------------------------------- Extraktion: Segmentierung

def extract_segments(grid):
    segments = []
    for i, segment_id in enumerate(SEGMENT_IDS):
        m_col = 2 + 3 * i
        sd_col = m_col + 1

        probability = to_float(cell_idx(grid, SEGMENT_PROBABILITY_ROW, m_col))
        constructs = {}
        constructs_sd = {}
        for row, construct in ROW_TO_SEGMENT_CONSTRUCT.items():
            constructs[construct] = to_float(cell_idx(grid, row, m_col))
            constructs_sd[construct] = to_float(cell_idx(grid, row, sd_col))

        segments.append((segment_id, probability, constructs, constructs_sd))
    return segments


# ----------------------------------------------------------------- XML-Rendering

def fmt(x):
    """Kompakte, aber verlustfreie Dezimaldarstellung."""
    if x == int(x):
        return str(int(x))
    return f"{x:.6f}".rstrip("0").rstrip(".")


def render_map(keys, values):
    return ",".join(f"{k}={fmt(values.get(k, 0.0))}" for k in keys)


def render_mode_param_set(mode, data):
    gamma = data["gamma"][mode]
    gamma_sd = {}  # Excel liefert keine Streuung fuer gamma.

    return f"""        <parameterset type="modeParams">
            <param name="mode" value="{mode}"/>
            <param name="asc" value="{fmt(ASC[mode])}"/>
            <param name="ascSd" value="{fmt(ASC_SD[mode])}"/>
            <param name="betaInVehicleTime" value="{fmt(data['beta_ivt'][mode])}"/>
            <param name="betaInVehicleTimeSd" value="{fmt(data['beta_ivt_sd'][mode])}"/>
            <param name="betaWaitTime" value="{fmt(data['beta_wait'][mode])}"/>
            <param name="betaWaitTimeSd" value="{fmt(data['beta_wait_sd'][mode])}"/>
            <param name="betaCost" value="{fmt(data['beta_cost'])}"/>
            <param name="betaCostSd" value="{fmt(BETA_COST_SD[mode])}"/>
            <param name="costPerKm" value="{fmt(COST_PER_KM[mode])}"/>
            <param name="costPerKmWithTicket" value="{fmt(COST_PER_KM_WITH_TICKET[mode])}"/>
            <param name="deltaByPreviousMode" value="{render_map(MODES, data['delta_by_previous_mode'][mode])}"/>
            <param name="gamma" value="{render_map(ALL_CONSTRUCTS, gamma)}"/>
            <param name="gammaSd" value="{render_map(ALL_CONSTRUCTS, gamma_sd)}"/>
        </parameterset>"""


def render_segment_param_set(segment_id, probability, constructs, constructs_sd):
    return f"""        <parameterset type="segmentParams">
            <param name="segmentId" value="{segment_id}"/>
            <param name="probability" value="{fmt(probability)}"/>
            <param name="constructs" value="{render_map(ALL_CONSTRUCTS, constructs)}"/>
            <param name="constructsSd" value="{render_map(ALL_CONSTRUCTS, constructs_sd)}"/>
        </parameterset>"""


def render_module(abm_data, segments):
    segment_blocks = "\n\n".join(
        render_segment_param_set(seg_id, prob, constructs, constructs_sd)
        for seg_id, prob, constructs, constructs_sd in segments)
    mode_blocks = "\n\n".join(render_mode_param_set(mode, abm_data) for mode in MODES)

    return f"""    <!-- =========================================================================
         PARAMETER-SCHNITTSTELLE DES ADD-ONS

         GENERIERT von scenarios/testszenario/generate_config.py aus
         fertigeabmparameter.xlsx und ferigeSegmentierungParameter.xlsx -
         NICHT VON HAND BEARBEITEN. Werte aendern: Excel bearbeiten, dann
         `python3 scenarios/testszenario/generate_config.py`.

         Kosten (betaCost): nur die Excel-Spalte "mittleres Einkommen"
         uebernommen (niedrig/hoch verworfen) - es gibt (noch) kein
         Einkommenskonzept im Code, betaCost ist weiterhin ein einzelner
         Wert je Modus.

         Zeit (betaInVehicleTime/betaWaitTime): Excel gibt je Minute an,
         hier auf je Stunde umgerechnet (x60) - Code/TripContext arbeiten
         durchgaengig mit Stunden.

         deltaByPreviousMode: volle Vormodus-Uebergangsmatrix aus der Excel
         (vorheriger Modus -> Traegheitsbonus fuer DIESEN Zielmodus), nicht
         nur ein Bonus bei Modusbeibehaltung.

         Konstrukte: 11 SLR-Konstrukte (TAM/TPB/PMT), identisch benannt in
         gamma (fertigeabmparameter.xlsx, Konstrukt x Modus) und constructs/
         constructsSd (ferigeSegmentierungParameter.xlsx, Konstrukt x
         Cluster, M und SD der z-standardisierten Clusterwerte X*).

         Zwei Schichten:
           segmentParams = Profilschicht  (Segment -> latente Konstrukte X*, je mit Sd)
           modeParams    = Nutzenschicht  (Modus x Konstrukt -> gamma, je mit Sd)
         ========================================================================= -->

    <module name="verhaltensmodell">

        <param name="scaleParameter" value="1.0"/>
        <param name="randomSeed" value="4711"/>
        <param name="segmentAttribute" value="segment"/>
        <param name="ticketAttribute" value="ptTicket"/>
        <param name="ticketOwnedValue" value="full"/>
        <!-- Oberlausitz/Dresden liefert das Personenattribut "ptTicket" mit
             Werten "none"/"full" (~14% der Population "full") - siehe
             modeParams.costPerKmWithTicket-Javadoc fuer die Verwendung. -->

{segment_blocks}

{mode_blocks}

    </module>"""


def replace_module_block(config_text, new_module_xml):
    # Matcht sowohl den von uns generierten Kommentarblock (falls schon vorhanden,
    # damit wiederholte Laeufe nicht kumulativ Kommentare anhaeufen) als auch den
    # <module>-Block selbst.
    pattern = re.compile(
        r'(    <!-- =+\s*PARAMETER-SCHNITTSTELLE DES ADD-ONS.*?=+ -->\s*\n\s*)?'
        r'    <module name="verhaltensmodell">.*?</module>',
        re.DOTALL)
    if not pattern.search(config_text):
        raise ValueError('Kein <module name="verhaltensmodell"> in config.xml gefunden.')
    return pattern.sub(lambda _: new_module_xml, config_text, count=1)


def main():
    abm_grid = read_grid(ABM_XLSX_PATH)
    abm_data = extract_abm(abm_grid)

    segment_grid = read_grid(SEGMENT_XLSX_PATH)
    segments = extract_segments(segment_grid)

    new_module_xml = render_module(abm_data, segments)

    config_text = CONFIG_PATH.read_text(encoding="utf-8")
    updated = replace_module_block(config_text, new_module_xml)
    CONFIG_PATH.write_text(updated, encoding="utf-8")
    print(f"OK: {CONFIG_PATH} aktualisiert (Quelle: {ABM_XLSX_PATH.name}, {SEGMENT_XLSX_PATH.name}).")


if __name__ == "__main__":
    main()
