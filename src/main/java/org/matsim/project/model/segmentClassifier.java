package org.matsim.project.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Naive-Bayes-Segmentklassifikator: ordnet jedem Agenten anhand fester,
 * publizierter Clusterzentren (Hauslbauer/Schade/Petzoldt 2022, Tab. 2-2)
 * GENAU EIN Segment zu - Gaussian/Bernoulli-Log-Likelihood je Cluster,
 * Alpha-Kalibrierung auf die publizierten Clusteranteile (SCHRITT 5), dann
 * eine EINMALIGE multinomiale Ziehung aus dem kalibrierten Posterior
 * (SCHRITT 6) statt argmax - argmax kollabiert die Population auf 3-4
 * Segmente, siehe Aufgabenstellung.
 *
 * Rein fachliche Berechnung, keine MATSim-Abhaengigkeit (siehe
 * RunOberlausitzDresdenTest fuer die Anbindung an echte Person-Attribute:
 * Extraktion der Rohwerte, Aufruf von classify(...), Schreiben des Ergebnisses
 * als Personen-Attribut "segment").
 */
public final class segmentClassifier {

    private segmentClassifier() {
    }

    /** SCHRITT 5: feste Anzahl Kalibrierungs-Iterationen, siehe Aufgabenstellung. */
    private static final int CALIBRATION_ITERATIONS = 20;

    /** SCHRITT 6: fester Seed fuer die EINMALIGE multinomiale Ziehung. */
    private static final long DRAW_SEED = 42L;

    /** Schutz gegen log(x/0) in SCHRITT 5, falls ein Cluster in piHat exakt 0 Masse traegt. */
    private static final double POSTERIOR_FLOOR = 1e-12;

    /** Ein Agent mit den in SCHRITT 1 benoetigten Rohwerten (vor z-Transformation). */
    public record agentCovariates(String personId, double age, boolean female, double hhSize,
                                   double hhIncome, double regioStaR17, int carAvailCode) {
    }

    /** Ein Clusterzentrum - eine Zeile der festen Clusterparameter-Tabelle. */
    public record clusterCenter(String segmentId, String name, double pi,
                                 double ageMu, double ageSd, double pFemale,
                                 double hhMu, double hhSd, double sesMu, double sesSd,
                                 double regMu, double regSd, double motMu, double motSd) {
    }

    /** Gewichte wA/wG/wH/wS/wR/wM und Temperatur T aus SCHRITT 3/4. */
    public record weights(double wAge, double wGender, double wHousehold, double wIncome,
                           double wRegion, double wMotorisation, double temperature) {

        /** wM=0 per Default, siehe Aufgabenstellungs-HINWEIS (Motorisierung ist Haushalts-, carAvail Personenebene). */
        public static weights defaults() {
            return new weights(1.0, 1.0, 1.0, 1.0, 1.0, 0.0, 1.0);
        }
    }

    /** Ergebnis je Agent: gezogenes Segment + volles kalibriertes Posterior (SCHRITT 6/Output p_1..p_8). */
    public record assignment(String personId, String segmentId, Map<String, Double> posterior) {

        public double maxPosterior() {
            return posterior.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        }
    }

    /** Muss ausgegeben werden, siehe Aufgabenstellung VALIDIERUNG. */
    public record validation(Map<String, Double> realizedShare, Map<String, Double> targetShare,
                              double meanMaxPosterior, double populationMeanAge, double populationAgeSd,
                              double populationFemaleShare, boolean allSegmentsOccupied) {
    }

    public record result(List<assignment> assignments, validation validationReport) {
    }

    /**
     * SCHRITT 1-6. clusters bestimmt die Segmentreihenfolge, insbesondere den
     * Referenzcluster fuer die Alpha-Normierung (alpha -= alpha[0], SCHRITT 5)
     * - clusters.get(0) muss deshalb konsistent mit der Zeile s=1 der
     * publizierten Tabelle sein (in der Config: das zuerst deklarierte
     * segmentParams-Set).
     *
     * Sortiert agentsIn intern nach personId, damit die Reihenfolge der
     * multinomialen Ziehungen (SCHRITT 6, EIN gemeinsamer Random-Strom) trotz
     * fixem Seed unabhaengig von der Iterationsreihenfolge der aufrufenden
     * MATSim-Population reproduzierbar ist.
     */
    public static result classify(List<agentCovariates> agentsIn, List<clusterCenter> clusters, weights w) {
        if (clusters.isEmpty()) {
            throw new IllegalArgumentException("Mindestens ein Cluster wird benoetigt.");
        }

        List<agentCovariates> agents = new ArrayList<>(agentsIn);
        agents.sort(Comparator.comparing(agentCovariates::personId));

        int n = agents.size();
        int k = clusters.size();

        // SCHRITT 2: z-Transformation ueber die GESAMTE Population (nicht je Cluster).
        // age bleibt in Rohjahren (siehe SCHRITT 3-Kommentar in der Aufgabenstellung).
        double[] zHh = zScores(agents.stream().mapToDouble(agentCovariates::hhSize).toArray());
        double[] zSes = zScores(agents.stream().mapToDouble(agentCovariates::hhIncome).toArray());
        double[] zReg = zScores(agents.stream().mapToDouble(a -> urbanitaet(a.regioStaR17())).toArray());
        double[] zMot = zScores(agents.stream().mapToDouble(a -> (double) a.carAvailCode()).toArray());

        // SCHRITT 3: Log-Likelihood je Agent x Cluster.
        double[] logPi = new double[k];
        for (int s = 0; s < k; s++) {
            logPi[s] = Math.log(clusters.get(s).pi());
        }
        double[][] logLikelihood = new double[n][k];
        for (int i = 0; i < n; i++) {
            agentCovariates a = agents.get(i);
            double g = a.female() ? 1.0 : 0.0;
            for (int s = 0; s < k; s++) {
                clusterCenter c = clusters.get(s);
                logLikelihood[i][s] = w.wAge() * lg(a.age(), c.ageMu(), c.ageSd())
                        + w.wGender() * (g * Math.log(c.pFemale()) + (1 - g) * Math.log(1 - c.pFemale()))
                        + w.wHousehold() * lg(zHh[i], c.hhMu(), c.hhSd())
                        + w.wIncome() * lg(zSes[i], c.sesMu(), c.sesSd())
                        + w.wRegion() * lg(zReg[i], c.regMu(), c.regSd())
                        + w.wMotorisation() * lg(zMot[i], c.motMu(), c.motSd());
            }
        }

        // SCHRITT 4 (Start alpha=0) + SCHRITT 5 (Kalibrierung, 20 Iterationen).
        double[] alpha = new double[k];
        double[] targetPi = clusters.stream().mapToDouble(clusterCenter::pi).toArray();
        double[][] posterior = computePosterior(logLikelihood, logPi, alpha, w.temperature());
        for (int iter = 0; iter < CALIBRATION_ITERATIONS; iter++) {
            double[] piHat = columnMeans(posterior);
            for (int s = 0; s < k; s++) {
                alpha[s] += Math.log(targetPi[s] / Math.max(piHat[s], POSTERIOR_FLOOR));
            }
            double reference = alpha[0];
            for (int s = 0; s < k; s++) {
                alpha[s] -= reference;
            }
            posterior = computePosterior(logLikelihood, logPi, alpha, w.temperature());
        }

        // SCHRITT 6: EINMALIGE multinomiale Ziehung aus einem gemeinsamen Random-Strom - NICHT argmax.
        Random rng = new Random(DRAW_SEED);
        List<assignment> assignments = new ArrayList<>(n);
        Map<String, Integer> realizedCounts = new LinkedHashMap<>();
        for (clusterCenter c : clusters) {
            realizedCounts.put(c.segmentId(), 0);
        }
        double maxPosteriorSum = 0.0;
        for (int i = 0; i < n; i++) {
            double draw = rng.nextDouble();
            double cumulative = 0.0;
            int chosen = k - 1;
            for (int s = 0; s < k; s++) {
                cumulative += posterior[i][s];
                if (draw <= cumulative) {
                    chosen = s;
                    break;
                }
            }

            Map<String, Double> posteriorById = new LinkedHashMap<>();
            double maxP = 0.0;
            for (int s = 0; s < k; s++) {
                posteriorById.put(clusters.get(s).segmentId(), posterior[i][s]);
                maxP = Math.max(maxP, posterior[i][s]);
            }
            maxPosteriorSum += maxP;

            assignments.add(new assignment(agents.get(i).personId(), clusters.get(chosen).segmentId(), posteriorById));
            realizedCounts.merge(clusters.get(chosen).segmentId(), 1, Integer::sum);
        }

        Map<String, Double> realizedShare = new LinkedHashMap<>();
        Map<String, Double> targetShare = new LinkedHashMap<>();
        boolean allOccupied = true;
        for (clusterCenter c : clusters) {
            int count = realizedCounts.get(c.segmentId());
            realizedShare.put(c.segmentId(), n == 0 ? 0.0 : count / (double) n);
            targetShare.put(c.segmentId(), c.pi());
            allOccupied &= count > 0;
        }

        double[] ages = agents.stream().mapToDouble(agentCovariates::age).toArray();
        double meanAge = mean(ages);
        double sdAge = sd(ages, meanAge);
        double femaleShare = agents.stream().mapToDouble(a -> a.female() ? 1.0 : 0.0).average().orElse(0.0);

        validation validationReport = new validation(realizedShare, targetShare,
                n == 0 ? 0.0 : maxPosteriorSum / n, meanAge, sdAge, femaleShare, allOccupied);

        return new result(assignments, validationReport);
    }

    /** SCHRITT 4: log(pi_s) + alpha_s + l[n,s]/T, log-sum-exp-stabil. */
    private static double[][] computePosterior(double[][] logLikelihood, double[] logPi, double[] alpha, double temperature) {
        int n = logLikelihood.length;
        int k = logPi.length;
        double[][] posterior = new double[n][k];
        for (int i = 0; i < n; i++) {
            double[] score = new double[k];
            double max = Double.NEGATIVE_INFINITY;
            for (int s = 0; s < k; s++) {
                score[s] = logPi[s] + alpha[s] + logLikelihood[i][s] / temperature;
                max = Math.max(max, score[s]);
            }
            double sumExp = 0.0;
            for (int s = 0; s < k; s++) {
                sumExp += Math.exp(score[s] - max);
            }
            for (int s = 0; s < k; s++) {
                posterior[i][s] = Math.exp(score[s] - max) / sumExp;
            }
        }
        return posterior;
    }

    private static double[] columnMeans(double[][] matrix) {
        int n = matrix.length;
        int k = matrix[0].length;
        double[] means = new double[k];
        for (double[] row : matrix) {
            for (int s = 0; s < k; s++) {
                means[s] += row[s];
            }
        }
        for (int s = 0; s < k; s++) {
            means[s] /= n;
        }
        return means;
    }

    private static double lg(double x, double mu, double sd) {
        return -Math.log(sd) - (x - mu) * (x - mu) / (2 * sd * sd);
    }

    private static double[] zScores(double[] values) {
        double m = mean(values);
        double s = sd(values, m);
        double[] z = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            z[i] = s < 1e-9 ? 0.0 : (values[i] - m) / s;
        }
        return z;
    }

    private static double mean(double[] values) {
        if (values.length == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    private static double sd(double[] values, double mean) {
        if (values.length == 0) {
            return 0.0;
        }
        double sumSq = 0.0;
        for (double v : values) {
            sumSq += (v - mean) * (v - mean);
        }
        return Math.sqrt(sumSq / values.length);
    }

    /**
     * Monotone Transformation des RegioStaR17-Codes zu einem Urbanitaets-Score
     * fuer SCHRITT 1 (xR) - hoch = Metropole, niedrig = laendlich.
     *
     * RegioStaR17 (BBSR-Raumtypologie) ist ein 3-stelliger Code, dessen Ziffern
     * - in fallender Prioritaet (100er/10er/1er-Stelle) - je fuer sich den Grad
     * an Urbanitaet kodieren: 1. Ziffer Stadtregion(1)/Laendliche Region(2),
     * 2. Ziffer Metropole-/Regiopole-Tier, 3. Ziffer Lagetyp (Kernstadt=1 bis
     * duenn besiedeltes Umland=5). In allen drei Stellen bedeutet eine
     * KLEINERE Ziffer "urbaner" - so auch in den 17 tatsaechlich in den
     * Oberlausitz/Dresden-Daten beobachteten Werten (111-225, output_persons.
     * csv-Spalte homeRegioStaR17): 1xx/Stadtregion durchgehend kleiner als
     * 2xx/Laendliche Region, xx1 durchgehend kleiner als xx5 innerhalb
     * derselben Gruppe. Da alle drei Stellen dieselbe Richtung teilen, ist die
     * Negation des vollen Codes bereits eine korrekte monotone lexikografische
     * Rangfunktion - keine Digit-Zerlegung noetig.
     */
    private static double urbanitaet(double regioStaR17Code) {
        return -regioStaR17Code;
    }
}
