package dis.exercise.sheet03;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.*;

public class IngredientMapper {

    private record IngredientAmount(String ingredient, double amount, String unit) {}

    /**
     * @param conn            PostgreSQL connection
     * @param dates           historical dates
     * @param drinkSeries     actual cups per drink per historical day
     * @param evalForecasts   forecasted cups for the held-out eval period (per drink)
     * @param futureForecasts forecasted cups for future days beyond the dataset (per drink)
     * @param trainEnd        index in dates where the eval period starts
     * @param horizon         number of future days forecasted
     */
    public static void run(Connection conn,
                           List<LocalDate> dates,
                           Map<String, double[]> drinkSeries,
                           Map<String, double[]> evalForecasts,
                           Map<String, double[]> futureForecasts,
                           int trainEnd,
                           int horizon) throws Exception {

        int n = dates.size();

        Map<String, List<IngredientAmount>> recipes = loadRecipes();
        System.out.println("  Recipes loaded for: " + recipes.keySet());

        // Collect all (ingredient -> unit) pairs for table structure
        Map<String, String> ingredientUnits = new LinkedHashMap<>();
        for (List<IngredientAmount> amounts : recipes.values()) {
            for (IngredientAmount ia : amounts) {
                ingredientUnits.putIfAbsent(ia.ingredient(), ia.unit());
            }
        }
        System.out.println("  Ingredients: " + ingredientUnits.keySet());

        try (Statement s = conn.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS ingredient_forecast (
                        date                   DATE         NOT NULL,
                        ingredient             VARCHAR(50)  NOT NULL,
                        unit                   VARCHAR(20)  NOT NULL,
                        actual_amount          NUMERIC(10,4),
                        eval_forecast_amount   NUMERIC(10,4),
                        future_forecast_amount NUMERIC(10,4),
                        PRIMARY KEY (date, ingredient)
                    )
                    """);
            conn.commit();
        }


        String insertSql = """
                INSERT INTO ingredient_forecast
                    (date, ingredient, unit, actual_amount, eval_forecast_amount, future_forecast_amount)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (date, ingredient) DO UPDATE SET
                    unit                   = EXCLUDED.unit,
                    actual_amount          = EXCLUDED.actual_amount,
                    eval_forecast_amount   = EXCLUDED.eval_forecast_amount,
                    future_forecast_amount = EXCLUDED.future_forecast_amount
                """;

        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {

            for (int i = 0; i < n; i++) {
                LocalDate date = dates.get(i);

                Map<String, Double> actual = ingredientTotals(drinkSeries, i, recipes);

                Map<String, Double> evalFc = (i >= trainEnd)
                        ? ingredientTotalsFromForecast(evalForecasts, i - trainEnd, recipes)
                        : Collections.emptyMap();

                for (Map.Entry<String, String> ing : ingredientUnits.entrySet()) {
                    String ingName = ing.getKey();
                    String unit    = ing.getValue();
                    ps.setDate(1, java.sql.Date.valueOf(date));
                    ps.setString(2, ingName);
                    ps.setString(3, unit);
                    setNullableDouble(ps, 4, actual.get(ingName));
                    setNullableDouble(ps, 5, evalFc.get(ingName));
                    ps.setNull(6, Types.NUMERIC);
                    ps.addBatch();
                }
            }

            LocalDate lastDate = dates.get(n - 1);
            for (int h = 1; h <= horizon; h++) {
                LocalDate futureDate = lastDate.plusDays(h);
                Map<String, Double> futureFc =
                        ingredientTotalsFromForecast(futureForecasts, h - 1, recipes);

                for (Map.Entry<String, String> ing : ingredientUnits.entrySet()) {
                    String ingName = ing.getKey();
                    String unit    = ing.getValue();
                    ps.setDate(1, java.sql.Date.valueOf(futureDate));
                    ps.setString(2, ingName);
                    ps.setString(3, unit);
                    ps.setNull(4, Types.NUMERIC); // no actual sales in the future
                    ps.setNull(5, Types.NUMERIC); // no eval forecast for future dates
                    setNullableDouble(ps, 6, futureFc.get(ingName));
                    ps.addBatch();
                }
            }

            ps.executeBatch();
            conn.commit();
        }

        System.out.printf("  ingredient_forecast populated: %d historical + %d future dates, %d ingredients.%n",
                n, horizon, ingredientUnits.size());
    }

    // helpers
    private static Map<String, Double> ingredientTotals(
            Map<String, double[]> drinkSeries,
            int dayIndex,
            Map<String, List<IngredientAmount>> recipes) {

        Map<String, Double> totals = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : drinkSeries.entrySet()) {
            String drink = e.getKey();
            double cups  = e.getValue()[dayIndex];
            addToTotals(totals, drink, cups, recipes);
        }
        return totals;
    }

    private static Map<String, Double> ingredientTotalsFromForecast(
            Map<String, double[]> forecasts,
            int stepIndex,
            Map<String, List<IngredientAmount>> recipes) {

        Map<String, Double> totals = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : forecasts.entrySet()) {
            String drink        = e.getKey();
            double forecastCups = e.getValue()[stepIndex];
            addToTotals(totals, drink, forecastCups, recipes);
        }
        return totals;
    }

    private static void addToTotals(Map<String, Double> totals,
                                    String drink,
                                    double cups,
                                    Map<String, List<IngredientAmount>> recipes) {
        List<IngredientAmount> recipeItems = recipes.get(drink);
        if (recipeItems == null) return;
        for (IngredientAmount ia : recipeItems) {
            totals.merge(ia.ingredient(), cups * ia.amount(), Double::sum);
        }
    }

    private static void setNullableDouble(PreparedStatement ps, int idx, Double value)
            throws SQLException {
        if (value == null) ps.setNull(idx, Types.NUMERIC);
        else               ps.setDouble(idx, value);
    }

    private static Map<String, List<IngredientAmount>> loadRecipes() {
        Map<String, List<IngredientAmount>> result = new LinkedHashMap<>();
        try (MongoClient client = MongoClients.create(EnvConfig.get("MONGO_URI"))) {
            MongoDatabase db = client.getDatabase("coffee");
            MongoCollection<Document> col = db.getCollection("recipes");
            for (Document doc : col.find()) {
                String drinkName = doc.getString("name");
                List<IngredientAmount> items = new ArrayList<>();
                List<Document> ingredients = doc.getList("ingredients", Document.class);
                if (ingredients != null) {
                    for (Document ing : ingredients) {
                        String rawItem = ing.getString("item");
                        String normalized = normalizeIngredient(rawItem);
                        double amount = ((Number) ing.get("amount")).doubleValue();
                        String unit   = ing.getString("unit");
                        items.add(new IngredientAmount(normalized, amount, unit));
                    }
                }
                result.put(drinkName, items);
            }
        }
        return result;
    }

    private static String normalizeIngredient(String item) {
        if (item.equalsIgnoreCase("Milk or water")) return "Milk";
        if (item.equalsIgnoreCase("Hot water")) return "Water";
        return item;
    }
}
