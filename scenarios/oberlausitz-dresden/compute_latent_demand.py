#!/usr/bin/env python3
"""
Berechnet die latente Nachfrage T^lat aus einem Basis- und einem AVM-Lauf
(siehe Abschnitt "Latente Nachfrage" der Auftraggeber-Spezifikation,
Gl. \\ref{eq:realisierung}/\\ref{eq:latent}): beide Laeufe muessen mit
IDENTISCHEM randomSeed und identischer Population gefahren worden sein und
sich einzig in der Verfuegbarkeit von AV/PSAV/SSAV im Choice-Set
unterscheiden (siehe behaviourConfigGroup.avmModesEnabled-Javadoc,
scripts/run-oberlausitz-dresden-basis.sh fuer den Basislauf).

Liest je Lauf die Spalte "nullAlternativeOutcome" aus <runId>.output_persons.csv
(geschrieben von behaviourCandidateTripInserter, siehe dortigen Javadoc) und
bildet je Person (verknuepft ueber die person-Spalte) den binaeren Indikator

    y_n = 1[outcome beginnt mit "inserted:"]     (Kandidatenweg fand statt)

sowie die Differenz

    l_n = y_AVM_n - y_base_n in {-1, 0, 1}
    T^lat = Summe der l_n ueber alle Personen (l_n=-1 gezaehlt wie in der Summe,
            sollte aber wegen der Choice-Set-Monotonie (C_base c C_AVM) nicht
            vorkommen - siehe Warnung unten, falls doch).

Nutzung:
    python3 scenarios/oberlausitz-dresden/compute_latent_demand.py \\
        <avm-output-verzeichnis> <basis-output-verzeichnis>

Schreibt in <avm-output-verzeichnis> (mit dem dortigen runId-Praefix):
  <runId>.latent_demand.csv             - eine Zeile: T^lat, Populationsgroesse,
                                           Anteil, Anzahl Monotonie-Verletzungen
  <runId>.latent_demand_by_segment.csv  - T^lat je Segment (Spalte "segment"
                                           aus dem AVM-Lauf)

.zst-Dateien werden automatisch entpackt (benoetigt das `zstd`-Kommandozeilenwerkzeug).
"""

import csv
import glob
import os
import subprocess
import sys
from collections import defaultdict

OUTCOME_COLUMN = "nullAlternativeOutcome"


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


def load_outcomes(output_dir):
    """person_id -> {'segment': str, 'y': 0|1}. Bricht ab, falls die Spalte fehlt (siehe Modul-Docstring)."""
    persons_path = find_output_file(output_dir, ".output_persons.csv")
    result = {}
    with open_maybe_zst(persons_path) as f:
        reader = csv.DictReader(f, delimiter=";")
        if not reader.fieldnames or OUTCOME_COLUMN not in reader.fieldnames:
            raise ValueError(
                f"Spalte '{OUTCOME_COLUMN}' fehlt in {persons_path} - das ist kein Lauf mit dem "
                f"Nullalternative-Mechanismus (behaviourCandidateTripInserter), oder ein Lauf von "
                f"vor dem Attribut-Tagging."
            )
        for row in reader:
            outcome = row.get(OUTCOME_COLUMN) or ""
            result[row["person"]] = {
                "segment": row.get("segment") or "unbekannt",
                "y": 1 if outcome.startswith("inserted:") else 0,
            }
    return result, persons_path


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)

    avm_dir, base_dir = sys.argv[1], sys.argv[2]
    avm_outcomes, avm_persons_path = load_outcomes(avm_dir)
    base_outcomes, base_persons_path = load_outcomes(base_dir)

    avm_ids = set(avm_outcomes)
    base_ids = set(base_outcomes)
    if avm_ids != base_ids:
        only_avm = len(avm_ids - base_ids)
        only_base = len(base_ids - avm_ids)
        print(
            f"WARNUNG: unterschiedliche Personenmengen ({only_avm} nur im AVM-Lauf, "
            f"{only_base} nur im Basislauf) - ist das wirklich dieselbe Population? "
            f"Nur die Schnittmenge wird ausgewertet.",
            file=sys.stderr,
        )
    common_ids = avm_ids & base_ids

    l_by_segment = defaultdict(int)
    persons_by_segment = defaultdict(int)
    total_latent = 0
    violations = 0

    for person_id in common_ids:
        y_avm = avm_outcomes[person_id]["y"]
        y_base = base_outcomes[person_id]["y"]
        l_n = y_avm - y_base
        if l_n < 0:
            violations += 1
        segment = avm_outcomes[person_id]["segment"]
        persons_by_segment[segment] += 1
        l_by_segment[segment] += l_n
        total_latent += l_n

    n = len(common_ids)
    share = total_latent / n if n else 0.0

    print(f"AVM-Lauf:    {avm_persons_path}")
    print(f"Basis-Lauf:  {base_persons_path}")
    print(f"Verglichene Personen: {n}")
    print(f"T^lat (latente/induzierte Wege): {total_latent}  ({share:.4%} der Population)")
    if violations:
        print(
            f"WARNUNG: {violations} Personen mit l_n=-1 (y_base=1, y_AVM=0) - verletzt die "
            f"erwartete Choice-Set-Monotonie C_base c C_AVM. Pruefen, ob AVM_MODES_ENABLED "
            f"in beiden Laeufen wirklich nur den Choice-Set-Unterschied ausmacht und ob "
            f"randomSeed/Population in beiden Configs identisch sind.",
            file=sys.stderr,
        )
    else:
        print("Monotonie-Check OK: kein l_n=-1 aufgetreten.")

    prefix = run_id_prefix(avm_persons_path)

    summary_path = os.path.join(avm_dir, f"{prefix}latent_demand.csv")
    with open(summary_path, "w", newline="") as f:
        writer = csv.DictWriter(
            f, fieldnames=["persons_compared", "latent_trips", "latent_share", "monotonicity_violations"],
            delimiter=";",
        )
        writer.writeheader()
        writer.writerow({
            "persons_compared": n,
            "latent_trips": total_latent,
            "latent_share": round(share, 6),
            "monotonicity_violations": violations,
        })
    print(f"\nOK: {summary_path} geschrieben.")

    segment_path = os.path.join(avm_dir, f"{prefix}latent_demand_by_segment.csv")
    with open(segment_path, "w", newline="") as f:
        writer = csv.DictWriter(
            f, fieldnames=["segment", "persons", "latent_trips", "latent_share"], delimiter=";",
        )
        writer.writeheader()
        for segment in sorted(persons_by_segment, key=lambda s: l_by_segment[s], reverse=True):
            p = persons_by_segment[segment]
            lat = l_by_segment[segment]
            writer.writerow({
                "segment": segment,
                "persons": p,
                "latent_trips": lat,
                "latent_share": round(lat / p, 6) if p else 0.0,
            })
    print(f"OK: {segment_path} geschrieben ({len(persons_by_segment)} Segmente).")


if __name__ == "__main__":
    main()
