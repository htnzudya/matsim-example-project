package org.matsim.project.model;

/**
 * Einkommensklasse fuer die Kostensensitivitaet (beta_Kosten), siehe
 * modeParams-Klassen-Javadoc und fertigeabmparameter.xlsx (Zeilen 13-15:
 * "beta_Kosten (mittleres/hohes/niedriges Einkommen)").
 *
 * hhIncome in der VSP-Population ist eine 10-stufige kategoriale
 * Haushaltseinkommensklasse (1-10, KEIN EUR-Betrag, siehe fromHhIncome-
 * Javadoc), keine kontinuierliche Groesse - die Zuordnung auf NIEDRIG/
 * MITTEL/HOCH erfolgt daher ueber zwei Schwellenwerte
 * (behaviourConfigGroup.einkommenSchwelleNiedrigMax/-HochMin), nicht ueber
 * eine feste Formel.
 */
public enum incomeTier {
    NIEDRIG, MITTEL, HOCH;

    /**
     * Ordnet eine hhIncome-Klasse (1-10) anhand zweier Schwellenwerte einer
     * Einkommenstufe zu: hhIncome &lt;= schwelleNiedrigMax -&gt; NIEDRIG,
     * hhIncome &gt;= schwelleHochMin -&gt; HOCH, sonst MITTEL.
     *
     * Default-Schwellenwerte (siehe behaviourConfigGroup) 3/8: an der realen
     * Verteilung der Oberlausitz/Dresden-Population orientiert (10pct-Lauf,
     * 112.952 Agenten) - Klassen 1-3 zusammen ~10,4%, Klassen 8-10 zusammen
     * ~14,5%, Klassen 4-7 (Mehrheit, ~75,1%) als "mittel". Kein amtlicher
     * MiD/SrV-Schwellenwert, sondern eine an der Verteilung orientierte
     * Einordnung - bei Bedarf ueber die Config anpassbar.
     */
    public static incomeTier fromHhIncome(double hhIncome, double schwelleNiedrigMax, double schwelleHochMin) {
        if (hhIncome <= schwelleNiedrigMax) {
            return NIEDRIG;
        }
        if (hhIncome >= schwelleHochMin) {
            return HOCH;
        }
        return MITTEL;
    }
}
