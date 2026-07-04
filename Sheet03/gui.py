import sys
import os
import re
import subprocess
import warnings
import numpy as np
import psycopg2
import pandas as pd
import matplotlib
matplotlib.use('Qt5Agg')
from matplotlib.backends.backend_qt5agg import FigureCanvasQTAgg
from matplotlib.figure import Figure
import matplotlib.dates as mdates

from PyQt5.QtWidgets import (
    QApplication, QMainWindow, QWidget,
    QVBoxLayout, QHBoxLayout, QFormLayout,
    QLineEdit, QPushButton, QComboBox, QLabel,
    QGroupBox, QMessageBox,
)
from PyQt5.QtCore import QThread, pyqtSignal, QObject

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
JAR = os.path.join(SCRIPT_DIR, 'target', 'dis-exercise-sheet03-1.0-SNAPSHOT.jar')


# ── Holt-Winters (same math as HoltWinters.java, used for grid search) ─────────

def _holt_winters(series, alpha, beta, gamma, L, horizon):
    n     = len(series)
    level = np.mean(series[:L])
    trend = 0.0
    s     = series[:L] - level
    for t in range(L, n):
        prev     = level
        seasonal = s[t % L]
        level    = alpha * (series[t] - seasonal) + (1 - alpha) * (level + trend)
        trend    = beta  * (level - prev)          + (1 - beta)  * trend
        s[t % L] = gamma * (series[t] - level)    + (1 - gamma) * seasonal
    return np.array([max(0.0, level + h * trend + s[(n + h - 1) % L])
                     for h in range(1, horizon + 1)])


def _rmse(a, b):
    return np.sqrt(np.mean((a - b) ** 2))


# ── DB helpers ─────────────────────────────────────────────────────────────────

def _read_env():
    d = SCRIPT_DIR
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


def _connect():
    env = _read_env()
    m   = re.match(r'jdbc:postgresql://([^:/]+):(\d+)/(.+)', env['POSTGRES_URL'])
    host, port, dbname = m.groups()
    return psycopg2.connect(host=host, port=int(port), dbname=dbname,
                            user=env['POSTGRES_USER'],
                            password=env['POSTGRES_PASSWORD'])


def _load_drink_series(eval_days):
    conn = _connect()
    with warnings.catch_warnings():
        warnings.simplefilter('ignore', UserWarning)
        df   = pd.read_sql("""
        SELECT date_fk, coffee_name, COUNT(*) AS cups
        FROM coffee_sales
        GROUP BY date_fk, coffee_name
        ORDER BY date_fk
    """, conn)
    conn.close()
    df['date_fk'] = pd.to_datetime(df['date_fk'])
    pivot = (df.pivot(index='date_fk', columns='coffee_name', values='cups')
               .reindex(pd.date_range(df['date_fk'].min(), df['date_fk'].max(), freq='D'))
               .fillna(0))
    result = {}
    for drink in pivot.columns:
        full = pivot[drink].values.astype(float)
        if len(full) - eval_days >= 7:
            result[drink] = (full[:-eval_days], full[-eval_days:])
    return result


def _load_ingredients():
    conn = _connect()
    cur  = conn.cursor()
    cur.execute("SELECT DISTINCT ingredient FROM ingredient_forecast ORDER BY ingredient")
    rows = [r[0] for r in cur.fetchall()]
    conn.close()
    return rows


def _load_ingredient_df(ingredient):
    conn = _connect()
    with warnings.catch_warnings():
        warnings.simplefilter('ignore', UserWarning)
        df = pd.read_sql("""
            SELECT date, actual_amount, eval_forecast_amount, future_forecast_amount, unit
            FROM ingredient_forecast
            WHERE ingredient = %s
            ORDER BY date
        """, conn, params=(ingredient,), parse_dates=['date'])
    conn.close()
    return df


# ── Background workers ─────────────────────────────────────────────────────────

class GridSearchWorker(QObject):
    finished = pyqtSignal(float, float, float)
    error    = pyqtSignal(str)

    def __init__(self, eval_days):
        super().__init__()
        self.eval_days = eval_days

    def run(self):
        try:
            series = _load_drink_series(self.eval_days)
            values = np.round(np.arange(0.1, 1.0, 0.1), 6)

            all_best = []
            for drink, (train, actual) in series.items():
                best_rmse, best = np.inf, None
                for a in values:
                    for b in values:
                        for g in values:
                            try:
                                fc = _holt_winters(train.copy(), a, b, g, 7, self.eval_days)
                                r  = _rmse(actual, fc)
                                if r < best_rmse:
                                    best_rmse, best = r, (a, b, g)
                            except Exception:
                                continue
                if best:
                    all_best.append(best)

            avg = np.array(all_best).mean(axis=0)
            self.finished.emit(float(avg[0]), float(avg[1]), float(avg[2]))
        except Exception as e:
            self.error.emit(str(e))


class ForecastWorker(QObject):
    finished = pyqtSignal()
    error    = pyqtSignal(str)

    def __init__(self, alpha, beta, gamma, horizon, eval_days):
        super().__init__()
        self.alpha     = alpha
        self.beta      = beta
        self.gamma     = gamma
        self.horizon   = horizon
        self.eval_days = eval_days

    def run(self):
        try:
            cmd = [
                'java', '-jar', JAR,
                '--alpha',   str(self.alpha),
                '--beta',    str(self.beta),
                '--gamma',   str(self.gamma),
                '--horizon', str(self.horizon),
                '--eval',    str(self.eval_days),
                '--out',     SCRIPT_DIR,
            ]
            proc = subprocess.Popen(cmd, cwd=SCRIPT_DIR,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    text=True)
            proc.communicate()
            if proc.returncode == 0:
                self.finished.emit()
            else:
                self.error.emit(f'Java exited with code {proc.returncode}')
        except Exception as e:
            self.error.emit(str(e))


# ── Main window ────────────────────────────────────────────────────────────────

class MainWindow(QMainWindow):

    def __init__(self):
        super().__init__()
        self.setWindowTitle('Café Ingredient Forecast — Holt-Winters TES')
        self.resize(1300, 720)
        self._threads = []   # keep thread/worker pairs alive
        self._build_ui()

    def _build_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        root = QVBoxLayout(central)
        root.setSpacing(8)

        # ── Parameters + buttons ───────────────────────────────────────────────
        param_group = QGroupBox('Model Parameters')
        param_row   = QHBoxLayout(param_group)

        form = QFormLayout()
        self.f_alpha   = QLineEdit('0.3');  form.addRow('α (level):', self.f_alpha)
        self.f_beta    = QLineEdit('0.1');  form.addRow('β (trend):', self.f_beta)
        self.f_gamma   = QLineEdit('0.2');  form.addRow('γ (seasonal):', self.f_gamma)
        self.f_horizon = QLineEdit('14');   form.addRow('Horizon (days):', self.f_horizon)
        self.f_eval    = QLineEdit('14');   form.addRow('Eval holdout (days):', self.f_eval)
        param_row.addLayout(form)

        btn_col = QVBoxLayout()
        self.btn_optimize = QPushButton('Find Best Parameters')
        self.btn_run      = QPushButton('Run Forecast')
        for btn in (self.btn_optimize, self.btn_run):
            btn.setFixedHeight(40)
            btn_col.addWidget(btn)
        self.btn_optimize.clicked.connect(self._run_grid_search)
        self.btn_run.clicked.connect(self._run_forecast)
        btn_col.addStretch()
        param_row.addLayout(btn_col)
        root.addWidget(param_group)

        # ── Ingredient selector ────────────────────────────────────────────────
        ing_row = QHBoxLayout()
        ing_row.addWidget(QLabel('Ingredient:'))
        self.combo = QComboBox()
        self.combo.setMinimumWidth(220)
        self.combo.currentTextChanged.connect(self._on_ingredient_changed)
        ing_row.addWidget(self.combo)
        ing_row.addStretch()
        root.addLayout(ing_row)

        # ── Side-by-side plots ─────────────────────────────────────────────────
        plot_row = QHBoxLayout()
        self.fig_eval,   self.ax_eval,   self.canvas_eval   = self._make_canvas()
        self.fig_future, self.ax_future, self.canvas_future = self._make_canvas()
        plot_row.addWidget(self.canvas_eval)
        plot_row.addWidget(self.canvas_future)
        root.addLayout(plot_row)

    @staticmethod
    def _make_canvas():
        fig    = Figure(figsize=(6, 3.8), tight_layout=True)
        ax     = fig.add_subplot(111)
        canvas = FigureCanvasQTAgg(fig)
        return fig, ax, canvas

    # ── Button handlers ────────────────────────────────────────────────────────

    def _run_grid_search(self):
        eval_days = self._int(self.f_eval, 14)
        self._set_busy(True)
        worker = GridSearchWorker(eval_days)
        self._start_worker(worker, on_finished=self._on_grid_done,
                           on_done_signal=worker.finished)

    def _on_grid_done(self, alpha, beta, gamma):
        self.f_alpha.setText(f'{alpha:.2f}')
        self.f_beta.setText(f'{beta:.2f}')
        self.f_gamma.setText(f'{gamma:.2f}')

    def _run_forecast(self):
        alpha   = self._float(self.f_alpha,   0.3)
        beta    = self._float(self.f_beta,    0.1)
        gamma   = self._float(self.f_gamma,   0.2)
        horizon = self._int(self.f_horizon,   14)
        eval_d  = self._int(self.f_eval,      14)
        self._set_busy(True)
        worker = ForecastWorker(alpha, beta, gamma, horizon, eval_d)
        self._start_worker(worker, on_finished=self._on_forecast_done,
                           on_done_signal=worker.finished)

    def _on_forecast_done(self):
        try:
            ingredients = _load_ingredients()
            prev = self.combo.currentText()
            self.combo.blockSignals(True)
            self.combo.clear()
            self.combo.addItems(ingredients)
            if prev in ingredients:
                self.combo.setCurrentText(prev)
            self.combo.blockSignals(False)
            # manually trigger plot for current selection
            self._on_ingredient_changed(self.combo.currentText())
        except Exception as e:
            self._on_error(str(e))

    def _on_ingredient_changed(self, ingredient):
        if not ingredient:
            return
        try:
            df     = _load_ingredient_df(ingredient)
            alpha  = self._float(self.f_alpha,  0.3)
            beta   = self._float(self.f_beta,   0.1)
            gamma  = self._float(self.f_gamma,  0.2)
            params = f'α={alpha}  β={beta}  γ={gamma}  (season=7, additive TES)'
            self._draw_eval(df, ingredient, params)
            self._draw_future(df, ingredient, params)
        except Exception as e:
            self._on_error(str(e))

    # ── Worker thread helper ───────────────────────────────────────────────────

    def _start_worker(self, worker, on_finished, on_done_signal):
        thread = QThread()
        worker.moveToThread(thread)
        thread.started.connect(worker.run)
        worker.error.connect(self._on_error)
        on_done_signal.connect(on_finished)
        on_done_signal.connect(thread.quit)
        worker.error.connect(thread.quit)
        thread.finished.connect(lambda: self._set_busy(False))
        self._threads.append((thread, worker))
        thread.start()

    def _on_error(self, msg):
        self._set_busy(False)
        QMessageBox.critical(self, 'Error', msg)

    def _set_busy(self, busy):
        self.btn_optimize.setEnabled(not busy)
        self.btn_run.setEnabled(not busy)
        self.combo.setEnabled(not busy)

    # ── Plot helpers ───────────────────────────────────────────────────────────

    def _draw_eval(self, df, ingredient, params):
        ax          = self.ax_eval
        hist        = df[df['actual_amount'].notna()]
        eval_period = df[df['eval_forecast_amount'].notna()]
        unit        = df['unit'].iloc[0]
        ax.clear()

        ax.plot(hist['date'], hist['actual_amount'],
                color='steelblue', linewidth=1.2, label='Actual')
        ax.plot(eval_period['date'], eval_period['actual_amount'],
                color='steelblue', linewidth=1.2, alpha=0.35)
        ax.plot(eval_period['date'], eval_period['eval_forecast_amount'],
                color='tomato', linewidth=1.5, linestyle='--', label='HW forecast')
        if not eval_period.empty:
            ax.axvline(eval_period['date'].iloc[0], color='gray',
                       linestyle=':', linewidth=1, label='Train / eval split')

        ax.set_title(f'Evaluation — {ingredient}\n{params}', fontsize=9)
        ax.set_ylabel(f'Daily amount ({unit})', fontsize=8)
        ax.legend(fontsize=7)
        ax.xaxis.set_major_formatter(mdates.DateFormatter('%b %Y'))
        self.fig_eval.autofmt_xdate()
        self.canvas_eval.draw()

    def _draw_future(self, df, ingredient, params):
        ax     = self.ax_future
        hist   = df[df['actual_amount'].notna()]
        future = df[df['future_forecast_amount'].notna()]
        unit   = df['unit'].iloc[0]
        ax.clear()

        ax.plot(hist['date'], hist['actual_amount'],
                color='steelblue', linewidth=1.2, label='Actual')
        ax.plot(future['date'], future['future_forecast_amount'],
                color='darkorange', linewidth=1.5, linestyle='--', label='HW forecast')
        if not future.empty:
            ax.axvline(future['date'].iloc[0], color='gray',
                       linestyle=':', linewidth=1, label='Forecast start')

        ax.set_title(f'Future Forecast — {ingredient}\n{params}', fontsize=9)
        ax.set_ylabel(f'Daily amount ({unit})', fontsize=8)
        ax.legend(fontsize=7)
        ax.xaxis.set_major_formatter(mdates.DateFormatter('%b %Y'))
        self.fig_future.autofmt_xdate()
        self.canvas_future.draw()

    # ── Input helpers ──────────────────────────────────────────────────────────

    @staticmethod
    def _float(field, default):
        try:    return float(field.text())
        except: return default

    @staticmethod
    def _int(field, default):
        try:    return int(field.text())
        except: return default


# ── Entry point ────────────────────────────────────────────────────────────────

if __name__ == '__main__':
    app = QApplication(sys.argv)
    win = MainWindow()
    win.show()
    sys.exit(app.exec_())
