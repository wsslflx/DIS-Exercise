package dis.exercise.sheet03;

/**
 * Additive Triple Exponential Smoothing (Holt-Winters seasonal method).
 *
 * Update equations:
 *   Level:    l_t = α*(y_t - s_{t-L}) + (1-α)*(l_{t-1} + b_{t-1})
 *   Trend:    b_t = β*(l_t - l_{t-1}) + (1-β)*b_{t-1}
 *   Seasonal: s_t = γ*(y_t - l_t)     + (1-γ)*s_{t-L}
 *
 * Forecast h steps ahead from the end of the series:
 *   ŷ_{n+h} = l_n + h*b_n + s[(n+h-1) mod L]
 *
 * Naive initialization: level = mean of first season, trend = 0,
 * seasonal components = first-season values minus the level mean.
 */
public class HoltWinters {

    /**
     * @param series       observed values (e.g. daily drink counts, length >= seasonLength)
     * @param alpha        level smoothing (0 < alpha < 1)
     * @param beta         trend smoothing (0 < beta  < 1)
     * @param gamma        seasonal smoothing (0 < gamma < 1)
     * @param seasonLength periods per season, e.g. 7 for weekly
     * @param horizon      number of steps to forecast ahead
     * @return forecasted values for steps 1..horizon (length = horizon)
     */
    public static double[] forecast(double[] series, double alpha, double beta,
                                    double gamma, int seasonLength, int horizon) {
        int n = series.length;

        // initailisation
        double level = 0.0;
        for (int i = 0; i < seasonLength; i++) level += series[i];
        level /= seasonLength;

        double trend = 0.0;

        // Seasonal component buffer
        double[] s = new double[seasonLength];
        for (int i = 0; i < seasonLength; i++) s[i] = series[i] - level;

        // update equations
        for (int t = seasonLength; t < n; t++) {
            double prevLevel = level;
            double seasonal  = s[t % seasonLength];
            level = alpha * (series[t] - seasonal) + (1.0 - alpha) * (level + trend);
            trend = beta  * (level - prevLevel)     + (1.0 - beta)  * trend;
            s[t % seasonLength] = gamma * (series[t] - level) + (1.0 - gamma) * seasonal;
        }

        // forecast
        double[] result = new double[horizon];
        for (int h = 1; h <= horizon; h++) {
            double raw = level + h * trend + s[(n + h - 1) % seasonLength];
            result[h - 1] = Math.max(0.0, raw); // cups cannot be negative
        }
        return result;
    }
}
