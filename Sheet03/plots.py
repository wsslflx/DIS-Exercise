import os
import re
import sys
import argparse
import psycopg2
import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.dates as mdates


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


def connect(env):
    m = re.match(r'jdbc:postgresql://([^:/]+):(\d+)/(.+)', env['POSTGRES_URL'])
    host, port, dbname = m.groups()
    return psycopg2.connect(host=host, port=int(port), dbname=dbname,
                            user=env['POSTGRES_USER'],
                            password=env['POSTGRES_PASSWORD'])


def plot_eval(ax, df, ingredient, alpha, beta, gamma):
    hist        = df[df['actual_amount'].notna()]
    eval_period = df[df['eval_forecast_amount'].notna()]
    unit        = df['unit'].iloc[0]

    ax.plot(hist['date'], hist['actual_amount'],
            color='steelblue', linewidth=1.2, label='Actual')
    ax.plot(eval_period['date'], eval_period['eval_forecast_amount'],
            color='tomato', linewidth=1.5, linestyle='--', label='HW forecast (eval)')
    # show actual in eval period as a faint continuation
    ax.plot(eval_period['date'], eval_period['actual_amount'],
            color='steelblue', linewidth=1.2, alpha=0.35)

    if not eval_period.empty:
        ax.axvline(eval_period['date'].iloc[0], color='gray',
                   linestyle=':', linewidth=1, label='Train / eval split')

    ax.set_title(f'Evaluation Forecast — {ingredient}\n'
                 f'α={alpha}  β={beta}  γ={gamma}  (season=7, additive TES)')
    ax.set_xlabel('Date')
    ax.set_ylabel(f'Daily amount ({unit})')
    ax.legend()
    ax.xaxis.set_major_formatter(mdates.DateFormatter('%Y-%m-%d'))


def plot_future(ax, df, ingredient, alpha, beta, gamma):
    hist   = df[df['actual_amount'].notna()]
    future = df[df['future_forecast_amount'].notna()]
    unit   = df['unit'].iloc[0]

    ax.plot(hist['date'], hist['actual_amount'],
            color='steelblue', linewidth=1.2, label='Actual')
    ax.plot(future['date'], future['future_forecast_amount'],
            color='darkorange', linewidth=1.5, linestyle='--', label='HW forecast (future)')

    if not future.empty:
        ax.axvline(future['date'].iloc[0], color='gray',
                   linestyle=':', linewidth=1, label='Forecast start')

    ax.set_title(f'Future Forecast — {ingredient}\n'
                 f'α={alpha}  β={beta}  γ={gamma}  (season=7, additive TES)')
    ax.set_xlabel('Date')
    ax.set_ylabel(f'Daily amount ({unit})')
    ax.legend()
    ax.xaxis.set_major_formatter(mdates.DateFormatter('%Y-%m-%d'))


def main():
    parser = argparse.ArgumentParser(description='Plot Holt-Winters ingredient forecasts')
    parser.add_argument('--ingredient', default='Milk',
                        help='Ingredient to plot (default: Milk)')
    parser.add_argument('--alpha', type=float, default=0.3)
    parser.add_argument('--beta',  type=float, default=0.1)
    parser.add_argument('--gamma', type=float, default=0.2)
    parser.add_argument('--out-eval',   default='plot_eval.png')
    parser.add_argument('--out-future', default='plot_future.png')
    args = parser.parse_args()

    env  = read_env()
    conn = connect(env)

    query = """
        SELECT date, actual_amount, eval_forecast_amount, future_forecast_amount, unit
        FROM ingredient_forecast
        WHERE ingredient = %s
        ORDER BY date
    """
    df = pd.read_sql(query, conn, params=(args.ingredient,), parse_dates=['date'])
    conn.close()

    if df.empty:
        print(f'No data found for ingredient "{args.ingredient}". '
              f'Run the Java program first.')
        sys.exit(1)

    print(f'Plotting "{args.ingredient}" — {len(df)} rows')

    for out_path, plot_fn, label in [
        (args.out_eval,   plot_eval,   'eval'),
        (args.out_future, plot_future, 'future'),
    ]:
        fig, ax = plt.subplots(figsize=(12, 4))
        plot_fn(ax, df, args.ingredient, args.alpha, args.beta, args.gamma)
        fig.autofmt_xdate()
        plt.tight_layout()
        plt.savefig(out_path, dpi=150)
        plt.close(fig)
        print(f'Saved {label} plot: {os.path.abspath(out_path)}')


if __name__ == '__main__':
    main()
