package org.matsim.project;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.tools.picocli.CommandLine;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.application.MATSimApplication;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpQSimComponents;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigReader;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.project.config.behaviourConfigGroup;
import org.matsim.project.model.segmentClassifier;
import org.matsim.project.module.behaviourModule;

/**
 * MINIMALER TEST auf einem echten Netzwerk (Oberlausitz/Dresden, aus dem
 * MATSim-Open-Szenario matsim-scenarios/matsim-oberlausitz-dresden): echtes
 * Strassennetz, echter OEPNV-Fahrplan (kein Teleport), 10pct-Population.
 * Netzwerk/Plaene/Fahrplan/Fahrzeuge werden per HTTPS direkt vom oeffentlichen
 * VSP-SVN geladen (siehe scenarios/oberlausitz-dresden/config.xml) - kein
 * lokaler Download noetig. Bewusst NICHT produktionsreif:
 *
 *   - DiscreteModeChoiceConfigurator.configureAsModeChoiceInTheL
 *     raeumt ALLE Replanning-Strategien global weg, auch die der Subpopulation
 *     "longDistanceFreight". Statt das subpopulationsbewusst zu loesen, wird
 *     diese Subpopulation hier einfach aus der Population entfernt (deren
 *     Fahrzeuge nutzen ohnehin den Modus "longDistanceFreight", den unser
 *     alternatives-Enum nicht kennt - ohne Entfernen wuerde
 *     behaviourUtilityEstimator beim ersten beruehrten Agenten mit
 *     IllegalArgumentException abbrechen).
 *   - Segment-Zuordnung per Naive-Bayes-Klassifikator (segmentClassifier) auf
 *     Basis publizierter Clusterzentren (Hauslbauer/Schade/Petzoldt 2022,
 *     siehe dortigen Klassen-Javadoc) gegen die echten Personenattribute
 *     (age/sex/hhSize/hhIncome/homeRegioStaR17/carAvail) - keine eigene
 *     Mobilitaetstypologie-Erhebung fuer Oberlausitz/Dresden, sondern eine
 *     uebertragene, fremde Typologie.
 *   - Lizenz-Attribut existiert in den Oberlausitz/Dresden-Daten nicht, nur
 *     carAvail - CarModeAvailability behandelt fehlende Lizenz als
 *     "vorhanden" (Default).
 *   - PSAV/SSAV sind seit Schritt 8 echte DRT-Flotten (matsim-contrib "drt"/
 *     "dvrp", siehe behaviourModule.prepareDrtFleets) mit Dispatch,
 *     Warteschlangen und Leerfahrten (Rebalancing) statt reiner Netzwerk-Modi.
 *     Seit Schritt 9 mit korrigierten Betriebskonzepten (siehe alternatives.
 *     java): PSAV = gepoolt mit virtuellen Haltestellen (operationalScheme=
 *     stopbased), SSAV = Door-to-Door-Robotaxi ohne Pooling (capacity=1).
 *     Flottengroesse/-kapazitaet sind Platzhalter (siehe PSAV_FLEET_SIZE etc.
 *     unten), nicht aus einer Bedarfsanalyse kalibriert.
 */
@CommandLine.Command(header = ":: OberlausitzDresdenTest ::", version = "1.0")
public class RunOberlausitzDresdenTest extends MATSimApplication {

	private static final Logger log = LogManager.getLogger(RunOberlausitzDresdenTest.class);

	// Schritt 8/9 (SAV-Flotte): Flottengroesse/-kapazitaet je Modus. PLATZHALTER,
	// nicht aus einer Bedarfsanalyse kalibriert (grobe Groessenordnung: ~94.500
	// simulierte Personen, PSAV/SSAV-Modusanteil bislang je ~7-9% der Wege -
	// siehe modestats.csv frueherer Laeufe). Siehe alternatives.java fuer die
	// (in Schritt 9 korrigierten) Betriebskonzepte:
	//   PSAV = gepoolt MIT virtuellen Haltestellen (wie MOIA) - mehrere
	//          Fahrgaeste gleichzeitig an Bord, daher weniger Fahrzeuge noetig
	//          fuer dieselbe Nachfrage.
	//   SSAV = Door-to-Door-Robotaxi OHNE Pooling (capacity=1) - jede Fahrt
	//          bindet ein ganzes Fahrzeug exklusiv, daher deutlich mehr
	//          Fahrzeuge noetig fuer dieselbe Nachfrage.
	private static final int PSAV_FLEET_SIZE = 600;
	private static final int PSAV_CAPACITY = 6;
	private static final int PSAV_STOP_COUNT = 3000;
	private static final int SSAV_FLEET_SIZE = 1500;
	private static final int SSAV_CAPACITY = 1;

	// Schritt 12: PSAV/SSAV-Bediengebiet als Bounding Box um die tatsaechlichen
	// Wohnstandorte der (ggf. gesampleten) Population, plus Puffer. Grund:
	// Oberlausitz/Dresdens Netz deckt eine Flaeche von ~640 km x 840 km ab -
	// weit groesser als ein plausibles SAV-Bediengebiet. Ohne Einschraenkung
	// bekommen PSAV/SSAV JEDEN car-Link, das macht useModeFilteredSubnetwork
	// (siehe multiModeDrt-Config) wirkungslos (das "gefilterte" Subnetz ist
	// dann genauso gross wie das volle Netz) und laesst DVRPs Fahrzeit-Matrix
	// (Sparse-Node-Matrix, skaliert mit der Knotenzahl DIESES Subnetzes, nicht
	// mit der Zonengroesse) auf dem vollen ~250.000-Knoten-Netz rechnen - das
	// war der dominante Kostentreiber, nicht die Zonierung (cellSize).
	//
	// PERZENTILE statt Min/Max: gegen die echte 10pct-Population geprueft, die
	// 0./100. Perzentile der Wohnkoordinaten sind extreme Ausreisser (ein
	// Sprung von "p1" nach "p0" ueber hunderte km) - vermutlich vereinzelte
	// Fernpendler oder Datenartefakte am Rand des Netzes, keine repraesentative
	// Bediengebietsgrenze. p1..p99 der Wohnkoordinaten liegen dagegen eng
	// beieinander (~136 km x 79 km) - das ist die tatsaechliche Kernregion.
	private static final double SERVICE_AREA_PERCENTILE_LOW = 1.0;
	private static final double SERVICE_AREA_PERCENTILE_HIGH = 99.0;
	private static final double SERVICE_AREA_BUFFER_METERS = 15_000.0;

	public RunOberlausitzDresdenTest() {
		super();
	}

	public static void main(String[] args) {
		MATSimApplication.execute(RunOberlausitzDresdenTest.class, "--config", "scenarios/oberlausitz-dresden/config.xml");
	}

	@Override
	protected Config prepareConfig(Config config) {

		config.controller().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);

		// Schritt 16 (Bugfix/Kalibrierung, HISTORISCH): 9 Iterationen reichten nicht
		// annaehernd zur Konvergenz - modestats.csv zeigte den AV-Anteil noch klar
		// steigend (kein Plateau) und scorestats.csv einen monoton FALLENDEN
		// Durchschnittsscore ueber alle 9 Iterationen (82.6 -> 65.2). Grund fuer den
		// fallenden Score: maxAgentPlanMemorySize=1 (kein "beste Alternative merken"),
		// ein Agent kann durch reines Zufallspech (Gumbel-Rauschen der 20%-
		// DiscreteModeChoice-Ziehung) in eine schlechtere Alternative rutschen, ohne
		// dass das je zurueckgedreht wird - kein LOS-getriebenes Gleichgewicht,
		// sondern ein Drift. Fuer echte Konvergenz braucht es deutlich mehr
		// Iterationen (bei 1pct-Population kein Problem: ~3 Min/Iteration nach dem
		// einmaligen ~10-Min-DVRP-Setup, siehe stopwatch.csv eines Testlaufs).
		//
		// KORREKTUR 2026-08-31: hier stand vorher ein hartcodiertes
		// config.controller().setLastIteration(40) - das ueberschrieb JEDEN
		// lastIteration-Wert aus config.xml stillschweigend, unabhaengig davon, was
		// dort konfiguriert war (der zugehoerige Kommentar sprach sogar von einem
		// voellig anderen Wert, 10 - Beleg, dass die beiden schon auseinandergelaufen
		// waren). lastIteration wird jetzt NICHT mehr hier gesetzt - config.xml
		// (controller.lastIteration) ist die alleinige Quelle, wie bei allen anderen
		// Parametern auch.

		// Oberlausitz/Dresden nutzt bereits "home" als Heimataktivitaet - kein
		// Override noetig (anders als equil mit "h").
		behaviourModule.configureController(config);

		// MUSS NACH behaviourModule.configureController(...) gesetzt werden:
		// DiscreteModeChoiceConfigurator.configureAsModeChoiceInTheLoop(...) (von
		// configureController(...) aufgerufen) setzt
		// fractionOfIterationsToDisableInnovation selbst hart auf
		// Double.POSITIVE_INFINITY - ein Aufruf VOR configureController(...) wuerde
		// von dort also wieder ueberschrieben (genau das ist output-1 passiert: der
		// Wert stand am Ende trotzdem auf Infinity). Die VSP-Konsistenzpruefung
		// hatte beide Werte von Anfang an bemaengelt (siehe Log), wir hatten es nur
		// an der falschen Stelle "behoben". Erst mit eingefrorener Innovation
		// (0.8) koennen die letzten ~20% der Iterationen ein echtes Gleichgewicht
		// ausschreiben statt eines Zufalls-Schnappschusses, und erst mit aktivem
		// fractionOfIterationsToStartScoreMSA (0.8) ist der ausgeschriebene Score
		// ein gleitender Mittelwert statt der letzten (verrauschten) Einzeliteration.
		config.replanning().setFractionOfIterationsToDisableInnovation(0.8);
		config.scoring().setFractionOfIterationsToStartScoreMSA(0.8);

		new ConfigReader(config).readFile("scenarios/testszenario/config.xml");

		// Schritt 15 (Bugfix): travelTimeMatrixCachePath (siehe oberlausitz-dresden/
		// config.xml) an die geladene Population koppeln - sonst wirft
		// FreeSpeedTravelTimeMatrix.<init> eine VerifyException ("numberOfZones ==
		// zoneSystem.getZones().size()"), sobald ein Cache-File einer FRUEHEREN
		// population.pct-Stufe (z. B. 10pct) fuer einen Lauf mit ANDERER Stufe
		// (z. B. 1pct) wiederverwendet wird: die dynamisch aus der Population
		// berechnete Bediengebiets-Bounding-Box (siehe computeServiceAreaBoundingBox)
		// aendert sich mit der Populationsgroesse, damit auch das PSAV/SSAV-Subnetz
		// und dessen Zonenzahl - der alte Cache passt dann nicht mehr zum aktuellen
		// ZoneSystem. Muss NACH dem ConfigReader-Merge oben laufen (der setzt
		// travelTimeMatrixCachePath erst).
		scopeTravelTimeMatrixCacheToPopulation(config);

		return config;
	}

	/** Siehe prepareConfig(...)-Kommentar zu travelTimeMatrixCachePath. */
	private static void scopeTravelTimeMatrixCacheToPopulation(Config config) {
		String populationSuffix = populationSuffix(config.plans().getInputFile());
		for (DrtConfigGroup drtConfig : MultiModeDrtConfigGroup.get(config).getModalElements()) {
			String cachePath = drtConfig.getTravelTimeMatrixCachePath();
			if (cachePath == null || cachePath.isBlank()) {
				continue;
			}
			int dot = cachePath.lastIndexOf('.');
			String scoped = dot < 0
					? cachePath + "-" + populationSuffix
					: cachePath.substring(0, dot) + "-" + populationSuffix + cachePath.substring(dot);
			drtConfig.setTravelTimeMatrixCachePath(scoped);
		}
	}

	/**
	 * Extrahiert die Populationsstufe (z. B. "1pct"/"10pct") aus dem VSP-
	 * Dateinamensmuster "...-<stufe>.plans-initial.xml.gz". Fallback (Hash des
	 * vollen Pfads) fuer Population-Dateien, die diesem Muster nicht folgen -
	 * degradiert dann sauber zu "jeder Populationsdatei ihr eigener Cache",
	 * statt eine falsche Stufe zu erraten.
	 */
	private static final Pattern POPULATION_PCT_PATTERN = Pattern.compile("-([0-9.]+pct)\\.");

	private static String populationSuffix(String inputPlansFile) {
		Matcher matcher = POPULATION_PCT_PATTERN.matcher(inputPlansFile);
		if (matcher.find()) {
			return matcher.group(1);
		}
		return "scope" + Integer.toHexString(inputPlansFile.hashCode());
	}

	@Override
	protected void prepareScenario(Scenario scenario) {

		// Subpopulation "longDistanceFreight" entfernen, siehe Klassen-Javadoc.
		List<org.matsim.api.core.v01.Id<Person>> toRemove = new ArrayList<>();
		for (Person person : scenario.getPopulation().getPersons().values()) {
			Object subpop = person.getAttributes().getAttribute("subpopulation");
			if (!"person".equals(subpop)) {
				toRemove.add(person.getId());
			}
		}
		toRemove.forEach(id -> scenario.getPopulation().removePerson(id));

		// configureAsModeChoiceInTheLoop(...) registriert DiscreteModeChoice/
		// KeepLastSelected OHNE Subpopulation (= gilt fuer die Standard-
		// Subpopulation "null"). Oberlausitz/Dresdens verbleibende Personen tragen
		// aber explizit subpopulation="person" - ohne das Attribut zu entfernen,
		// faende MATSims StrategyManager fuer sie gar keine passende Strategie
		// ("No strategy found ... Current subpopulation = person"). Fuer den
		// Minimaltest reicht es, alle auf die Standard-Subpopulation zu setzen.
		for (Person person : scenario.getPopulation().getPersons().values()) {
			person.getAttributes().removeAttribute("subpopulation");
		}

		// Wie schon bei Kelheim kodieren die Aktivitaetstypen die typische Dauer
		// direkt im Namen (z. B. "home_600" = 600 Sekunden) - VSP-uebliche
		// Konvention, nicht Kelheim-spezifisch. Das mitgelieferte config.xml
		// bringt dafuer KEINE activityParams mit - ohne sie bricht MATSims
		// Kern-Scoring beim ersten unbekannten Aktivitaetstyp ab ("acttype ...
		// is not known in utility parameters"). Deshalb hier dynamisch aus der
		// Population registriert statt einzeln in XML gepflegt.
		ScoringConfigGroup scoringConfig = scenario.getConfig().scoring();
		Set<String> knownTypes = new HashSet<>();
		for (ScoringConfigGroup.ActivityParams existing : scoringConfig.getActivityParams()) {
			knownTypes.add(existing.getActivityType());
		}
		for (Person person : scenario.getPopulation().getPersons().values()) {
			for (PlanElement element : person.getSelectedPlan().getPlanElements()) {
				if (element instanceof Activity activity && knownTypes.add(activity.getType())) {
					double typicalDurationSeconds = behaviourModule.parseActivityType(activity.getType()).typicalDurationSeconds();
					scoringConfig.addActivityParams(
							new ScoringConfigGroup.ActivityParams(activity.getType()).setTypicalDuration(typicalDurationSeconds));
				}
			}
		}

		// Segment-Zuordnung per Naive-Bayes-Klassifikator (segmentClassifier):
		// echte Cluster-Zuordnung aus den Personenattributen (age/sex/hhSize/
		// hhIncome/homeRegioStaR17/carAvail) gegen die publizierten
		// Clusterzentren, statt einer reinen gewichteten Zufallsziehung. Siehe
		// segmentClassifier-Klassen-Javadoc fuer SCHRITT 1-6, assignSegments(...)
		// unten fuer die Anbindung an die Population.
		behaviourConfigGroup cfg = behaviourConfigGroup.getOrCreate(scenario.getConfig());
		assignSegments(scenario, cfg);

		// Bugfix (siehe behaviourModule.registerHomeActivityTypes-Javadoc): DMCs
		// ActivityTypeHomeFinder/ActivityTourFinder matchen NUR exakt gegen den
		// konfigurierten homeActivityType ("home") - Oberlausitz/Dresdens
		// tatsaechliche Aktivitaetstypen heissen aber "home_<dauer>" (VSP-
		// Konvention). Ohne diesen Aufruf findet DMC in KEINEM Trip eine
		// Heimataktivitaet und behandelt jeden Agententag als eine einzige Tour.
		behaviourModule.registerHomeActivityTypes(scenario);

		// Schritt 7 (Netzwerkrouting): AV auf denselben Links wie CA erlauben,
		// damit es echtes, kongestionsabhaengiges Routing auf dem echten
		// Strassennetz statt Teleport nutzt.
		behaviourModule.addNetworkModesToLinks(scenario.getNetwork());

		// Schritt 12: PSAV/SSAV nur innerhalb des Bediengebiets (siehe
		// SERVICE_AREA_BUFFER_METERS-Javadoc) statt auf dem vollen Netz -
		// sonst baut DVRP seine Fahrzeit-Matrix auf allen ~250.000 Knoten.
		double[] serviceArea = computeServiceAreaBoundingBox(scenario, SERVICE_AREA_BUFFER_METERS);
		behaviourModule.addServiceAreaModesToLinks(scenario.getNetwork(),
				serviceArea[0], serviceArea[1], serviceArea[2], serviceArea[3]);

		// Oberlausitz/Dresden nutzt vehiclesSource=modeVehicleTypesFromVehiclesData
		// - QSim braucht dafuer einen registrierten VehicleType je Hauptmodus,
		// sonst "Could not find requested vehicle type = av".
		behaviourModule.addVehicleTypesForModes(scenario);

		// Schritt 8 (SAV-Flotte): PSAV/SSAV als echte DRT-Flotten mit Dispatch,
		// Warteschlangen und Leerfahrten statt reiner Netzwerk-Modi. Muss NACH
		// addNetworkModesToLinks(...)/addServiceAreaModesToLinks(...) laufen
		// (siehe dortigen Javadoc).
		behaviourModule.prepareDrtFleets(scenario,
				PSAV_FLEET_SIZE, PSAV_CAPACITY, PSAV_STOP_COUNT, SSAV_FLEET_SIZE, SSAV_CAPACITY);
	}

	/**
	 * carAvail-Attributwert -> Motorisierungs-Code, siehe segmentClassifier-
	 * Klassen-Javadoc SCHRITT 1 (xM).
	 */
	private static int carAvailCode(Object carAvail, Person person) {
		String value = String.valueOf(carAvail);
		return switch (value) {
			case "never" -> 0;
			case "sometimes" -> 1;
			case "always" -> 2;
			default -> throw new IllegalArgumentException(
					"Unbekannter carAvail-Wert '" + value + "' fuer Person " + person.getId());
		};
	}

	/** Liest ein numerisches Personenattribut, egal ob es als Number oder als String geparst wurde. */
	private static double asDouble(Object value, Person person, String attributeName) {
		if (value instanceof Number number) {
			return number.doubleValue();
		}
		if (value != null) {
			return Double.parseDouble(value.toString());
		}
		throw new IllegalArgumentException("Person " + person.getId() + " hat kein Attribut '" + attributeName + "'.");
	}

	/**
	 * "other" ist in den VSP-Populationsdaten ein bekannter Sonderwert fuer
	 * Personen, deren Wohnort keinem der 111-225-RegioStaR17-Codes zugeordnet
	 * werden konnte (z. B. grenznahe Adressen ausserhalb Deutschlands - Oberlausitz
	 * grenzt an Polen/Tschechien) - kein Datenfehler. Betrifft in der 10pct-
	 * Population genau 1 von 113220 Personen (verifiziert per grep), in der
	 * 1pct-Stichprobe kam diese Person zufaellig nicht vor, deshalb ist der Fall
	 * dort nie aufgefallen. Ohne Zusatzinformation zur echten Urbanitaet wird auf
	 * den haeufigsten beobachteten Code (111, ~46% der Population) imputiert -
	 * bei n=1 nicht praezise herleitbar, aber der Effekt auf die
	 * Segment-Klassifikation der Gesamtpopulation ist vernachlaessigbar.
	 */
	private static final double REGIO_STA_R17_FALLBACK_CODE = 111.0;

	private static double regioStaR17Code(Object value, Person person) {
		if ("other".equals(value)) {
			log.warn("Person {} hat homeRegioStaR17='other' - impute mit Fallback-Code {}",
					person.getId(), REGIO_STA_R17_FALLBACK_CODE);
			return REGIO_STA_R17_FALLBACK_CODE;
		}
		return asDouble(value, person, "homeRegioStaR17");
	}

	private static segmentClassifier.agentCovariates extractCovariates(Person person) {
		var attrs = person.getAttributes();
		double age = asDouble(attrs.getAttribute("age"), person, "age");
		boolean female = "f".equals(attrs.getAttribute("sex"));
		double hhSize = asDouble(attrs.getAttribute("hhSize"), person, "hhSize");
		double hhIncome = asDouble(attrs.getAttribute("hhIncome"), person, "hhIncome");
		double regioStaR17 = regioStaR17Code(attrs.getAttribute("homeRegioStaR17"), person);
		int carAvail = carAvailCode(attrs.getAttribute("carAvail"), person);
		return new segmentClassifier.agentCovariates(person.getId().toString(), age, female, hhSize, hhIncome,
				regioStaR17, carAvail);
	}

	/**
	 * Segment-Zuordnung per Naive-Bayes-Klassifikator: extrahiert die
	 * Rohattribute aller Personen, ruft segmentClassifier.classify(...) (SCHRITT
	 * 1-6, siehe dortigen Klassen-Javadoc) auf, schreibt das gezogene Segment als
	 * Personenattribut (unveraendert "segment", wie zuvor bei der gewichteten
	 * Zufallsziehung - buildSegments()/resolveProfile bleiben dadurch
	 * unveraendert lauffaehig) sowie persons_segment.csv (personId, segment,
	 * p_1..p_8) ins Output-Verzeichnis, und loggt die geforderte Validierung.
	 */
	private static void assignSegments(Scenario scenario, behaviourConfigGroup cfg) {
		List<segmentClassifier.agentCovariates> covariates = new ArrayList<>();
		for (Person person : scenario.getPopulation().getPersons().values()) {
			covariates.add(extractCovariates(person));
		}

		List<segmentClassifier.clusterCenter> clusters = cfg.buildClusterCenters();
		segmentClassifier.result result = segmentClassifier.classify(covariates, clusters, cfg.buildClassifierWeights());

		java.util.Map<String, segmentClassifier.assignment> byPersonId = new java.util.HashMap<>();
		for (segmentClassifier.assignment assignment : result.assignments()) {
			byPersonId.put(assignment.personId(), assignment);
		}
		for (Person person : scenario.getPopulation().getPersons().values()) {
			segmentClassifier.assignment assignment = byPersonId.get(person.getId().toString());
			person.getAttributes().putAttribute(cfg.getSegmentAttribute(), assignment.segmentId());
		}

		writePersonsSegmentCsv(scenario.getConfig().controller().getOutputDirectory(), result, clusters);
		logValidation(result.validationReport());
	}

	private static void writePersonsSegmentCsv(String outputDirectory, segmentClassifier.result result,
			List<segmentClassifier.clusterCenter> clusters) {
		try {
			Path directory = Path.of(outputDirectory);
			Files.createDirectories(directory);
			Path csvPath = directory.resolve("persons_segment.csv");

			StringBuilder sb = new StringBuilder();
			sb.append("personId;segment");
			for (segmentClassifier.clusterCenter c : clusters) {
				sb.append(";p_").append(c.segmentId());
			}
			sb.append('\n');
			for (segmentClassifier.assignment assignment : result.assignments()) {
				sb.append(assignment.personId()).append(';').append(assignment.segmentId());
				for (segmentClassifier.clusterCenter c : clusters) {
					sb.append(';').append(String.format(Locale.ROOT, "%.6f", assignment.posterior().get(c.segmentId())));
				}
				sb.append('\n');
			}
			Files.writeString(csvPath, sb.toString(), StandardCharsets.UTF_8);
			log.info("Segment-Zuordnung: " + csvPath + " geschrieben (" + result.assignments().size() + " Personen).");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** SCHRITT "VALIDIERUNG (muss ausgegeben werden)" der Aufgabenstellung. */
	private static void logValidation(segmentClassifier.validation validation) {
		log.info("Segment-Zuordnung Validierung:");
		for (String segmentId : validation.targetShare().keySet()) {
			double realized = validation.realizedShare().get(segmentId);
			double target = validation.targetShare().get(segmentId);
			double deviationPp = (realized - target) * 100.0;
			log.info(String.format(Locale.ROOT, "  %-32s realisiert=%.4f  pi=%.4f  Abweichung=%.2fpp",
					segmentId, realized, target, deviationPp));
			if (Math.abs(deviationPp) >= 1.0) {
				log.warn(String.format(Locale.ROOT,
						"  -> Segment '%s' weicht um %.2f Prozentpunkte von pi ab (Vorgabe: < 1pp).",
						segmentId, deviationPp));
			}
		}
		log.info(String.format(Locale.ROOT, "  mittlere maximale Posteriorwahrscheinlichkeit (Trennschaerfe) = %.4f",
				validation.meanMaxPosterior()));
		log.info(String.format(Locale.ROOT,
				"  Populationsmittel age = %.2f (sd=%.2f, Referenz-Stichprobe 53.5/16.1), Frauenanteil = %.4f (Referenz 0.475)",
				validation.populationMeanAge(), validation.populationAgeSd(), validation.populationFemaleShare()));
		if (Math.abs(validation.populationMeanAge() - 53.5) > 5.0 || Math.abs(validation.populationFemaleShare() - 0.475) > 0.05) {
			log.warn("  -> age/Frauenanteil weichen deutlich von der Referenz-Stichprobe ab - siehe Aufgabenstellungs-HINWEIS "
					+ "(ggf. age ebenfalls z-transformieren statt Rohjahre zu verwenden).");
		}
		if (!validation.allSegmentsOccupied()) {
			log.warn("  -> nicht alle Segmente sind besetzt!");
		} else {
			log.info("  alle Segmente besetzt: ja");
		}
	}

	/**
	 * Bounding Box (minX, minY, maxX, maxY, Netz-Koordinaten) um das
	 * SERVICE_AREA_PERCENTILE_LOW..SERVICE_AREA_PERCENTILE_HIGH-Perzentil der
	 * Wohnstandorte ("home_x"/"home_y"-Personenattribute, in den Oberlausitz/
	 * Dresden-Daten vorhanden) aller Personen der (ggf. gesampleten) Population,
	 * plus gleichmaessigem Puffer - siehe SERVICE_AREA_BUFFER_METERS-Javadoc fuer
	 * die Begruendung (Perzentile statt Min/Max wegen Ausreissern). Datengetrieben
	 * statt fest codierter Koordinaten, damit sich das Bediengebiet automatisch an
	 * den jeweils geladenen Bevoelkerungsschnitt (0.1pct/1pct/10pct/...) anpasst.
	 */
	private static double[] computeServiceAreaBoundingBox(Scenario scenario, double bufferMeters) {
		List<Double> xs = new ArrayList<>();
		List<Double> ys = new ArrayList<>();

		for (Person person : scenario.getPopulation().getPersons().values()) {
			Object homeX = person.getAttributes().getAttribute("home_x");
			Object homeY = person.getAttributes().getAttribute("home_y");
			if (homeX instanceof Number x && homeY instanceof Number y) {
				xs.add(x.doubleValue());
				ys.add(y.doubleValue());
			}
		}

		if (xs.isEmpty()) {
			throw new IllegalStateException(
					"Keine Person mit home_x/home_y-Attributen gefunden - Bediengebiet kann nicht bestimmt werden.");
		}

		Collections.sort(xs);
		Collections.sort(ys);

		double minX = percentile(xs, SERVICE_AREA_PERCENTILE_LOW) - bufferMeters;
		double maxX = percentile(xs, SERVICE_AREA_PERCENTILE_HIGH) + bufferMeters;
		double minY = percentile(ys, SERVICE_AREA_PERCENTILE_LOW) - bufferMeters;
		double maxY = percentile(ys, SERVICE_AREA_PERCENTILE_HIGH) + bufferMeters;

		return new double[] { minX, minY, maxX, maxY };
	}

	private static double percentile(List<Double> sortedValues, double percentile) {
		int index = (int) Math.round(percentile / 100.0 * (sortedValues.size() - 1));
		return sortedValues.get(Math.max(0, Math.min(sortedValues.size() - 1, index)));
	}

	@Override
	protected void prepareControler(Controler controler) {
		controler.addOverridingModule(new behaviourModule());

		// Schritt 8 (SAV-Flotte): aktiviert die von MultiModeDrtModule gebundenen
		// QSim-Komponenten (Fahrzeuge, Dispatch, Passagier-Handling) fuer PSAV/
		// SSAV. Guice-Module (behaviourModule.install()) haben keinen Controler-
		// Zugriff, deshalb hier statt dort.
		controler.configureQSimComponents(
				DvrpQSimComponents.activateAllModes(MultiModeDrtConfigGroup.get(controler.getConfig())));

		// Wanduhr-Obergrenze fuer lange Laeufe (Auftraggeber-Vorgabe: max. 3 Tage
		// ODER lastIteration, je nachdem was zuerst eintritt) - siehe
		// wallClockOrIterationTerminationCriterion-Klassen-Javadoc: wird NUR
		// zwischen Iterationen geprueft, die laufende Iteration wird immer sauber
		// zu Ende gefuehrt. Ueber die Umgebungsvariable MAX_RUNTIME_SECONDS
		// ueberschreibbar (dieselbe Variable wie in
		// scripts/run-oberlausitz-dresden-with-timeout.sh, das als reines
		// Sicherheitsnetz fuer einen komplett haengenden Prozess danebensteht).
		long maxRuntimeSeconds = 3L * 24 * 60 * 60; // 3 Tage
		String override = System.getenv("MAX_RUNTIME_SECONDS");
		if (override != null && !override.isBlank()) {
			maxRuntimeSeconds = Long.parseLong(override.trim());
		}
		long deadlineEpochMillis = System.currentTimeMillis() + maxRuntimeSeconds * 1000L;
		controler.setTerminationCriterion(new wallClockOrIterationTerminationCriterion(
				controler.getConfig().controller().getLastIteration(), deadlineEpochMillis));
	}
}