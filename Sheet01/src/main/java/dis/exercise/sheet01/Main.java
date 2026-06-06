package dis.exercise.sheet01;

import dis.exercise.common.DBConfig;

public class Main {

    public static void main(String[] args) throws Exception {
        String task = args.length > 0 ? args[0] : "a";
        try {
            switch (task) {
                case "a"      -> TaskA.run();
                case "b"      -> TaskB.run();
                case "c"      -> TaskC.run();
                default       -> System.err.println("Unknown task: " + task + ". Use 'a', 'b', or 'c'.");
            }
        } finally {
            DBConfig.close();
        }
    }
}
