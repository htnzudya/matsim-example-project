# Digitaler Anhang zur Bachelorarbeit

Titel:      Modellierung menschlichen Verkehrsverhalten im Kontext
automatisierter und vernetzter Mobilität
Autor:      Meier Henry, Bergische Universität Wuppertal, TMDT
Abgabe:     01.09.2026

## Referenzierter Stand
Repository:   https://github.com/htnzudya/matsim-example-project
Branch:       main
Tag:          v1.0-abgabe (zeigt auf genau diesen, den finalen Stand inkl. dieser Datei)
Commit:       siehe Tag - exakter SHA via `git rev-parse v1.0-abgabe`
Commit-Datum: 2026-09-01
Permalink:    https://github.com/htnzudya/matsim-example-project/tree/v1.0-abgabe

## Inhalt
```
src/main/java/org/matsim/project/model/    Framework-freier Modellkern (Nutzenfunktion,
                                            Alternativen/Modi, Segment-/Personenprofile,
                                            Einkommens-/Trip-Kontext)
src/main/java/org/matsim/project/module/   MATSim-Anbindung (Nullalternative/Kandidatenweg-
                                            Mechanismus, DRT-Flottenmodule, ASC-/ascNull-
                                            Kalibrierung, DCM-
                                            Erweiterung)
src/main/java/org/matsim/project/config/   MATSim-ConfigGroup (verhaltensmodell-Modul,
                                            alle Studien-/Kalibrierungsparameter)
src/main/java/org/matsim/project/scoring/  Scoring-/TripContext-Hilfsklassen
src/main/java/org/matsim/project/          Einstiegspunkte (RunOberlausitzDresdenTest.java
                                            u. a.)
src/test/                                  Unit-Tests
scenarios/testszenario/                    Verhaltensmodell-Konfiguration (Nutzenfunktions-
                                            parameter: ASC/Beta/Gamma je Modus, Segmentierung)
                                            inkl. parameteruebersicht.xlsx (Referenz-
                                            Parameterübersicht)
scenarios/oberlausitz-dresden/             Szenariokonfiguration des Fallbeispiels
                                            (Population, Netz, Iterationen, DRT-Flotten)
scenarios/equil/                           MATSim-Standard-Testszenario (Framework-Beispiel,
                                            nicht Teil der eigenen Auswertung)
output/                                    Ausgewertete Ergebnisse/Diagramme dieser Arbeit
                                            (Rohdaten der MATSim-Läufe NICHT enthalten,
                                            siehe .gitignore - zu groß fürs Repository)
```

## Reproduktion
Voraussetzungen: JDK 26+, Maven 3.9+ (Maven Wrapper `./mvnw` ist im Repo enthalten).

```
git clone https://github.com/htnzudya/matsim-example-project.git
cd matsim-example-project
git checkout v1.0-abgabe
./mvnw -q test
./mvnw -q exec:java -Dexec.mainClass="org.matsim.project.RunOberlausitzDresdenTest"
```

Laufzeit ca. 7 h auf 6-Kern-CPU, 32 GB RAM.
Eingangsdaten: projektinterne Segmentierungs-/AVM-Akzeptanzstudie (siehe
scenarios/testszenario/parameteruebersicht.xlsx); keine gesonderte Lizenz vorhanden.

Für einen reinen Kompilier-/Test-Check genügt `./mvnw -q test`; der volle Simulationslauf
(`RunOberlausitzDresdenTest`) schreibt seine Ergebnisse nach `output/` (git-ignoriert) und
benötigt je nach Populationsgröße/Iterationszahl aus `scenarios/oberlausitz-dresden/config.xml`
mehrere Stunden Rechenzeit.

## Verwendete Software
Dieses Projekt basiert auf dem Open-Source-Verkehrssimulationsframework **MATSim**
(Multi-Agent Transport Simulation). Zitation gemäß Vorgabe des MATSim-Projekts:

> Horni, A., K. Nagel and K.W. Axhausen (eds.) 2016. *The Multi-Agent Transport
> Simulation MATSim*. London: Ubiquity Press.

Weitere Informationen: https://www.matsim.org

## Lizenz
- **MATSim-Programmcode** (`src/`, `*.java`): GPL-2.0, siehe LICENSE-Datei im Repository-Root.
- **MATSim-Input-/Output-Dateien** (`scenarios/`, `output/`): Creative Commons Attribution 4.0
  International (CC BY 4.0).
- Details siehe README.md im Repository-Root.
