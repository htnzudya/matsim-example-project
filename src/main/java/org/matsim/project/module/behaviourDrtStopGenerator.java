package org.matsim.project.module;

import org.matsim.api.core.v01.Coord;
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
 * Erzeugt virtuelle Haltestellen fuer stopbased-DRT (Schritt 9: PSAV als
 * gepooltes MOIA-artiges System MIT virtuellen Haltestellen statt Door-to-
 * Door) als transitSchedule-Datei (nur &lt;transitStops&gt;, keine Linien -
 * exakt das Format, das DrtConfigGroup.transitStopFile erwartet).
 *
 * Bewusst PROGRAMMATISCH statt als statische Datei gepflegt, aus demselben
 * Grund wie {@link behaviourDrtFleetGenerator}: Oberlausitz/Dresden hat kein
 * festes Netz mit bekannten Link-IDs.
 *
 * Stop-Dichte ist ein PLATZHALTER (siehe stopCount-Parameter) - nicht aus
 * einer echten MOIA-Haltestellendichte kalibriert, nur so gewaehlt, dass die
 * Anzahl auf dem grossen Oberlausitz/Dresden-Netz (441k Links) nicht dieselbe
 * Speicherexplosion wie eine zu feine Zonierung verursacht (siehe Klassen-
 * Javadoc von behaviourDrtFleetGenerator fuer denselben Speicher-Hintergrund).
 */
final class behaviourDrtStopGenerator {

    private behaviourDrtStopGenerator() {
    }

    /**
     * Schreibt {@code stopCount} virtuelle Haltestellen auf zufaellig gezogene,
     * fuer {@code mode} freigegebene Netzlinks.
     */
    static void writeStops(Network network, String mode, int stopCount, long randomSeed, Path outputFile) {

        List<Link> candidateLinks = new ArrayList<>();
        for (Link link : network.getLinks().values()) {
            if (link.getAllowedModes().contains(mode)) {
                candidateLinks.add(link);
            }
        }
        if (candidateLinks.isEmpty()) {
            throw new IllegalStateException(
                    "Kein Netzlink erlaubt Modus '" + mode + "' - behaviourModule.addServiceAreaModesToLinks(...) "
                            + "muss vor der Haltestellengenerierung aufgerufen worden sein.");
        }

        Random random = new Random(randomSeed);
        try {
            Files.createDirectories(outputFile.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        try (Writer writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            writer.write("<!DOCTYPE transitSchedule SYSTEM \"http://www.matsim.org/files/dtd/transitSchedule_v2.dtd\">\n\n");
            writer.write("<transitSchedule>\n\n\t<transitStops>\n");
            for (int i = 1; i <= stopCount; i++) {
                Link link = candidateLinks.get(random.nextInt(candidateLinks.size()));
                Id<Link> linkId = link.getId();
                Coord coord = link.getCoord();
                writer.write(String.format(Locale.ROOT,
                        "\t\t<stopFacility id=\"%s_stop_%d\" x=\"%f\" y=\"%f\" linkRefId=\"%s\" isBlocking=\"false\"/>\n",
                        mode, i, coord.getX(), coord.getY(), linkId));
            }
            writer.write("\t</transitStops>\n\n</transitSchedule>\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
