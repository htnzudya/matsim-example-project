package org.matsim.project.model;

/**
 * Level-of-Service-Attribute einer konkreten Alternative fuer einen konkreten Weg.
 *
 * Diese Werte liefert im spaeteren Einsatz die Simulation (Router, Fahrplan,
 * Tarifmodell). Fuer Tests und Unit-Pruefungen werden sie direkt gesetzt.
 *
 * @param inVehicleTimeHours Im-Fahrzeug-Zeit in Stunden
 * @param waitTimeHours      Wartezeit in Stunden (z. B. Zugangs-/Umsteige-/Bereitstellungszeit)
 * @param costEuro           monetaere Kosten der Alternative in Euro
 * @param distanceKm         zurueckgelegte Distanz in Kilometern
 */
public record TripContext(double inVehicleTimeHours, double waitTimeHours, double costEuro, double distanceKm) {
}