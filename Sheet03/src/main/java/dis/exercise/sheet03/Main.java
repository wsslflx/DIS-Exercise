package dis.exercise.sheet03;

import java.io.PrintWriter;
import java.nio.file.*;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.*;

public class Main {

    private static void printUsage() {
        System.out.println("""
                Usage: java -jar sheet03.jar [options]

                  --alpha   <0-1>   Level smoothing factor     (default: 0.3)
                  --beta    <0-1>   Trend smoothing factor     (default: 0.1)
                  --gamma   <0-1>   Seasonal smoothing factor  (default: 0.2)
                  --horizon <int>   Days to forecast ahead     (default: 14)
                  --eval    <int>   Days to hold out for eval  (default: 14)
                  --out     <dir>   Output directory for CSVs  (default: .)
                """);
    }

    public static void main(String[] args) throws Exception {
        double alpha    = 0.3;
        double beta     = 0.1;
        double gamma    = 0.2;
        int    horizon  = 14;
        int    evalDays = 14;
        String outDir   = ".";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--help"    -> { printUsage(); return; }
                case "--alpha"   -> alpha    = Double.parseDouble(args[++i]);
                case "--beta"    -> beta     = Double.parseDouble(args[++i]);
                case "--gamma"   -> gamma    = Double.parseDouble(args[++i]);
                case "--horizon" -> horizon  = Integer.parseInt(args[++i]);
                case "--eval"    -> evalDays = Integer.parseInt(args[++i]);
                case "--out"     -> outDir   = args[++i];
                default          -> System.err.println("Unknown option: " + args[i]);
            }
        }

        System.out.printf("Parameters: alpha=%.2f  beta=%.2f  gamma=%.2f  horizon=%d  eval=%d%n",
                alpha, beta, gamma, horizon, evalDays);

        // ── Step 1: Load daily drink counts from PostgreSQL ────────────────────
        DataLoader.DailyCounts data;
        try (Connection conn = DBConfig.getDataSource().getConnection()) {
            data = DataLoader.load(conn);
        }

        List<LocalDate>       dates       = data.dates();
        Map<String, double[]> drinkSeries = data.drinkSeries();
        int n = dates.size();

        System.out.printf("Loaded %d days (%s → %s), %d drink types.%n",
                n, dates.get(0), dates.get(n - 1), drinkSeries.size());
        System.out.println("Drinks: " + drinkSeries.keySet());

        // ── Step 2: Run TES for every drink, collect forecasts in memory ───────
        int trainEnd = n - evalDays;
        Map<String, double[]> evalForecasts   = new LinkedHashMap<>();
        Map<String, double[]> futureForecasts = new LinkedHashMap<>();

        for (Map.Entry<String, double[]> entry : drinkSeries.entrySet()) {
            String   drink = entry.getKey();
            double[] full  = entry.getValue();

            if (trainEnd >= 7) {
                double[] train = Arrays.copyOf(full, trainEnd);
                evalForecasts.put(drink,
                        HoltWinters.forecast(train, alpha, beta, gamma, 7, evalDays));
            } else {
                System.out.printf("  [eval]   Skipping '%s': only %d training days.%n", drink, trainEnd);
            }

            if (n >= 7) {
                futureForecasts.put(drink,
                        HoltWinters.forecast(full, alpha, beta, gamma, 7, horizon));
            } else {
                System.out.printf("  [future] Skipping '%s': only %d total days.%n", drink, n);
            }
        }

        // ── Step 3: Write CSVs for Python visualization ────────────────────────
        Files.createDirectories(Path.of(outDir));
        Path evalPath   = Path.of(outDir, "eval.csv");
        Path futurePath = Path.of(outDir, "future.csv");

        writeEvalCsv(evalPath, dates, drinkSeries, evalForecasts, trainEnd);
        writeFutureCsv(futurePath, dates, drinkSeries, futureForecasts, horizon);

        System.out.println("\nCSVs written:");
        System.out.println("  " + evalPath.toAbsolutePath());
        System.out.println("  " + futurePath.toAbsolutePath());

        // ── Step 4: Map forecasts to ingredients and persist to PostgreSQL ─────
        System.out.println("\nRunning ingredient mapping (Phase 2)...");
        try (Connection conn = DBConfig.getDataSource().getConnection()) {
            IngredientMapper.run(conn, dates, drinkSeries,
                    evalForecasts, futureForecasts, trainEnd, horizon);
        } finally {
            DBConfig.close();
        }

        System.out.println("\nDone. Query ingredient_forecast in PostgreSQL for ingredient plots.");
    }

    // ── CSV helpers ────────────────────────────────────────────────────────────

    private static void writeEvalCsv(Path path,
                                     List<LocalDate> dates,
                                     Map<String, double[]> drinkSeries,
                                     Map<String, double[]> evalForecasts,
                                     int trainEnd) throws Exception {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(path))) {
            w.println("date,drink,actual,eval_forecast");
            for (Map.Entry<String, double[]> e : drinkSeries.entrySet()) {
                String   drink = e.getKey();
                double[] full  = e.getValue();
                double[] fc    = evalForecasts.get(drink);
                for (int i = 0; i < dates.size(); i++) {
                    String forecastCol = (fc != null && i >= trainEnd)
                            ? String.format("%.4f", fc[i - trainEnd])
                            : "";
                    w.printf("%s,%s,%.0f,%s%n", dates.get(i), drink, full[i], forecastCol);
                }
            }
        }
    }

    private static void writeFutureCsv(Path path,
                                       List<LocalDate> dates,
                                       Map<String, double[]> drinkSeries,
                                       Map<String, double[]> futureForecasts,
                                       int horizon) throws Exception {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(path))) {
            w.println("date,drink,actual,future_forecast");
            for (Map.Entry<String, double[]> e : drinkSeries.entrySet()) {
                String   drink = e.getKey();
                double[] full  = e.getValue();
                double[] fc    = futureForecasts.get(drink);

                // Historical rows
                for (int i = 0; i < dates.size(); i++) {
                    w.printf("%s,%s,%.0f,%n", dates.get(i), drink, full[i]);
                }
                // Future rows
                if (fc != null) {
                    LocalDate last = dates.get(dates.size() - 1);
                    for (int h = 1; h <= horizon; h++) {
                        w.printf("%s,%s,,%.4f%n", last.plusDays(h), drink, fc[h - 1]);
                    }
                }
            }
        }
    }
}
