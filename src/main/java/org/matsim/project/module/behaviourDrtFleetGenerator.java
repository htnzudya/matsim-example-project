package org.matsim.project.module;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Erzeugt eine DVRP-Fahrzeugflotte (Schritt 8: SAV-Flotte mit Leerfahrten) als
 * DTD-konforme XML-Datei ("dvrp_vehicles_v1.dtd"), aus der DRT-Depotpositionen
 * per echten, zufaellig gezogenen Links des uebergebenen (bereits geladenen)
 * Strassennetzes.
 *
 * Bewusst PROGRAMMATISCH statt als statische Datei gepflegt: Oberlausitz/
 * Dresden hat kein festes, kleines Netz mit bekannten Link-IDs (das Netz wird
 * je Lauf per HTTPS geladen) - eine von Hand gepflegte Flottendatei mit
 * geratenen Link-IDs waere entweder falsch (Link existiert nicht) oder
 * muesste bei jedem Netz-Update von Hand nachgezogen werden. Stattdessen wird
 * die Flotte in {@link behaviourModule#prepareDrtFleets} zur Laufzeit aus dem
 * tatsaechlich geladenen Network erzeugt.
 *
 * Deterministisch ueber den uebergebenen Random-Seed (Schritt 6 der
 * bestehenden Konvention: "frozen" Ziehung, reproduzierbar je Lauf).
 */
final class behaviourDrtFleetGenerator {

    private behaviourDrtFleetGenerator() {
    }

    /**
     * Schreibt eine Flotte von {@code fleetSize} Fahrzeugen mit Kapazitaet
     * {@code capacity} auf zufaellig gezogene, fuer {@code mode} freigegebene
     * Netzlinks (Depotpositionen), Betriebszeit [serviceBeginTime, serviceEndTime]
     * in Sekunden seit Mitternacht.
     */
    static void writeFleet(Network network, String mode, int fleetSize, int capacity,
                            double serviceBeginTime, double serviceEndTime,
                            long randomSeed, Path outputFile) {

        List<Id<Link>> candidateLinks = new ArrayList<>();
        for (Link link : network.getLinks().values()) {
            if (link.getAllowedModes().contains(mode)) {
                candidateLinks.add(link.getId());
            }
        }
        if (candidateLinks.isEmpty()) {
            throw new IllegalStateException(
                    "Kein Netzlink erlaubt Modus '" + mode + "' - behaviourModule.addNetworkModesToLinks(...) "
                            + "muss vor prepareDrtFleets(...) aufgerufen worden sein.");
        }

        Random random = new Random(randomSeed);
        try {
            Files.createDirectories(outputFile.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        try (Writer writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            writer.write("<?xml version=\"1.0\" ?>\n");
            writer.write("<!DOCTYPE vehicles SYSTEM \"http://matsim.org/files/dtd/dvrp_vehicles_v1.dtd\">\n\n");
            writer.write("<vehicles>\n");
            for (int i = 1; i <= fleetSize; i++) {
                Id<Link> startLink = candidateLinks.get(random.nextInt(candidateLinks.size()));
                writer.write(String.format(Locale.ROOT,
                        "\t<vehicle id=\"%s_veh_%d\" start_link=\"%s\" t_0=\"%.0f\" t_1=\"%.0f\" capacity=\"%d\"/>\n",
                        mode, i, startLink, serviceBeginTime, serviceEndTime, capacity));
            }
            writer.write("</vehicles>\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
