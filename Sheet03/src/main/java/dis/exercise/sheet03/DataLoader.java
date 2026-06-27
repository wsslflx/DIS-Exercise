package dis.exercise.sheet03;

import java.sql.*;
import java.time.LocalDate;
import java.util.*;

public class DataLoader {

    public record DailyCounts(List<LocalDate> dates, Map<String, double[]> drinkSeries) {}

    /**
     * Queries PostgreSQL for daily drink counts and fills gaps (days with no
     * sales for a given drink) with 0, producing a contiguous time series per drink.
     */
    public static DailyCounts load(Connection conn) throws SQLException {
        // Raw counts keyed by date -> (drink -> cups)
        TreeMap<LocalDate, Map<String, Integer>> raw = new TreeMap<>();
        Set<String> drinks = new LinkedHashSet<>();

        String sql = """
                SELECT date_fk, coffee_name, COUNT(*) AS cups
                FROM coffee_sales
                GROUP BY date_fk, coffee_name
                ORDER BY date_fk
                """;

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                LocalDate date  = rs.getDate("date_fk").toLocalDate();
                String drink    = rs.getString("coffee_name");
                int    cups     = rs.getInt("cups");
                raw.computeIfAbsent(date, d -> new HashMap<>()).put(drink, cups);
                drinks.add(drink);
            }
        }
        conn.commit();

        if (raw.isEmpty()) throw new IllegalStateException("No sales data found in coffee_sales.");

        // Build a contiguous date list, filling missing days with 0
        LocalDate first = raw.firstKey();
        LocalDate last  = raw.lastKey();
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            dates.add(d);
        }

        int n = dates.size();
        Map<String, double[]> drinkSeries = new LinkedHashMap<>();
        for (String drink : drinks) {
            double[] series = new double[n];
            for (int i = 0; i < n; i++) {
                Map<String, Integer> dayMap = raw.get(dates.get(i));
                series[i] = (dayMap != null && dayMap.containsKey(drink))
                    ? dayMap.get(drink)
                    : 0.0;
            }
            drinkSeries.put(drink, series);
        }

        return new DailyCounts(dates, drinkSeries);
    }
}
