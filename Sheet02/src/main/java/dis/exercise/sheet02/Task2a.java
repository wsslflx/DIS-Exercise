package dis.exercise.sheet02;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;
import java.util.TreeSet;

public class Task2a {

    private static final String MONGO_URI =
            EnvConfig.get("MONGO_URI");

    public static void run() throws Exception {
        Set<String> soldCoffees = loadSoldCoffeeNames();
        Set<String> recipeCoffees = loadRecipeNames();

        System.out.println("Coffee names in sales (PostgreSQL):");
        soldCoffees.forEach(name -> System.out.println("  " + name));

        System.out.println("\nCoffee names in recipes (MongoDB):");
        recipeCoffees.forEach(name -> System.out.println("  " + name));

        Set<String> missing = new TreeSet<>(soldCoffees);
        missing.removeAll(recipeCoffees);

        System.out.println("\nSold but missing from recipes:");
        if (missing.isEmpty()) {
            System.out.println("  (none)");
        } else {
            missing.forEach(name -> System.out.println("  " + name));
        }
    }

    private static Set<String> loadSoldCoffeeNames() throws Exception {
        Set<String> names = new TreeSet<>();
        try (Connection conn = DBConfig.getDataSource().getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT DISTINCT coffee_name FROM coffee_sales ORDER BY coffee_name")) {
            while (rs.next()) {
                names.add(rs.getString("coffee_name"));
            }
        }
        return names;
    }

    private static Set<String> loadRecipeNames() {
        Set<String> names = new TreeSet<>();
        try (MongoClient client = MongoClients.create(MONGO_URI)) {
            MongoDatabase db = client.getDatabase("coffee");
            MongoCollection<Document> recipes = db.getCollection("recipes");
            for (Document doc : recipes.find()) {
                names.add(doc.getString("name"));
            }
        }
        return names;
    }
}
