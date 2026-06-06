package dis.exercise.sheet02;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.util.List;

public class Task1d {

    private static final String CONNECTION_STRING =
            "mongodb+srv://DIS-User:REMOVED@cluster38018.soysy5p.mongodb.net/";

    public static void run() {
        try (MongoClient client = MongoClients.create(CONNECTION_STRING)) {
            MongoDatabase db = client.getDatabase("coffee");
            MongoCollection<Document> recipes = db.getCollection("recipes");

            Document recipe = recipes.find(new Document("name", "Americano with Milk")).first();

            if (recipe == null) {
                System.out.println("Recipe 'Americano with Milk' not found.");
                return;
            }

            List<Document> ingredients = recipe.getList("ingredients", Document.class);
            System.out.println("Ingredients for 'Americano with Milk':");
            for (Document ingredient : ingredients) {
                System.out.printf("  - %s: %s %s%n",
                        ingredient.getString("item"),
                        ingredient.get("amount"),
                        ingredient.getString("unit"));
            }
        }
    }
}
