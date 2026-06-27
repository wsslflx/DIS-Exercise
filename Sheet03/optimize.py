import os
import re
import argparse
import numpy as np
import psycopg2
import pandas as pd


# ── TES (additive Holt-Winters) re-implemented in numpy for fast grid search ──

def holt_winters(series, alpha, beta, gamma, season_length, horizon):
    n = len(series)
    level = np.mean(series[:season_length])
    trend = 0.0
    s = series[:season_length] - level  # seasonal components, circular buffer

    for t in range(season_length, n):
        prev_level = level
        seasonal   = s[t % season_length]
        level      = alpha * (series[t] - seasonal) + (1 - alpha) * (level + trend)
        trend      = beta  * (level - prev_level)   + (1 - beta)  * trend
        s[t % season_length] = gamma * (series[t] - level) + (1 - gamma) * seasonal

    forecast = np.array([
        max(0.0, level + h * trend + s[(n + h - 1) % season_length])
        for h in range(1, horizon + 1)
    ])
    return forecast


def rmse(actual, predicted):
    return np.sqrt(np.mean((actual - predicted) ** 2))


# ── Data loading ───────────────────────────────────────────────────────────────

def read_env():
    d = os.path.abspath(os.path.dirname(__file__))
    for _ in range(4):
        p = os.path.join(d, '.env')
        if os.path.exists(p):
            env = {}
            for line in open(p):
                line = line.strip()
                if line and not line.startswith('#') and '=' in line:
                    k, v = line.split('=', 1)
                    env[k.strip()] = v.strip()
            return env
        d = os.path.dirname(d)
    raise FileNotFoundError('.env not found')


def load_series(eval_days):
    """Load daily drink counts from PostgreSQL, return dict of drink -> np.array."""
    env = read_env()
    m   = re.match(r'jdbc:postgresql://([^:/]+):(\d+)/(.+)', env['POSTGRES_URL'])
    host, port, dbname = m.groups()
    conn = psycopg2.connect(host=host, port=int(port), dbname=dbname,
                            user=env['POSTGRES_USER'],
                            password=env['POSTGRES_PASSWORD'])

    df = pd.read_sql("""
        SELECT date_fk, coffee_name, COUNT(*) AS cups
        FROM coffee_sales
        GROUP BY date_fk, coffee_name
        ORDER BY date_fk
    """, conn)
    conn.close()

    # Pivot to wide format: index=date, columns=drink
    df['date_fk'] = pd.to_datetime(df['date_fk'])
    pivot = df.pivot(index='date_fk', columns='coffee_name', values='cups')
    pivot = pivot.reindex(pd.date_range(pivot.index.min(), pivot.index.max(), freq='D'))
    pivot = pivot.fillna(0)

    series_dict = {}
    for drink in pivot.columns:
        full  = pivot[drink].values.astype(float)
        train = full[:-eval_days]
        actual_eval = full[-eval_days:]
        if len(train) >= 7:
            series_dict[drink] = (train, actual_eval)

    return series_dict


# ── Grid search ────────────────────────────────────────────────────────────────

def grid_search(series_dict, eval_days, season_length, step):
    values = np.round(np.arange(step, 1.0, step), 6)
    total  = len(values) ** 3
    print(f'Grid: {len(values)}³ = {total} combinations per drink\n')

    results = {}
    for drink, (train, actual) in series_dict.items():
        best_rmse   = np.inf
        best_params = None

        for alpha in values:
            for beta in values:
                for gamma in values:
                    try:
                        forecast = holt_winters(train.copy(), alpha, beta, gamma,
                                                season_length, eval_days)
                        error = rmse(actual, forecast)
                        if error < best_rmse:
                            best_rmse   = error
                            best_params = (alpha, beta, gamma)
                    except Exception:
                        continue

        results[drink] = (best_params, best_rmse)
        a, b, g = best_params
        print(f'  {drink:<25}  α={a:.2f}  β={b:.2f}  γ={g:.2f}  RMSE={best_rmse:.3f}')

    return results


def print_recommendation(results):
    print('\n── Recommended parameters (averaged across drinks) ──')
    params = np.array([p for p, _ in results.values()])
    mean_a, mean_b, mean_g = params.mean(axis=0)
    print(f'  α={mean_a:.2f}  β={mean_b:.2f}  γ={mean_g:.2f}')
    print(f'\nRun Java with:')
    print(f'  java -jar target/dis-exercise-sheet03-1.0-SNAPSHOT.jar '
          f'--alpha {mean_a:.2f} --beta {mean_b:.2f} --gamma {mean_g:.2f}')


# ── Entry point ────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description='Grid search for Holt-Winters parameters')
    parser.add_argument('--eval',          type=int,   default=14,
                        help='Held-out days used as validation set (default: 14)')
    parser.add_argument('--season-length', type=int,   default=7,
                        help='Season length (default: 7)')
    parser.add_argument('--step',          type=float, default=0.1,
                        help='Grid step size for α/β/γ (default: 0.1)')
    args = parser.parse_args()

    print(f'Loading data from PostgreSQL...')
    series_dict = load_series(args.eval)
    print(f'Loaded {len(series_dict)} drinks\n')

    print(f'Running grid search (step={args.step}, eval={args.eval} days)...\n')
    results = grid_search(series_dict, args.eval, args.season_length, args.step)
    print_recommendation(results)


if __name__ == '__main__':
    main()
