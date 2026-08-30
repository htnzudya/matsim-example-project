#!/usr/bin/env Rscript
# Baut aus den von analyze_segments.py geschriebenen segment_stats.csv/
# trip_outcomes.csv zusaetzlich: Long-Format-Tabellen (fuer eigene R/ggplot2-
# bzw. Python/Excel-Diagramme) UND direkt gerenderte PNG-Diagramme. Schreibt
# alles direkt in dasselbe Output-Verzeichnis, keine Web-/HTML-Ausgabe.
#
# Voraussetzung: analyze_segments.py ist fuer dasselbe Verzeichnis bereits
# gelaufen (schreibt <runId>.segment_stats.csv und ggf. <runId>.trip_outcomes.csv).
#
# Nutzung:
#   Rscript scenarios/oberlausitz-dresden/analyze_segments.R <output-verzeichnis>
#
# Schreibt (alle mit demselben <runId>-Praefix wie die uebrigen Outputs):
#   <runId>.mode_share_long.csv   - eine Zeile je Segment x Modus
#                                    (segment, mode, share, trips, avg_distance_km)
#   <runId>.modal_split.png       - gestapeltes Balkendiagramm: Modal Split je Segment
#   <runId>.trips_per_person.png  - Balkendiagramm: Wege/Person je Segment
#   <runId>.outcome_long.csv      - nur falls trip_outcomes.csv vorhanden - eine
#                                    Zeile je Segment x Ergebnis (inserted/optedOut/
#                                    skipped)
#   <runId>.trip_outcomes.png     - nur falls trip_outcomes.csv vorhanden
#
# Nur Basis-R (read.table/barplot/png) - keine Pakete noetig.

args <- commandArgs(trailingOnly = TRUE)
if (length(args) < 1) {
  stop("Nutzung: Rscript analyze_segments.R <output-verzeichnis>")
}
output_dir <- args[1]

find_file <- function(dir, suffix_pattern) {
  matches <- list.files(dir, pattern = paste0(suffix_pattern, "$"), full.names = TRUE)
  if (length(matches) == 0) {
    return(NA_character_)
  }
  matches[1]
}

segment_stats_path <- find_file(output_dir, "segment_stats\\.csv")
if (is.na(segment_stats_path)) {
  stop(paste0(
    "Keine *segment_stats.csv in ", output_dir, " gefunden - erst ",
    "scenarios/oberlausitz-dresden/analyze_segments.py <output-verzeichnis> laufen lassen."
  ))
}
prefix <- sub("segment_stats\\.csv$", "", basename(segment_stats_path))

stats <- read.table(segment_stats_path, sep = ";", header = TRUE, stringsAsFactors = FALSE)
# Reihenfolge wie von analyze_segments.py sortiert (trips_per_person absteigend) beibehalten.
segments <- stats$segment

share_cols <- grep("^share_", names(stats), value = TRUE)
modes <- sub("^share_", "", share_cols)

## ---- Long-Format: Modal Split ---------------------------------------------

mode_share_long <- do.call(rbind, lapply(modes, function(mode) {
  data.frame(
    segment = segments,
    mode = mode,
    share = stats[[paste0("share_", mode)]],
    trips = stats[[paste0("trips_", mode)]],
    avg_distance_km = stats[[paste0("avg_distance_km_", mode)]],
    stringsAsFactors = FALSE
  )
}))
mode_share_long <- mode_share_long[order(match(mode_share_long$segment, segments), mode_share_long$mode), ]

mode_share_long_path <- file.path(output_dir, paste0(prefix, "mode_share_long.csv"))
write.table(mode_share_long, mode_share_long_path, sep = ";", row.names = FALSE, quote = FALSE)
cat("OK:", mode_share_long_path, "geschrieben (", nrow(mode_share_long), "Zeilen).\n")

## ---- Diagramm: Modal Split je Segment (gestapelt) -------------------------

share_matrix <- t(as.matrix(stats[, share_cols, drop = FALSE]))
rownames(share_matrix) <- modes
colnames(share_matrix) <- segments

palette_modes <- hcl.colors(length(modes), palette = "Dark 3")

modal_split_path <- file.path(output_dir, paste0(prefix, "modal_split.png"))
png(modal_split_path, width = 1600, height = 1000, res = 150)
op <- par(mar = c(10, 4, 4, 12), xpd = TRUE)
bp <- barplot(share_matrix, col = palette_modes, las = 2, ylab = "Anteil an Wegen",
              main = "Modal Split je Segment", border = NA)
# Prozentwerte je Segment/Modus in die gestapelten Balken schreiben - nur ab 3%
# Anteil (sonst zu wenig Platz im Segment, Beschriftung wird unleserlich).
for (i in seq_along(segments)) {
  cum <- cumsum(share_matrix[, i])
  prev <- c(0, head(cum, -1))
  mid <- (prev + cum) / 2
  vals <- share_matrix[, i]
  labels <- ifelse(vals >= 0.03, paste0(round(vals * 100, 1), "%"), "")
  text(bp[i], mid, labels, cex = 0.55)
}
legend("topright", inset = c(-0.20, 0), legend = modes, fill = palette_modes, bty = "n", title = "Modus")
par(op)
invisible(dev.off())
cat("OK:", modal_split_path, "geschrieben.\n")

## ---- Diagramm: Wegerate je Segment -----------------------------------------

trips_per_person_path <- file.path(output_dir, paste0(prefix, "trips_per_person.png"))
png(trips_per_person_path, width = 1600, height = 1000, res = 150)
op <- par(mar = c(10, 4, 4, 2))
bp <- barplot(stats$trips_per_person, names.arg = segments, las = 2,
              col = "#4C72B0", ylab = "Wege / Person", main = "Wegerate je Segment", border = NA)
text(bp, stats$trips_per_person, labels = round(stats$trips_per_person, 2), pos = 3, cex = 0.8)
par(op)
invisible(dev.off())
cat("OK:", trips_per_person_path, "geschrieben.\n")

## ---- Zusatzweg-Ergebnis (Nullalternative/induzierte Nachfrage), falls vorhanden ----

outcomes_path <- find_file(output_dir, "trip_outcomes\\.csv")
if (is.na(outcomes_path)) {
  cat(
    "Hinweis: keine *trip_outcomes.csv in", output_dir, "gefunden - dieser Lauf enthaelt",
    "keinen Zusatzweg-Mechanismus (z. B. Branch feature/behaviour-module). Kein",
    "outcome_long.csv/trip_outcomes.png geschrieben.\n"
  )
} else {
  outcomes <- read.table(outcomes_path, sep = ";", header = TRUE, stringsAsFactors = FALSE)
  has_opt_out <- "optedOut" %in% names(outcomes)

  outcome_types <- c("inserted", if (has_opt_out) "optedOut", "skipped")
  outcome_long <- do.call(rbind, lapply(outcome_types, function(type) {
    data.frame(
      segment = outcomes$segment,
      outcome = type,
      count = outcomes[[type]],
      pct = outcomes[[paste0(type, "_pct")]],
      stringsAsFactors = FALSE
    )
  }))
  outcome_long <- outcome_long[order(match(outcome_long$segment, outcomes$segment), outcome_long$outcome), ]

  outcome_long_path <- file.path(output_dir, paste0(prefix, "outcome_long.csv"))
  write.table(outcome_long, outcome_long_path, sep = ";", row.names = FALSE, quote = FALSE)
  cat("OK:", outcome_long_path, "geschrieben (", nrow(outcome_long), "Zeilen).\n")

  outcome_matrix <- t(as.matrix(outcomes[, paste0(outcome_types, "_pct"), drop = FALSE]))
  rownames(outcome_matrix) <- outcome_types
  colnames(outcome_matrix) <- outcomes$segment

  palette_outcomes <- c(inserted = "#55A868", optedOut = "#C44E52", skipped = "#8C8C8C")[outcome_types]

  outcomes_png_path <- file.path(output_dir, paste0(prefix, "trip_outcomes.png"))
  png(outcomes_png_path, width = 1600, height = 1000, res = 150)
  op <- par(mar = c(10, 4, 4, 12), xpd = TRUE)
  bp <- barplot(outcome_matrix, col = palette_outcomes, las = 2, ylab = "Anteil der betrachteten Agenten",
                main = "Zusatzweg-Ergebnis je Segment", border = NA)
  # Prozentwerte je Segment/Ergebnis in die gestapelten Balken schreiben - nur ab 3%
  # Anteil (sonst zu wenig Platz im Segment, Beschriftung wird unleserlich).
  for (i in seq_len(ncol(outcome_matrix))) {
    cum <- cumsum(outcome_matrix[, i])
    prev <- c(0, head(cum, -1))
    mid <- (prev + cum) / 2
    vals <- outcome_matrix[, i]
    labels <- ifelse(vals >= 0.03, paste0(round(vals * 100, 1), "%"), "")
    text(bp[i], mid, labels, cex = 0.55)
  }
  legend("topright", inset = c(-0.20, 0), legend = outcome_types, fill = palette_outcomes, bty = "n", title = "Ergebnis")
  par(op)
  invisible(dev.off())
  cat("OK:", outcomes_png_path, "geschrieben.\n")
}
