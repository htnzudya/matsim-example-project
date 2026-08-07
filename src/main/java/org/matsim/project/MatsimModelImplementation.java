/* *********************************************************************** *
 * project: org.matsim.*												   *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2008 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */
package org.matsim.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.logging.log4j.core.tools.picocli.CommandLine;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigReader;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.population.PersonUtils;
import org.matsim.project.config.behaviourConfigGroup;
import org.matsim.project.model.agentProfile;
import org.matsim.project.module.behaviourModule;

/**
 * @author nagel
 *
 */
@CommandLine.Command( header = ":: MyScenario ::", version = "1.0")
public class MatsimModelImplementation extends MATSimApplication {

	public MatsimModelImplementation() {
		super();
	}

	public static void main(String[] args) {
		MATSimApplication.execute(MatsimModelImplementation.class, "--config", "scenarios/equil/config-2026.xml");
	}

	@Override
	protected Config prepareConfig(Config config) {

		config.controller().setOverwriteFileSetting( OverwriteFileSetting.deleteDirectoryIfExists );

		// equil nutzt "h" statt "home" als Heimataktivitaet - Szenario-spezifische
		// Abweichung vom Add-on-Default, siehe behaviourModule-Vertrag.
		behaviourConfigGroup cfg = behaviourConfigGroup.getOrCreate(config);
		cfg.setHomeActivityType("h");

		// Das komplette Verhaltensmodell-Add-on inkl. DMC-Verdrahtung (Config-Teil).
		behaviourModule.configureController(config);

		// Echte Koeffizienten nachladen - 8 Segmente + modeParams fuer CA/AV/PT/SAV,
		// so wie in scenarios/testszenario/config.xml gepflegt. Kein einziger
		// Zahlenwert hier im Code, siehe behaviourConfigGroup-Vertrag.
		new ConfigReader(config).readFile("scenarios/testszenario/config.xml");

		// ---

		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {

		// equil-Personen haben kein Segment-Attribut aus echten Erhebungsdaten (das
		// waere Aufgabe einer spaeteren Datenanbindung). Bis dahin eine gewichtete
		// Zufallsziehung nach den ECHTEN Cluster-Anteilen ("probability") aus
		// testszenario/config.xml - keine erfundenen Segmente, nur eine Zuordnung,
		// die im Testszenario noch fehlt.
		behaviourConfigGroup cfg = behaviourConfigGroup.getOrCreate(scenario.getConfig());
		Map<String, agentProfile> segments = cfg.buildSegments();

		List<String> segmentIds = new ArrayList<>(segments.keySet());
		double[] cumulative = new double[segmentIds.size()];
		double sum = 0.0;
		for (int i = 0; i < segmentIds.size(); i++) {
			sum += segments.get(segmentIds.get(i)).getProbability();
			cumulative[i] = sum;
		}

		// Fuehrerschein-/Fahrzeugzugangs-Attribute fehlen equil ebenso (Schritt 7,
		// behaviourModeAvailability). Auch hier keine erfundenen Verhaltensdaten,
		// nur ein plausibler, klar markierter Platzhalteranteil ohne Fuehrerschein/
		// ohne Fahrzeugzugang, damit die Constraint ueberhaupt etwas zu filtern hat.
		Random random = new Random(cfg.getRandomSeed());
		for (Person person : scenario.getPopulation().getPersons().values()) {
			double draw = random.nextDouble() * sum;
			int index = 0;
			while (index < cumulative.length - 1 && draw > cumulative[index]) {
				index++;
			}
			person.getAttributes().putAttribute(cfg.getSegmentAttribute(), segmentIds.get(index));

			if (random.nextDouble() < 0.15) {
				PersonUtils.setLicence(person, "no");
			}
			if (random.nextDouble() < 0.10) {
				PersonUtils.setCarAvail(person, "never");
			}
		}

		// Schritt 7 (Netzwerkrouting): AV/SAV auf denselben Links wie CA erlauben,
		// damit sie echtes, kongestionsabhaengiges Routing statt Teleport nutzen.
		behaviourModule.addNetworkModesToLinks(scenario.getNetwork());

		// equil nutzt die defaultVehicle-Quelle (kein <module name="vehicles">) -
		// hier ein No-Op, aber fuer Portabilitaet trotzdem aufgerufen.
		behaviourModule.addVehicleTypesForModes(scenario);

		// ---

	}

	@Override
	protected void prepareControler(Controler controler) {

		// Das komplette Verhaltensmodell-Add-on inkl. DMC-Verdrahtung (Controler-Teil).
		controler.addOverridingModule( new behaviourModule() );

//		controler.addOverridingModule( new OTFVisLiveModule() ) ;
//		controler.addOverridingModule( new SimWrapperModule() ) ;


		// ---
	}
}
