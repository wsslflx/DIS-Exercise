package dis.exercise.sheet02;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;

import java.util.List;

public class Task2b {

    private static final String MONGO_URI =
            "mongodb+srv://DIS-User:REMOVED@cluster38018.soysy5p.mongodb.net/";

    public static void run() {
        Document cappuccino = new Document()
                .append("id", "cappuccino")
                .append("name", "Cappuccino")
                .append("category", "Espresso")
                .append("time_minutes", 161)
                .append("servings", 1)
                .append("ingredients", List.of(
                        new Document("item", "Espresso").append("amount", 2).append("unit", "shots"),
                        new Document("item", "Milk").append("amount", 150).append("unit", "ml")
                ))
                .append("equipment", List.of(
                        "Espresso machine", "Milk pitcher", "Steam wand"
                ))
                .append("steps", List.of(
                        "Brew 2 espresso shots into a large cup.",
                        "Steam milk to about 55C (creamy microfoam).",
                        "Pour steamed milk over the espresso, holding back a little foam, then finish with the remaining microfoam."
                ));

        Document espresso = new Document()
                .append("id", "espresso")
                .append("name", "Espresso")
                .append("category", "Espresso")
                .append("time_minutes", 3)
                .append("servings", 1)
                .append("ingredients", List.of(
                        new Document("item", "Ground coffee").append("amount", 7).append("unit", "g"),
                        new Document("item", "Water").append("amount", 30).append("unit", "ml")
                ))
                .append("equipment", List.of(
                        "Espresso machine", "Portafilter", "Coffee grinder"
                ))
                .append("steps", List.of(
                        "Grind coffee beans to a fine espresso grind.",
                        "Dose 7g of ground coffee into the portafilter and tamp firmly.",
                        "Lock the portafilter into the machine and brew for 25-30 seconds until you have ~30ml of espresso."
                ));

        // upsert: inserts the document if no match is found, replaces it if one is found.
        // This makes the method safe to run multiple times without creating duplicates.
        ReplaceOptions upsert = new ReplaceOptions().upsert(true);

        try (MongoClient client = MongoClients.create(MONGO_URI)) {
            MongoDatabase db = client.getDatabase("coffee");
            MongoCollection<Document> recipes = db.getCollection("recipes");

            upsertRecipe(recipes, cappuccino, upsert);
            upsertRecipe(recipes, espresso, upsert);
        }
    }

    private static void upsertRecipe(MongoCollection<Document> recipes, Document recipe, ReplaceOptions options) {
        Document filter = new Document("name", recipe.getString("name"));
        UpdateResult result = recipes.replaceOne(filter, recipe, options);
        if (result.getUpsertedId() != null) {
            System.out.println("Inserted: " + recipe.getString("name"));
        } else {
            System.out.println("Updated:  " + recipe.getString("name"));
        }
    }
}
