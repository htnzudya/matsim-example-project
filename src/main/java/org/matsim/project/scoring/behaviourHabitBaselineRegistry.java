package org.matsim.project.scoring;

import java.util.concurrent.ConcurrentHashMap;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import com.google.inject.Singleton;

/**
 * Haelt den Traegheits-/Gewohnheits-Referenzmodus JEDES Weges ueber den
 * GESAMTEN Lauf hinweg fest (Auftraggeber-Vorgabe: der Habit-Term soll auf
 * die AUSGANGSLAGE wirken, nicht pro Iteration neu verglichen werden).
 *
 * OHNE diese Klasse wuerde behaviourUtilityEstimator fuer den Habit-Vergleich
 * DiscreteModeChoiceTrip.getInitialMode() direkt verwenden - das ist aber NUR
 * der Modus, den der Weg IM AKTUELL SELEKTIERTEN PLAN hat (TripListConverter
 * baut die DiscreteModeChoiceTrip-Objekte bei JEDER Replanning-Runde neu aus
 * dem gerade gueltigen Plan, siehe DMC-Quelltext) - nach einer erfolgreichen
 * Umplanung waere das also bereits der NEUE, nicht mehr der urspruengliche
 * Modus, und der Habit-Bonus wuerde effektiv "Traegheit gegenueber der letzten
 * Iteration" statt "Traegheit gegenueber der Ausgangslage" messen.
 *
 * Loesung: beim ERSTEN Kontakt mit einem (Person, Wegindex)-Paar wird dessen
 * initialMode dauerhaft eingefroren und bei JEDEM weiteren Aufruf
 * zurueckgegeben, unabhaengig davon, was der Weg zu diesem spaeteren
 * Zeitpunkt tatsaechlich im Plan traegt. Da vor der ALLERERSTEN
 * Umplanungs-Beruehrung eines Weges (irgendeine Iteration, in der die Person
 * zufaellig fuer DiscreteModeChoice statt KeepLastSelected gezogen wird)
 * niemals etwas an ihm veraendert wurde, ist der beim ersten Kontakt
 * beobachtete initialMode garantiert die echte Ausgangslage (Iteration 0,
 * inkl. eines ggf. bereits eingefuegten Kandidatenwegs - der wird vor
 * Iteration 0 fest eingefuegt und danach nie mehr durch diese Klasse
 * beruehrt).
 *
 * ALS SINGLETON GEBUNDEN (siehe behaviourDiscreteModeChoiceExtension) - das
 * ist hier BEWUSST anders als bei behaviourCachedTripEstimator: dort war
 * Singleton-Scope genau das Problem (unbegrenztes Wachstum ueber den
 * gesamten Lauf, siehe dortigen Klassen-Javadoc), hier ist ein ueber den
 * GESAMTEN Lauf stabiler, aber sehr kleiner Speicher (ein String je
 * tatsaechlichem Weg der Population, nicht je Kandidaten-Auswertung)
 * genau das gewuenschte Verhalten.
 */
@Singleton
public final class behaviourHabitBaselineRegistry {

    private final ConcurrentHashMap<String, String> baselineModeByTrip = new ConcurrentHashMap<>();

    /**
     * Liefert den eingefrorenen Ausgangslage-Modus fuer diesen Weg - beim
     * ersten Aufruf fuer ein gegebenes (personId, tripIndex)-Paar wird
     * currentInitialMode uebernommen und dauerhaft gespeichert, bei allen
     * weiteren Aufrufen bleibt der urspruenglich gespeicherte Wert
     * bestehen, auch wenn currentInitialMode sich inzwischen geaendert hat.
     */
    public String resolveBaseline(Id<Person> personId, int tripIndex, String currentInitialMode) {
        String key = personId.toString() + ':' + tripIndex;
        return baselineModeByTrip.computeIfAbsent(key, ignored -> currentInitialMode);
    }
}
