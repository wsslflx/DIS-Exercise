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