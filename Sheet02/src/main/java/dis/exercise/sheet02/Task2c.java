package dis.exercise.sheet02;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public class Task2c {

    private static final String MONGO_URI =
            EnvConfig.get("MONGO_URI");

    public static void run() throws Exception {

        // ── Step 1: Load recipe lookup ───────────────────────────
        // We need to know how many minutes each coffee takes to prepare.
        // Result: "Latte" -> 8, "Espresso" -> 3,...
        Map<String, Integer> prepTimeByName = loadRecipeTimes();
        System.out.println("Recipe prep times loaded: " + prepTimeByName);

        // ── Step 2: Query sales grouped by date and coffee name ───────────────
        // Instead of fetching every individual sale row, we let PostgreSQL do the
        // counting For each (date, coffee) pair we get the number of cups
        // sold that day, which we can multiply by the prep time in Step 3.
        //
        // coffee_sales schema after Sheet01 Task C normalization:
        //   id, cash_type, money, coffee_name, time, date_fk
        String sql = """
                SELECT date_fk, coffee_name, COUNT(*) AS cups_sold
                FROM coffee_sales
                GROUP BY date_fk, coffee_name
                ORDER BY date_fk
                """;

        // ── Step 3: Multiply cups × prep time per day
        Map<String, Integer> totalPrepTimePerDay = new TreeMap<>();

        try (Connection conn = DBConfig.getDataSource().getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                String date       = rs.getString("date_fk");
                String coffeeName = rs.getString("coffee_name");
                int    cupsSold   = rs.getInt("cups_sold");

                Integer prepTime = prepTimeByName.get(coffeeName);
                if (prepTime == null) {
                    System.out.println("Warning: no recipe found for '" + coffeeName + "', skipping.");
                    continue;
                }

                // Each cup sold requires one full preparation cups × minutes
                int minutesForThisCoffee = cupsSold * prepTime;
                totalPrepTimePerDay.merge(date, minutesForThisCoffee, Integer::sum);
            }
        }

        // ── Step 4: Print results
        System.out.printf("%n%-15s %s%n", "Date", "Total prep time (min)");
        System.out.println("-".repeat(38));
        totalPrepTimePerDay.forEach((date, minutes) ->
                System.out.printf("%-15s %d%n", date, minutes));
    }

    private static Map<String, Integer> loadRecipeTimes() {
        Map<String, Integer> map = new HashMap<>();
        try (MongoClient client = MongoClients.create(MONGO_URI)) {
            MongoDatabase db = client.getDatabase("coffee");
            MongoCollection<Document> recipes = db.getCollection("recipes");
            for (Document doc : recipes.find()) {
                map.put(doc.getString("name"), doc.getInteger("time_minutes"));
            }
        }
        return map;
    }
}
