package dis.exercise.sheet02;

public class Main {

    public static void main(String[] args) throws Exception {
        String task = args.length > 0 ? args[0] : "c";
        try {
            switch (task) {
                case "a"  -> Task2a.run();
                case "b"  -> Task2b.run();
                case "c"  -> Task2c.run();
                case "d"  -> Task1d.run();
                default   -> System.err.println("Unknown task: " + task + ". Use 'a', 'b', 'c', or 'd'.");
            }
        } finally {
            DBConfig.close();
        }
    }
}
