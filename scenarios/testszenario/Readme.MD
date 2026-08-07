# Verhaltensmodell-Add-on — Gerüst

Parametrisierbares Verhaltensmodell (DCM) als MATSim-Add-on.
Vier Alternativen: CA, AV, PT, SAV.

---

## Die Nutzenfunktion

```
V(i,j) = ASC_j
       + β_zeit_j      · Reisezeit(i,j)
       + β_kosten_j    · Kosten(i,j)
       + β_trägheit_j  · 1[vorheriger Modus = j]
       + Σ_k γ_jk      · X*_ik
```

| Block | Theoretische Säule |
|---|---|
| `ASC_j` | modusspezifische Grundpräferenz (AV-Akzeptanz) |
| `β_zeit`, `β_kosten` | klassische Level-of-Service-Terme (RUM / DCM) |
| `β_trägheit` | Habit Theory — Verhaltensträgheit |
| `γ_jk · X*_ik` | TPB (Einstellung, subjektive Norm) + Protection Motivation Theory (Risikowahrnehmung) |

---

## Zwei-Schichten-Parametrisierung

**Profilschicht** — Segment → latente Konstrukte X* (z-standardisiert)
Kommt aus der Mobilitätstypologie. Steht in `segmentParams`.

**Nutzenschicht** — Modus × Konstrukt → γ, plus ASC und β
Kommt aus der SLR-Extraktion. Steht in `modeParams`.

Beide Schichten sind unabhängig austauschbar. Im Java-Code steht **kein einziger Zahlenwert** — alles kommt aus der XML.

---

## Dateien

| Datei | Rolle | Status |
|---|---|---|
| `model/Alternative.java` | Enum CA/AV/PT/SAV | kompiliert ✓ |
| `model/AgentProfile.java` | Profilschicht: Segment + X* | kompiliert ✓ |
| `model/TripContext.java` | LOS-Attribute (Zeit, Kosten, Distanz) | kompiliert ✓ |
| `model/ModeParams.java` | Nutzenschicht: ASC, β (Mean + SD), γ | kompiliert ✓ |
| `model/VerhaltensUtilityFunction.java` | **KERN** — V(i,j), Logit, Logsum | kompiliert ✓ |
| `config/VerhaltensConfigGroup.java` | Parameter-Schnittstelle (liest XML) | MATSim-API prüfen |
| `module/VerhaltensModule.java` | Einhängepunkt (`addOverridingModule`) | MATSim-API prüfen |
| `scenarios/.../verhaltensmodell-parameter.xml` | Beispielparameter | — |
| `UtilityFunctionTest.java` | 8 Unit-Tests | 8/8 grün ✓ |
| `Demo.java` | Demolauf mit Testdaten | läuft ✓ |

Das Paket `model` hat **keine MATSim-Abhängigkeit**. Es ist der wissenschaftliche Kern und ohne Simulation testbar — genau das macht ihn als eigenständiges Ergebnis zitierfähig.

---

## Demolauf — Ergebnis mit Platzhalterwerten

Weg: 12 km, vorheriger Modus CA.

| Segment | P(CA) | P(AV) | P(PT) | P(SAV) |
|---|---|---|---|---|
| technikaffiner_autofahrer | 31,9 % | **57,9 %** | 0,3 % | 9,9 % |
| oepnv_orientiert_urban | **54,2 %** | 35,9 % | 1,2 % | 8,7 % |
| skeptischer_traditionalist | **91,7 %** | 5,8 % | 0,9 % | 1,5 % |

Dieselbe Nutzenfunktion, unterschiedliche Segmente → unterschiedliche Moduswahl. Das ist die ABM-Heterogenität.

---

## ⚠️ Alle Zahlen sind Platzhalter

Sämtliche Werte in `Demo.java` und in der XML sind **illustrative Platzhalter**, keine SLR-Koeffizienten. Sie zeigen nur, an welcher Stelle die extrahierten Werte eingetragen werden.

---

## Nächste Schritte

1. **JDK 21** in IntelliJ einstellen, `pom.xml` auf `release 21`.
2. Dateien ins Projekt kopieren (Paketstruktur beibehalten).
3. `UtilityFunctionTest` laufen lassen → muss 8/8 grün sein.
4. **Koeffizienten eintragen** — aus der SLR-Extraktionstabelle (Spalte „Modellrolle"). Das ist der eigentliche inhaltliche Kern.
5. MATSim-Anbindung: `ScoringFunctionFactory` oder `informed-mode-choice`-Estimator — gegen die 2026.0-API verifizieren.
6. Mini-Testszenario, das `addOverridingModule(new VerhaltensModule())` aufruft.

---

## Der Vertrag des Add-ons

**Erwartet vom Szenario:**
- Config-Block `<module name="verhaltensmodell">` mit allen Parametern
- Jede Person trägt ein Attribut `segment` mit ihrer Segment-ID
- Die vier Modi CA, AV, PT, SAV sind definiert

**Liefert:**
- Nutzenwerte V(i,j) je Agent und Alternative
- Wahlwahrscheinlichkeiten (Logit)
- Logsum (erwarteter Maximalnutzen — Basis für spätere Nested-Logit-Erweiterung)