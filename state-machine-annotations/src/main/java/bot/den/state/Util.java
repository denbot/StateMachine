package bot.den.state;

public class Util {
    /**
     * @param input The input to adjust
     * @return The input with the first letter uppercase
     */
    public static String ucfirst(String input) {
        return input.substring(0, 1).toUpperCase() + input.substring(1);
    }
}
