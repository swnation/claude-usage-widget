#!/usr/bin/env python3
"""
Claude Usage Desktop Widget (Standalone)
Always-on-top floating widget that tracks Claude API usage locally.
No server required - reads usage log directly and fetches from Anthropic Admin API.
"""

import json
import os
import threading
import time
import tkinter as tk
from datetime import datetime, timedelta, timezone
from pathlib import Path

import requests

CONFIG_PATH = os.environ.get(
    "CLAUDE_WIDGET_CONFIG",
    str(Path(__file__).parent.parent / "config.json"),
)
DATA_DIR = Path(__file__).parent / "data"
LOG_PATH = DATA_DIR / "usage_log.jsonl"
STATE_PATH = DATA_DIR / "state.json"

DEFAULT_CONFIG = {
    "anthropic_api_key": "",
    "organization_id": "",
    "monthly_budget_usd": 100.0,
    "monthly_token_limit": 10_000_000,
    "refresh_interval_seconds": 30,
    "alert_thresholds": [50, 75, 90, 95],
}

MODEL_PRICING = {
    "claude-opus-4-6": {"input": 15.0, "output": 75.0},
    "claude-sonnet-4-6": {"input": 3.0, "output": 15.0},
    "claude-haiku-4-5": {"input": 0.80, "output": 4.0},
    "default": {"input": 3.0, "output": 15.0},
}


def load_config():
    try:
        with open(CONFIG_PATH, "r") as f:
            cfg = json.load(f)
        # Merge with defaults
        for k, v in DEFAULT_CONFIG.items():
            cfg.setdefault(k, v)
        return cfg
    except FileNotFoundError:
        return dict(DEFAULT_CONFIG)


class UsageTracker:
    """Standalone usage tracker with local JSONL log and optional Admin API."""

    def __init__(self, config: dict):
        self.config = config
        DATA_DIR.mkdir(parents=True, exist_ok=True)

    # --- Local log ---

    def append_log(self, model: str, input_tokens: int, output_tokens: int):
        cost = self._calculate_cost(model, input_tokens, output_tokens)
        entry = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "model": model,
            "input_tokens": input_tokens,
            "output_tokens": output_tokens,
            "cost_usd": cost,
        }
        with open(LOG_PATH, "a") as f:
            f.write(json.dumps(entry) + "\n")

    def load_log(self) -> list:
        entries = []
        try:
            with open(LOG_PATH, "r") as f:
                for line in f:
                    line = line.strip()
                    if line:
                        entries.append(json.loads(line))
        except FileNotFoundError:
            pass
        return entries

    def cleanup_old_logs(self):
        cutoff = datetime.now(timezone.utc) - timedelta(days=35)
        entries = self.load_log()
        kept = []
        for e in entries:
            try:
                ts = datetime.fromisoformat(e["timestamp"].replace("Z", "+00:00"))
                if ts >= cutoff:
                    kept.append(e)
            except (KeyError, ValueError):
                kept.append(e)
        with open(LOG_PATH, "w") as f:
            for e in kept:
                f.write(json.dumps(e) + "\n")

    # --- Aggregate ---

    def aggregate(self) -> dict:
        now = datetime.now(timezone.utc)
        cutoffs = {
            "5h": now - timedelta(hours=5),
            "daily": now.replace(hour=0, minute=0, second=0, microsecond=0),
            "weekly": (now - timedelta(days=now.weekday())).replace(
                hour=0, minute=0, second=0, microsecond=0
            ),
            "monthly": now.replace(day=1, hour=0, minute=0, second=0, microsecond=0),
        }

        periods = {}
        for key in cutoffs:
            periods[key] = {
                "tokens": 0, "cost_usd": 0.0,
                "input_tokens": 0, "output_tokens": 0, "requests": 0,
            }

        entries = self.load_log()
        total_input = 0
        total_output = 0
        total_cost = 0.0

        for entry in entries:
            try:
                ts = datetime.fromisoformat(entry["timestamp"].replace("Z", "+00:00"))
            except (KeyError, ValueError):
                continue

            inp = entry.get("input_tokens", 0)
            out = entry.get("output_tokens", 0)
            cost = entry.get("cost_usd", 0.0)

            # Monthly totals (same as monthly period, but kept for top-level)
            if ts >= cutoffs["monthly"]:
                total_input += inp
                total_output += out
                total_cost += cost

            for key, cutoff in cutoffs.items():
                if ts >= cutoff:
                    periods[key]["tokens"] += inp + out
                    periods[key]["cost_usd"] = round(periods[key]["cost_usd"] + cost, 6)
                    periods[key]["input_tokens"] += inp
                    periods[key]["output_tokens"] += out
                    periods[key]["requests"] += 1

        budget = self.config.get("monthly_budget_usd", 100.0)
        token_limit = self.config.get("monthly_token_limit", 10_000_000)
        total_tokens = total_input + total_output

        return {
            "input_tokens": total_input,
            "output_tokens": total_output,
            "total_tokens": total_tokens,
            "estimated_cost_usd": round(total_cost, 4),
            "monthly_budget_usd": budget,
            "monthly_token_limit": token_limit,
            "budget_used_percent": round((total_cost / budget) * 100, 2) if budget > 0 else 0,
            "tokens_used_percent": round((total_tokens / token_limit) * 100, 2) if token_limit > 0 else 0,
            "last_updated": datetime.now(timezone.utc).isoformat(),
            "periods": periods,
        }

    # --- Admin API fetch (optional) ---

    def fetch_from_admin_api(self):
        api_key = self.config.get("anthropic_api_key", "")
        if not api_key:
            return

        now = datetime.now(timezone.utc)
        headers = {
            "x-api-key": api_key,
            "anthropic-version": "2023-06-01",
        }
        org_id = self.config.get("organization_id", "")
        if org_id:
            headers["anthropic-organization"] = org_id

        try:
            resp = requests.get(
                "https://api.anthropic.com/v1/usage",
                headers=headers,
                params={
                    "start_date": now.strftime("%Y-%m-01"),
                    "end_date": now.strftime("%Y-%m-%d"),
                },
                timeout=30,
            )
            if resp.status_code == 200:
                return resp.json()
        except Exception:
            pass
        return None

    # --- Helpers ---

    def _calculate_cost(self, model: str, input_tokens: int, output_tokens: int) -> float:
        pricing = MODEL_PRICING.get("default")
        for key, p in MODEL_PRICING.items():
            if key in model:
                pricing = p
                break
        cost = (input_tokens / 1_000_000) * pricing["input"] + \
               (output_tokens / 1_000_000) * pricing["output"]
        return round(cost, 6)


class UsageWidget:
    PERIOD_LABELS = ["5h", "Daily", "Weekly", "Monthly"]
    PERIOD_KEYS = ["5h", "daily", "weekly", "monthly"]

    def __init__(self):
        self.config = load_config()
        self.tracker = UsageTracker(self.config)
        self.drag_x = 0
        self.drag_y = 0
        self.collapsed = False
        self.usage_data = {}
        self.selected_period = 0

        self.root = tk.Tk()
        self.root.title("Claude Usage")
        self.root.overrideredirect(True)
        self.root.attributes("-topmost", True)
        self.root.attributes("-alpha", 0.92)

        # Colors
        self.bg = "#1a1a2e"
        self.fg = "#e0e0e0"
        self.accent = "#c084fc"
        self.bar_bg = "#2a2a4a"
        self.tab_bg = "#22223a"
        self.tab_active = "#c084fc"
        self.green = "#4ade80"
        self.yellow = "#fbbf24"
        self.red = "#f87171"
        self.dim = "#888899"

        self.root.configure(bg=self.bg)

        self._build_ui()
        self._bind_drag()
        self._position_window()
        self._start_polling()

    def _build_ui(self):
        self.main_frame = tk.Frame(self.root, bg=self.bg, padx=10, pady=6)
        self.main_frame.pack(fill=tk.BOTH, expand=True)

        # Header
        header = tk.Frame(self.main_frame, bg=self.bg)
        header.pack(fill=tk.X)

        self.title_label = tk.Label(
            header, text="⬡ Claude Usage", fg=self.accent, bg=self.bg,
            font=("Segoe UI", 10, "bold"), anchor="w",
        )
        self.title_label.pack(side=tk.LEFT)

        self.collapse_btn = tk.Label(
            header, text="─", fg=self.dim, bg=self.bg,
            font=("Segoe UI", 10), cursor="hand2",
        )
        self.collapse_btn.pack(side=tk.RIGHT, padx=(4, 0))
        self.collapse_btn.bind("<Button-1>", self._toggle_collapse)

        close_btn = tk.Label(
            header, text="✕", fg=self.dim, bg=self.bg,
            font=("Segoe UI", 10), cursor="hand2",
        )
        close_btn.pack(side=tk.RIGHT)
        close_btn.bind("<Button-1>", lambda e: self.root.quit())

        # Content
        self.content = tk.Frame(self.main_frame, bg=self.bg)
        self.content.pack(fill=tk.X, pady=(4, 0))

        # Period tab bar
        self.tab_frame = tk.Frame(self.content, bg=self.bg)
        self.tab_frame.pack(fill=tk.X, pady=(0, 4))

        self.tab_buttons = []
        for i, label in enumerate(self.PERIOD_LABELS):
            btn = tk.Label(
                self.tab_frame, text=label,
                fg=self.fg if i == 0 else self.dim,
                bg=self.tab_active if i == 0 else self.tab_bg,
                font=("Segoe UI", 7, "bold"),
                padx=6, pady=2, cursor="hand2",
            )
            btn.pack(side=tk.LEFT, padx=(0, 2))
            btn.bind("<Button-1>", lambda e, idx=i: self._switch_tab(idx))
            self.tab_buttons.append(btn)

        # Period summary
        self.period_frame = tk.Frame(self.content, bg=self.tab_bg, padx=6, pady=4)
        self.period_frame.pack(fill=tk.X, pady=(0, 4))

        period_row1 = tk.Frame(self.period_frame, bg=self.tab_bg)
        period_row1.pack(fill=tk.X)

        self.period_cost_label = tk.Label(
            period_row1, text="$0.00", fg=self.accent, bg=self.tab_bg,
            font=("Segoe UI", 12, "bold"), anchor="w",
        )
        self.period_cost_label.pack(side=tk.LEFT)

        self.period_reqs_label = tk.Label(
            period_row1, text="0 reqs", fg=self.dim, bg=self.tab_bg,
            font=("Segoe UI", 8), anchor="e",
        )
        self.period_reqs_label.pack(side=tk.RIGHT)

        period_row2 = tk.Frame(self.period_frame, bg=self.tab_bg)
        period_row2.pack(fill=tk.X)

        self.period_tokens_label = tk.Label(
            period_row2, text="0 tokens", fg=self.fg, bg=self.tab_bg,
            font=("Segoe UI", 8), anchor="w",
        )
        self.period_tokens_label.pack(side=tk.LEFT)

        self.period_detail_label = tk.Label(
            period_row2, text="In: 0 / Out: 0", fg=self.dim, bg=self.tab_bg,
            font=("Segoe UI", 7), anchor="e",
        )
        self.period_detail_label.pack(side=tk.RIGHT)

        # Separator
        tk.Frame(self.content, bg=self.dim, height=1).pack(fill=tk.X, pady=(2, 4))

        # Monthly budget
        budget_header = tk.Frame(self.content, bg=self.bg)
        budget_header.pack(fill=tk.X)

        tk.Label(budget_header, text="Monthly Budget", fg=self.dim, bg=self.bg,
                 font=("Segoe UI", 7)).pack(side=tk.LEFT)

        self.budget_pct_label = tk.Label(
            budget_header, text="0%", fg=self.fg, bg=self.bg,
            font=("Segoe UI", 7, "bold"), anchor="e",
        )
        self.budget_pct_label.pack(side=tk.RIGHT)

        self.cost_label = tk.Label(
            self.content, text="$0.00 / $100.00", fg=self.fg, bg=self.bg,
            font=("Segoe UI", 9, "bold"), anchor="w",
        )
        self.cost_label.pack(fill=tk.X, pady=(1, 0))

        self.cost_bar_frame = tk.Frame(self.content, bg=self.bar_bg, height=5)
        self.cost_bar_frame.pack(fill=tk.X, pady=(2, 3))
        self.cost_bar_frame.pack_propagate(False)

        self.cost_bar = tk.Frame(self.cost_bar_frame, bg=self.green, height=5)
        self.cost_bar.place(x=0, y=0, relwidth=0.0, relheight=1.0)

        # Monthly tokens
        token_header = tk.Frame(self.content, bg=self.bg)
        token_header.pack(fill=tk.X)

        tk.Label(token_header, text="Monthly Tokens", fg=self.dim, bg=self.bg,
                 font=("Segoe UI", 7)).pack(side=tk.LEFT)

        self.token_pct_label = tk.Label(
            token_header, text="0%", fg=self.fg, bg=self.bg,
            font=("Segoe UI", 7, "bold"), anchor="e",
        )
        self.token_pct_label.pack(side=tk.RIGHT)

        self.token_label = tk.Label(
            self.content, text="0 / 10M", fg=self.fg, bg=self.bg,
            font=("Segoe UI", 8), anchor="w",
        )
        self.token_label.pack(fill=tk.X)

        self.token_bar_frame = tk.Frame(self.content, bg=self.bar_bg, height=5)
        self.token_bar_frame.pack(fill=tk.X, pady=(2, 3))
        self.token_bar_frame.pack_propagate(False)

        self.token_bar = tk.Frame(self.token_bar_frame, bg=self.accent, height=5)
        self.token_bar.place(x=0, y=0, relwidth=0.0, relheight=1.0)

        # Status
        status_frame = tk.Frame(self.content, bg=self.bg)
        status_frame.pack(fill=tk.X, pady=(2, 0))

        self.status_label = tk.Label(
            status_frame, text="Loading...", fg=self.dim, bg=self.bg,
            font=("Segoe UI", 7), anchor="w",
        )
        self.status_label.pack(side=tk.LEFT)

        self.updated_label = tk.Label(
            status_frame, text="", fg=self.dim, bg=self.bg,
            font=("Segoe UI", 7), anchor="e",
        )
        self.updated_label.pack(side=tk.RIGHT)

    def _switch_tab(self, idx):
        self.selected_period = idx
        for i, btn in enumerate(self.tab_buttons):
            if i == idx:
                btn.config(bg=self.tab_active, fg=self.fg)
            else:
                btn.config(bg=self.tab_bg, fg=self.dim)
        self._update_period_display()

    def _update_period_display(self):
        periods = self.usage_data.get("periods", {})
        key = self.PERIOD_KEYS[self.selected_period]
        p = periods.get(key, {})

        self.period_cost_label.config(text=f"${p.get('cost_usd', 0):.2f}")
        self.period_reqs_label.config(text=f"{p.get('requests', 0)} reqs")
        self.period_tokens_label.config(text=f"{self._fmt(p.get('tokens', 0))} tokens")
        self.period_detail_label.config(
            text=f"In: {self._fmt(p.get('input_tokens', 0))} / Out: {self._fmt(p.get('output_tokens', 0))}"
        )

    def _bind_drag(self):
        for w in [self.main_frame, self.title_label]:
            w.bind("<ButtonPress-1>", self._start_drag)
            w.bind("<B1-Motion>", self._on_drag)

    def _start_drag(self, event):
        self.drag_x = event.x
        self.drag_y = event.y

    def _on_drag(self, event):
        x = self.root.winfo_x() + event.x - self.drag_x
        y = self.root.winfo_y() + event.y - self.drag_y
        self.root.geometry(f"+{x}+{y}")

    def _toggle_collapse(self, event=None):
        self.collapsed = not self.collapsed
        if self.collapsed:
            self.content.pack_forget()
            self.collapse_btn.config(text="□")
        else:
            self.content.pack(fill=tk.X, pady=(4, 0))
            self.collapse_btn.config(text="─")
        self.root.geometry("")

    def _position_window(self):
        self.root.update_idletasks()
        w = 260
        screen_w = self.root.winfo_screenwidth()
        self.root.geometry(f"{w}x{self.root.winfo_reqheight()}+{screen_w - w - 20}+20")

    def _fmt(self, n: int) -> str:
        if n >= 1_000_000:
            return f"{n / 1_000_000:.1f}M"
        if n >= 1_000:
            return f"{n / 1_000:.1f}K"
        return str(n)

    def _bar_color(self, pct: float) -> str:
        if pct >= 90:
            return self.red
        if pct >= 75:
            return self.yellow
        return self.green

    def _update_ui(self, data: dict):
        self.usage_data = data
        cost = data.get("estimated_cost_usd", 0)
        budget = data.get("monthly_budget_usd", 100)
        total_tokens = data.get("total_tokens", 0)
        token_limit = data.get("monthly_token_limit", 10_000_000)
        budget_pct = data.get("budget_used_percent", 0)
        token_pct = data.get("tokens_used_percent", 0)

        self.cost_label.config(text=f"${cost:.2f} / ${budget:.2f}")
        self.budget_pct_label.config(text=f"{budget_pct:.1f}%", fg=self._bar_color(budget_pct))
        bw = min(budget_pct / 100, 1.0)
        self.cost_bar.place(x=0, y=0, relwidth=max(bw, 0.005), relheight=1.0)
        self.cost_bar.config(bg=self._bar_color(budget_pct))

        self.token_label.config(text=f"{self._fmt(total_tokens)} / {self._fmt(token_limit)}")
        self.token_pct_label.config(text=f"{token_pct:.1f}%", fg=self._bar_color(token_pct))
        tw = min(token_pct / 100, 1.0)
        self.token_bar.place(x=0, y=0, relwidth=max(tw, 0.005), relheight=1.0)
        self.token_bar.config(bg=self._bar_color(token_pct))

        self._update_period_display()

        self.updated_label.config(text=datetime.now().strftime("%H:%M"))
        self.status_label.config(text="● Local tracking", fg=self.green)

    def _refresh(self):
        """Read local log and aggregate."""
        data = self.tracker.aggregate()
        self.root.after(0, self._update_ui, data)

    def _poll_loop(self):
        interval = self.config.get("refresh_interval_seconds", 30)
        while True:
            self._refresh()
            time.sleep(interval)

    def _start_polling(self):
        threading.Thread(target=self._poll_loop, daemon=True).start()

    def run(self):
        self.root.mainloop()


def main():
    widget = UsageWidget()
    widget.run()


if __name__ == "__main__":
    main()
