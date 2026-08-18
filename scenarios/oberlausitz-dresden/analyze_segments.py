#!/usr/bin/env python3
"""
Aggregiert MATSim-Output je Mobilitaetssegment (Cluster): Personenzahl,
Wegeanzahl/Person, Modal Split, durchschnittliche Wegelaenge (gesamt und je
Modus) - UND, falls vorhanden, das Ergebnis des Zusatzweg-Mechanismus
(Nullalternative/induzierte Nachfrage: eingefuegt/opt-out/uebersprungen je
Segment). Grundlage fuer die spaetere Herausarbeitung von Uebergangsgruppen
(welche Segmente wechseln bei AV-Verfuegbarkeit am staerksten Modus/
Wegeanzahl, und welche Segmente nehmen den induzierten Zusatzweg am
haeufigsten an).

Verwendet MATSim-Output-Dateien desselben Laufs:
  <runId>.output_persons.csv        - eine Zeile je Person, u. a. Spalte "segment"
                                       und (falls der Lauf einen Zusatzweg-
                                       Mechanismus enthaelt) "nullAlternativeOutcome"
                                       oder "inducedTripOutcome"
  <runId>.output_trips.csv[.zst]    - eine Zeile je Weg, u. a. "person"/"main_mode"/"traveled_distance"

Nutzung:
    python3 scenarios/oberlausitz-dresden/analyze_segments.py <output-verzeichnis>

Schreibt direkt in <output-verzeichnis> (neben den uebrigen MATSim-
Outputdateien, mit demselben runId-Praefix):
  <runId>.segment_stats.csv     - Wegerate + Modal Split je Segment, sortiert
                                   nach Wegen/Person (absteigend); Modus-Spalten
                                   sortiert nach Gesamthaeufigkeit
  <runId>.trip_outcomes.csv     - nur falls nullAlternativeOutcome/
                                   inducedTripOutcome-Spalte vorhanden: je
                                   Segment eingefuegt/opt-out/uebersprungen
                                   (Anzahl + Anteil) sowie Modusverteilung der
                                   eingefuegten Zusatzwege

.zst-Dateien werden automatisch entpackt (benoetigt das `zstd`-Kommandozeilenwerkzeug).
"""

import csv
import glob
import os
import subprocess
import sys
from collections import Counter, defaultdict

OUTCOME_COLUMNS = ("nullAlternativeOutcome", "inducedTripOutcome")


def find_output_file(output_dir, suffix):
    matches = glob.glob(os.path.join(output_dir, f"*{suffix}"))
    if not matches:
        matches = glob.glob(os.path.join(output_dir, f"*{suffix}.zst"))
    if not matches:
        raise FileNotFoundError(f"Keine Datei mit Suffix '{suffix}[.zst]' in {output_dir} gefunden.")
    return matches[0]


def run_id_prefix(persons_path):
    """<pfad>/<runId>.output_persons.csv[.zst] -> '<runId>.'"""
    name = os.path.basename(persons_path)
    for suffix in (".output_persons.csv.zst", ".output_persons.csv"):
        if name.endswith(suffix):
            return name[: -len(suffix)] + "."
    return ""


def open_maybe_zst(path):
    """Liefert einen Text-Iterator ueber die Zeilen von path - entpackt .zst on-the-fly via `zstd -dc`."""
    if path.endswith(".zst"):
        proc = subprocess.Popen(["zstd", "-dc", path], stdout=subprocess.PIPE, text=True)
        return proc.stdout
    return open(path, "r", newline="")


def load_persons(persons_path):
    """person_id -> {'segment': str, 'outcome': str|None}. Fehlendes Segment wird als 'unbekannt' gefuehrt."""
    persons = {}
    outcome_column = None
    with open_maybe_zst(persons_path) as f:
        reader = csv.DictReader(f, delimiter=";")
        for candidate in OUTCOME_COLUMNS:
            if reader.fieldnames and candidate in reader.fieldnames:
                outcome_column = candidate
                break
        for row in reader:
            persons[row["person"]] = {
                "segment": row.get("segment") or "unbekannt",
                "outcome": row.get(outcome_column) if outcome_column else None,
            }
    return persons, outcome_column


def aggregate_segments(persons, trips_path):
    """Wegerate + Modal Split je Segment - Zeilen nach trips_per_person absteigend, Modi nach Gesamthaeufigkeit."""
    trip_count = defaultdict(int)
    distance_sum = defaultdict(float)
    mode_count = defaultdict(lambda: defaultdict(int))
    mode_distance_sum = defaultdict(lambda: defaultdict(float))
    mode_totals = Counter()

    with open_maybe_zst(trips_path) as f:
        reader = csv.DictReader(f, delimiter=";")
        for row in reader:
            person = persons.get(row["person"], {"segment": "unbekannt"})
            segment = person["segment"]
            mode = row["main_mode"]
            distance_km = float(row["traveled_distance"]) / 1000.0

            trip_count[segment] += 1
            distance_sum[segment] += distance_km
            mode_count[segment][mode] += 1
            mode_distance_sum[segment][mode] += distance_km
            mode_totals[mode] += 1

    persons_per_segment = defaultdict(int)
    for info in persons.values():
        persons_per_segment[info["segment"]] += 1

    all_modes = [m for m, _ in mode_totals.most_common()]

    rows = []
    for segment, n_persons in persons_per_segment.items():
        n_trips = trip_count[segment]
        row = {
            "segment": segment,
            "persons": n_persons,
            "trips": n_trips,
            "trips_per_person": round(n_trips / n_persons, 3) if n_persons else 0.0,
            "avg_trip_distance_km": round(distance_sum[segment] / n_trips, 3) if n_trips else 0.0,
        }
        for mode in all_modes:
            m_count = mode_count[segment].get(mode, 0)
            row[f"share_{mode}"] = round(m_count / n_trips, 4) if n_trips else 0.0
            row[f"trips_{mode}"] = m_count
            row[f"avg_distance_km_{mode}"] = (
                round(mode_distance_sum[segment][mode] / m_count, 3) if m_count else 0.0
            )
        rows.append(row)

    rows.sort(key=lambda r: r["trips_per_person"], reverse=True)
    return rows, all_modes


def aggregate_outcomes(persons, segment_order):
    """
    Je Segment: eingefuegt/opt-out/uebersprungen (Anzahl + Anteil) sowie
    Modusverteilung der eingefuegten Zusatzwege. "opted" (Nullalternative
    gewaehlt) taucht nur auf, wenn der Lauf ueberhaupt optedOut-Werte kennt
    (induzierte Nachfrage kennt das nicht, siehe behaviourInducedDemandTripInserter).
    """
    by_segment = defaultdict(lambda: Counter())
    inserted_modes = defaultdict(lambda: Counter())
    has_opt_out = False

    for info in persons.values():
        outcome = info["outcome"]
        if outcome is None:
            continue
        segment = info["segment"]
        if outcome.startswith("inserted:"):
            by_segment[segment]["inserted"] += 1
            inserted_modes[segment][outcome.split(":", 1)[1]] += 1
        elif outcome == "optedOut":
            by_segment[segment]["optedOut"] += 1
            has_opt_out = True
        else:
            by_segment[segment]["skipped"] += 1

    all_inserted_modes = sorted({m for counts in inserted_modes.values() for m in counts})

    rows = []
    for segment in segment_order:
        counts = by_segment.get(segment, Counter())
        total = sum(counts.values())
        row = {
            "segment": segment,
            "considered": total,
            "inserted": counts["inserted"],
            "inserted_pct": round(counts["inserted"] / total, 4) if total else 0.0,
        }
        if has_opt_out:
            row["optedOut"] = counts["optedOut"]
            row["optedOut_pct"] = round(counts["optedOut"] / total, 4) if total else 0.0
        row["skipped"] = counts["skipped"]
        row["skipped_pct"] = round(counts["skipped"] / total, 4) if total else 0.0
        for mode in all_inserted_modes:
            row[f"inserted_{mode}"] = inserted_modes[segment].get(mode, 0)
        rows.append(row)
    return rows, has_opt_out, all_inserted_modes


def print_table(rows, header):
    print("  ".join(f"{h:>16}" for h in header))
    for row in rows:
        values = [row[h] for h in header]
        print("  ".join(f"{v:>16}" for v in values))


def write_csv(rows, fieldnames, path):
    with open(path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, delimiter=";")
        writer.writeheader()
        writer.writerows(rows)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    output_dir = sys.argv[1]
    persons_path = find_output_file(output_dir, ".output_persons.csv")
    trips_path = find_output_file(output_dir, ".output_trips.csv")
    prefix = run_id_prefix(persons_path)

    persons, outcome_column = load_persons(persons_path)

    segment_rows, all_modes = aggregate_segments(persons, trips_path)
    segment_header = ["segment", "persons", "trips", "trips_per_person", "avg_trip_distance_km"]
    segment_header += [f"share_{m}" for m in all_modes]

    print("=== Wegerate + Modal Split je Segment (sortiert nach Wegen/Person) ===")
    print_table(segment_rows, segment_header)

    segment_stats_path = os.path.join(output_dir, f"{prefix}segment_stats.csv")
    write_csv(segment_rows, list(segment_rows[0].keys()) if segment_rows else segment_header, segment_stats_path)
    print(f"\nOK: {segment_stats_path} geschrieben ({len(segment_rows)} Segmente).")

    if outcome_column:
        segment_order = [row["segment"] for row in segment_rows]
        outcome_rows, has_opt_out, inserted_modes = aggregate_outcomes(persons, segment_order)
        outcome_header = ["segment", "considered", "inserted", "inserted_pct"]
        if has_opt_out:
            outcome_header += ["optedOut", "optedOut_pct"]
        outcome_header += ["skipped", "skipped_pct"]
        outcome_header += [f"inserted_{m}" for m in inserted_modes]

        print(f"\n=== Zusatzweg-Ergebnis je Segment (Spalte '{outcome_column}') ===")
        print_table(outcome_rows, outcome_header)

        outcomes_path = os.path.join(output_dir, f"{prefix}trip_outcomes.csv")
        write_csv(outcome_rows, outcome_header, outcomes_path)
        print(f"\nOK: {outcomes_path} geschrieben ({len(outcome_rows)} Segmente).")
    else:
        print(
            f"\nHinweis: weder 'nullAlternativeOutcome' noch 'inducedTripOutcome' in "
            f"{persons_path} gefunden - dieser Lauf enthaelt keinen Zusatzweg-Mechanismus "
            f"(z. B. Branch feature/behaviour-module, oder ein Lauf von vor dem Attribut-Tagging). "
            f"Keine trip_outcomes.csv geschrieben."
        )


if __name__ == "__main__":
    main()
