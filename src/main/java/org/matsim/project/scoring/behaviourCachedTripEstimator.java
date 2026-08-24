package org.matsim.project.scoring;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripEstimator;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;

import com.google.inject.Inject;

/**
 * Threadsicherer Ersatz fuer DMCs eingebautes Caching (DiscreteModeChoiceConfigGroup.
 * setCachedModes(...) -&gt; CachedTripEstimator): dessen Cache ist eine einfache
 * java.util.HashMap je Modus, aber AbstractMultithreadedModule ruft estimateTrip(...)
 * aus allen numberOfThreads Replanning-Threads GLEICHZEITIG auf demselben
 * (Singleton-)TripEstimator auf - nebenlaeufige computeIfAbsent-Aufrufe auf einer
 * einfachen HashMap koennen deren interne Baumstruktur korrumpieren. Beobachtet als
 * Endlosschleife in HashMap$TreeNode.find (ein Replanning-Thread haengt dort fest,
 * die anderen sind laengst fertig, kein Fortschritt mehr moeglich, keine Exception) -
 * deshalb wurde setCachedModes(...) zunaechst ersatzlos entfernt.
 *
 * OHNE Caching wird aber jede Kandidaten-Modenkette, die DiscreteModeChoiceAlgorithm
 * fuer eine Tour durchprobiert, bei jeder erneuten Betrachtung DERSELBEN Person/
 * Modus/Fahrt-Kombination komplett neu geroutet (SwissRailRaptor/SpeedyALT auf dem
 * echten, grossen Strassen-/OEPNV-Netz) - das macht die Replanning-Phase praktisch
 * unbenutzbar langsam (beobachtet: alle Threads gleichzeitig minutenlang in echtem,
 * aber redundantem Routing, kaum Fortschritt).
 *
 * Diese Klasse loest beides: sie umhuellt behaviourUtilityEstimator (unveraendert)
 * mit einer java.util.concurrent.ConcurrentHashMap - deren computeIfAbsent(...) ist
 * PRO SCHLUESSEL atomar (ein JDK-garantiertes, gut getestetes Verhalten, anders als
 * die vorherige einfache HashMap), blockiert konkurrierende Threads fuer denselben
 * Schluessel statt die Struktur zu korrumpieren, UND spart die teure Delegation
 * (samt Routing) bei einem Cache-Treffer komplett ein.
 *
 * Cache-Schluessel (mode, trip, roundedDepartureTime) - identisch zu DMCs eigenem
 * CachedTripEstimator: DiscreteModeChoiceTrip ueberschreibt hashCode() aber NICHT
 * equals() (siehe Bytecode-Pruefung), verhaelt sich also wie Objektidentitaet -
 * TourBasedModel erzeugt je Person/Tagesplan-Position EIN eigenes Trip-Objekt, nie
 * ueber Personen hinweg geteilt. Der Schluessel braucht deshalb KEIN explizites
 * Personen-Feld - Objektidentitaet des Trips grenzt implizit bereits auf genau
 * diese eine Person ein. roundedDepartureTime (Math.ceil, wie im Original) faengt
 * den Fall ab, dass TourBasedModel.chooseModes(...) dieselbe Trip-Instanz mit
 * unterschiedlicher Abfahrtszeit wiederverwendet (z. B. bei verschobenen
 * vorherigen Aktivitaeten in unterschiedlichen Modenketten-Kandidaten).
 *
 * WICHTIG - Speicherbegrenzung durch Leerung bei Personenwechsel: getPlanAlgoInstance()
 * in AbstractMultithreadedModule wird nur EINMAL PRO THREAD aufgerufen (nicht pro
 * Person), und ModelModule/EstimatorModule binden die ganze DMC-Modellkette bewusst
 * OHNE @Singleton - dieselbe behaviourCachedTripEstimator-Instanz verarbeitet also
 * viele Personen NACHEINANDER innerhalb einer Iteration. Da Cache-Treffer wegen der
 * Objektidentitaet des Schluessels ohnehin nie ueber Personen hinweg auftreten koennen,
 * ist jeder Eintrag einer vorherigen Person ab dem Personenwechsel toter Ballast. Ohne
 * Leerung wuchs der Cache dadurch unbegrenzt ueber den gesamten Personen-Anteil einer
 * Thread-Iteration (beobachtet: OutOfMemoryError bereits in Iteration 1 auf dem echten
 * Netz, auch mit 10 GB Heap) - estimateTrip(...) leert ihn deshalb bei jedem erkannten
 * Personenwechsel.
 */
public final class behaviourCachedTripEstimator implements TripEstimator {

    private final behaviourUtilityEstimator delegate;
    private final ConcurrentHashMap<cacheKey, TripCandidate> cache = new ConcurrentHashMap<>();
    private volatile Id<Person> lastPersonId;

    @Inject
    public behaviourCachedTripEstimator(behaviourUtilityEstimator delegate) {
        this.delegate = delegate;
    }

    @Override
    public TripCandidate estimateTrip(Person person, String mode, DiscreteModeChoiceTrip trip,
            List<TripCandidate> previousTrips) {
        Id<Person> personId = person.getId();
        if (!personId.equals(lastPersonId)) {
            // Trip-Objektidentitaet ist je Person eindeutig (siehe Klassen-Javadoc), ein
            // Cache-Eintrag einer ANDEREN Person kann hier also nie treffen - er ist ab
            // dem Personenwechsel garantiert totes Gewicht. Ohne diese Leerung waechst
            // der Cache unbegrenzt ueber den gesamten Personen-Anteil, den diese
            // TripEstimator-Instanz im Lauf ihrer Lebensdauer abarbeitet (ein Thread
            // verarbeitet ueblicherweise viele Personen nacheinander mit DERSELBEN
            // Instanz, siehe AbstractMultithreadedModule.PlanAlgoThread.run(...)) -
            // beobachtet als OutOfMemoryError bereits in Iteration 1 auf dem echten,
            // grossen Netz.
            cache.clear();
            lastPersonId = personId;
        }

        long roundedDepartureTime = (long) Math.ceil(trip.getDepartureTime());
        cacheKey key = new cacheKey(mode, trip, roundedDepartureTime);
        return cache.computeIfAbsent(key, k -> delegate.estimateTrip(person, mode, trip, previousTrips));
    }

    /**
     * trip geht per Objektidentitaet ein (siehe Klassen-Javadoc) - der von
     * DiscreteModeChoiceTrip selbst ueberschriebene hashCode() plus die record-
     * generierte equals()-Delegation (die mangels eigener equals()-Methode in
     * DiscreteModeChoiceTrip auf Object.equals(), also Referenzgleichheit,
     * zurueckfaellt) ergeben zusammen exakt das gewuenschte Verhalten.
     */
    private record cacheKey(String mode, DiscreteModeChoiceTrip trip, long roundedDepartureTime) {
    }
}
