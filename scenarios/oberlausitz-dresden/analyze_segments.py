#!/usr/bin/env python3
"""
Aggregiert MATSim-Output je Mobilitaetssegment (Cluster): Personenzahl,
Wegeanzahl/Person, Modal Split, durchschnittliche Wegelaenge (gesamt und je
Modus). Grundlage fuer die spaetere Herausarbeitung von Uebergangsgruppen
(welche Segmente wechseln bei AV-Verfuegbarkeit am staerksten Modus/Wegeanzahl).

Verwendet zwei MATSim-Output-Dateien desselben Laufs:
  <runId>.output_persons.csv        - eine Zeile je Person, u. a. Spalte "segment"
  <runId>.output_trips.csv[.zst]    - eine Zeile je Weg, u. a. "person"/"main_mode"/"traveled_distance"

Nutzung:
    python3 scenarios/oberlausitz-dresden/analyze_segments.py <output-verzeichnis> [<output-csv>]

Beispiel:
    python3 scenarios/oberlausitz-dresden/analyze_segments.py output/output-1 segment_stats.csv

Ohne <output-csv> wird nur eine Tabelle auf stdout ausgegeben. .zst-Dateien
werden automatisch entpackt (benoetigt das `zstd`-Kommandozeilenwerkzeug).
"""

import csv
import glob
import os
import subprocess
import sys
from collections import defaultdict


def find_output_file(output_dir, suffix):
    matches = glob.glob(os.path.join(output_dir, f"*{suffix}"))
    if not matches:
        matches = glob.glob(os.path.join(output_dir, f"*{suffix}.zst"))
    if not matches:
        raise FileNotFoundError(f"Keine Datei mit Suffix '{suffix}[.zst]' in {output_dir} gefunden.")
    return matches[0]


def open_maybe_zst(path):
    """Liefert einen Text-Iterator ueber die Zeilen von path - entpackt .zst on-the-fly via `zstd -dc`."""
    if path.endswith(".zst"):
        proc = subprocess.Popen(["zstd", "-dc", path], stdout=subprocess.PIPE, text=True)
        return proc.stdout
    return open(path, "r", newline="")


def load_person_segments(persons_path):
    """person_id -> segment (fehlendes/leeres Segment wird als 'unbekannt' gefuehrt)."""
    segments = {}
    with open_maybe_zst(persons_path) as f:
        reader = csv.DictReader(f, delimiter=";")
        for row in reader:
            segment = row.get("segment") or "unbekannt"
            segments[row["person"]] = segment
    return segments


def aggregate(persons_path, trips_path):
    person_segment = load_person_segments(persons_path)

    # segment -> Zaehlwerte
    trip_count = defaultdict(int)
    distance_sum = defaultdict(float)
    mode_count = defaultdict(lambda: defaultdict(int))
    mode_distance_sum = defaultdict(lambda: defaultdict(float))
    persons_with_trips = defaultdict(set)

    with open_maybe_zst(trips_path) as f:
        reader = csv.DictReader(f, delimiter=";")
        for row in reader:
            person_id = row["person"]
            segment = person_segment.get(person_id, "unbekannt")
            mode = row["main_mode"]
            distance_km = float(row["traveled_distance"]) / 1000.0

            trip_count[segment] += 1
            distance_sum[segment] += distance_km
            mode_count[segment][mode] += 1
            mode_distance_sum[segment][mode] += distance_km
            persons_with_trips[segment].add(person_id)

    # Personenzahl je Segment (auch die OHNE jeden Weg an diesem Iterationsstand)
    persons_per_segment = defaultdict(int)
    for segment in person_segment.values():
        persons_per_segment[segment] += 1

    all_modes = sorted({m for counts in mode_count.values() for m in counts})
    all_segments = sorted(persons_per_segment.keys())

    rows = []
    for segment in all_segments:
        n_persons = persons_per_segment[segment]
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
    return rows, all_modes


def print_table(rows, all_modes):
    header = ["segment", "persons", "trips", "trips_per_person", "avg_trip_distance_km"]
    header += [f"share_{m}" for m in all_modes]
    print("  ".join(f"{h:>18}" for h in header))
    for row in rows:
        values = [row[h] for h in header]
        print("  ".join(f"{v:>18}" for v in values))


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    output_dir = sys.argv[1]
    out_csv = sys.argv[2] if len(sys.argv) > 2 else None

    persons_path = find_output_file(output_dir, ".output_persons.csv")
    trips_path = find_output_file(output_dir, ".output_trips.csv")

    rows, all_modes = aggregate(persons_path, trips_path)

    print_table(rows, all_modes)

    if out_csv:
        fieldnames = list(rows[0].keys()) if rows else []
        with open(out_csv, "w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=fieldnames, delimiter=";")
            writer.writeheader()
            writer.writerows(rows)
        print(f"\nOK: {out_csv} geschrieben ({len(rows)} Segmente).")


if __name__ == "__main__":
    main()
