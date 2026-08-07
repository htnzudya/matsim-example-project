package org.matsim.project;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.logging.log4j.core.tools.picocli.CommandLine;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigReader;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.project.config.behaviourConfigGroup;
import org.matsim.project.model.agentProfile;
import org.matsim.project.module.behaviourModule;

/**
 * MINIMALER TEST auf einem echten Netzwerk (Oberlausitz/Dresden, aus dem
 * MATSim-Open-Szenario matsim-scenarios/matsim-oberlausitz-dresden): echtes
 * Strassennetz, echter OEPNV-Fahrplan (kein Teleport), 10pct-Population.
 * Netzwerk/Plaene/Fahrplan/Fahrzeuge werden per HTTPS direkt vom oeffentlichen
 * VSP-SVN geladen (siehe scenarios/oberlausitz-dresden/config.xml) - kein
 * lokaler Download noetig. Bewusst NICHT produktionsreif:
 *
 *   - DiscreteModeChoiceConfigurator.configureAsModeChoiceInTheLoop(config)
 *     raeumt ALLE Replanning-Strategien global weg, auch die der Subpopulation
 *     "longDistanceFreight". Statt das subpopulationsbewusst zu loesen, wird
 *     diese Subpopulation hier einfach aus der Population entfernt (deren
 *     Fahrzeuge nutzen ohnehin den Modus "longDistanceFreight", den unser
 *     alternatives-Enum nicht kennt - ohne Entfernen wuerde
 *     behaviourUtilityEstimator beim ersten beruehrten Agenten mit
 *     IllegalArgumentException abbrechen).
 *   - Segment-Zuordnung weiterhin per gewichteter Zufallsziehung (Schritt 5),
 *     nicht aus einer echten Mobilitaetstypologie-Erhebung fuer Oberlausitz/
 *     Dresden.
 *   - Lizenz-Attribut existiert in den Oberlausitz/Dresden-Daten nicht, nur
 *     carAvail - CarModeAvailability behandelt fehlende Lizenz als
 *     "vorhanden" (Default).
 */
@CommandLine.Command(header = ":: OberlausitzDresdenTest ::", version = "1.0")
public class RunOberlausitzDresdenTest extends MATSimApplication {

	public RunOberlausitzDresdenTest() {
		super();
	}

	public static void main(String[] args) {
		MATSimApplication.execute(RunOberlausitzDresdenTest.class, "--config", "scenarios/oberlausitz-dresden/config.xml");
	}

	@Override
	protected Config prepareConfig(Config config) {

		config.controller().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setLastIteration(10);

		// Oberlausitz/Dresden nutzt bereits "home" als Heimataktivitaet - kein
		// Override noetig (anders als equil mit "h").
		behaviourModule.configureController(config);

		new ConfigReader(config).readFile("scenarios/testszenario/config.xml");

		return config;
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
					String type = activity.getType();
					int underscore = type.lastIndexOf('_');
					double typicalDurationSeconds = 12 * 3600.0;
					if (underscore >= 0) {
						try {
							typicalDurationSeconds = Double.parseDouble(type.substring(underscore + 1));
						} catch (NumberFormatException e) {
							// kein numerischer Suffix - Default beibehalten
						}
					}
					scoringConfig.addActivityParams(
							new ScoringConfigGroup.ActivityParams(type).setTypicalDuration(typicalDurationSeconds));
				}
			}
		}

		// Segment-Zuordnung wie bei equil/Kelheim (Schritt 5): gewichtete
		// Zufallsziehung nach den echten Cluster-Anteilen aus
		// testszenario/config.xml. Die echten carAvail-Attribute der Personen
		// bleiben unangetastet.
		behaviourConfigGroup cfg = behaviourConfigGroup.getOrCreate(scenario.getConfig());
		Map<String, agentProfile> segments = cfg.buildSegments();

		List<String> segmentIds = new ArrayList<>(segments.keySet());
		double[] cumulative = new double[segmentIds.size()];
		double sum = 0.0;
		for (int i = 0; i < segmentIds.size(); i++) {
			sum += segments.get(segmentIds.get(i)).getProbability();
			cumulative[i] = sum;
		}

		Random random = new Random(cfg.getRandomSeed());
		for (Person person : scenario.getPopulation().getPersons().values()) {
			double draw = random.nextDouble() * sum;
			int index = 0;
			while (index < cumulative.length - 1 && draw > cumulative[index]) {
				index++;
			}
			person.getAttributes().putAttribute(cfg.getSegmentAttribute(), segmentIds.get(index));
		}

		// Schritt 7 (Netzwerkrouting): AV/SAV auf denselben Links wie CA erlauben,
		// damit sie echtes, kongestionsabhaengiges Routing auf dem echten
		// Strassennetz statt Teleport nutzen.
		behaviourModule.addNetworkModesToLinks(scenario.getNetwork());

		// Oberlausitz/Dresden nutzt vehiclesSource=modeVehicleTypesFromVehiclesData
		// - QSim braucht dafuer einen registrierten VehicleType je Hauptmodus,
		// sonst "Could not find requested vehicle type = av".
		behaviourModule.addVehicleTypesForModes(scenario);
	}

	@Override
	protected void prepareControler(Controler controler) {
		controler.addOverridingModule(new behaviourModule());
	}
}