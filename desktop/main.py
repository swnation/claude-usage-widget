#!/usr/bin/env python3
"""
Claude Usage Desktop Widget
Always-on-top floating widget showing Claude API usage in real-time.
Shows 5-hour, daily, weekly, and monthly usage breakdowns.
"""

import json
import os
import threading
import time
import tkinter as tk
from datetime import datetime
from pathlib import Path

import requests

CONFIG_PATH = os.environ.get("CLAUDE_WIDGET_CONFIG", str(Path(__file__).parent.parent / "config.json"))

DEFAULT_CONFIG = {
    "server": {"host": "localhost", "port": 8490},
    "refresh_interval_seconds": 30,
    "monthly_budget_usd": 100.0,
    "monthly_token_limit": 10_000_000,
}


def load_config():
    try:
        with open(CONFIG_PATH, "r") as f:
            return json.load(f)
    except FileNotFoundError:
        return DEFAULT_CONFIG


class UsageWidget:
    PERIOD_LABELS = ["5h", "Daily", "Weekly", "Monthly"]
    PERIOD_KEYS = ["5h", "daily", "weekly", "monthly"]

    def __init__(self):
        self.config = load_config()
        self.server_url = self._get_server_url()
        self.drag_x = 0
        self.drag_y = 0
        self.collapsed = False
        self.usage_data = {}
        self.selected_period = 0  # index into PERIOD_KEYS

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

    def _get_server_url(self):
        host = self.config.get("server", {}).get("host", "localhost")
        port = self.config.get("server", {}).get("port", 8490)
        if host == "0.0.0.0":
            host = "localhost"
        return f"http://{host}:{port}"

    def _build_ui(self):
        self.main_frame = tk.Frame(self.root, bg=self.bg, padx=10, pady=6)
        self.main_frame.pack(fill=tk.BOTH, expand=True)

        # Header row
        header = tk.Frame(self.main_frame, bg=self.bg)
        header.pack(fill=tk.X)

        self.title_label = tk.Label(
            header, text="⬡ Claude Usage", fg=self.accent, bg=self.bg,
            font=("Segoe UI", 10, "bold"), anchor="w"
        )
        self.title_label.pack(side=tk.LEFT)

        self.collapse_btn = tk.Label(
            header, text="─", fg=self.dim, bg=self.bg,
            font=("Segoe UI", 10), cursor="hand2"
        )
        self.collapse_btn.pack(side=tk.RIGHT, padx=(4, 0))
        self.collapse_btn.bind("<Button-1>", self._toggle_collapse)

        close_btn = tk.Label(
            header, text="✕", fg=self.dim, bg=self.bg,
            font=("Segoe UI", 10), cursor="hand2"
        )
        close_btn.pack(side=tk.RIGHT)
        close_btn.bind("<Button-1>", lambda e: self.root.quit())

        # Content frame (collapsible)
        self.content = tk.Frame(self.main_frame, bg=self.bg)
        self.content.pack(fill=tk.X, pady=(4, 0))

        # === Period tab bar ===
        self.tab_frame = tk.Frame(self.content, bg=self.bg)
        self.tab_frame.pack(fill=tk.X, pady=(0, 4))

        self.tab_buttons = []
        for i, label in enumerate(self.PERIOD_LABELS):
            btn = tk.Label(
                self.tab_frame, text=label,
                fg=self.fg if i == 0 else self.dim,
                bg=self.tab_active if i == 0 else self.tab_bg,
                font=("Segoe UI", 7, "bold"),
                padx=6, pady=2, cursor="hand2"
            )
            btn.pack(side=tk.LEFT, padx=(0, 2))
            btn.bind("<Button-1>", lambda e, idx=i: self._switch_tab(idx))
            self.tab_buttons.append(btn)

        # === Period summary (selected tab) ===
        self.period_frame = tk.Frame(self.content, bg=self.tab_bg, padx=6, pady=4)
        self.period_frame.pack(fill=tk.X, pady=(0, 4))

        period_row1 = tk.Frame(self.period_frame, bg=self.tab_bg)
        period_row1.pack(fill=tk.X)

        self.period_cost_label = tk.Label(
            period_row1, text="$0.00", fg=self.accent, bg=self.tab_bg,
            font=("Segoe UI", 12, "bold"), anchor="w"
        )
        self.period_cost_label.pack(side=tk.LEFT)

        self.period_reqs_label = tk.Label(
            period_row1, text="0 reqs", fg=self.dim, bg=self.tab_bg,
            font=("Segoe UI", 8), anchor="e"
        )
        self.period_reqs_label.pack(side=tk.RIGHT)

        period_row2 = tk.Frame(self.period_frame, bg=self.tab_bg)
        period_row2.pack(fill=tk.X)

        self.period_tokens_label = tk.Label(
            period_row2, text="0 tokens", fg=self.fg, bg=self.tab_bg,
            font=("Segoe UI", 8), anchor="w"
        )
        self.period_tokens_label.pack(side=tk.LEFT)

        self.period_detail_label = tk.Label(
            period_row2, text="In: 0 / Out: 0", fg=self.dim, bg=self.tab_bg,
            font=("Segoe UI", 7), anchor="e"
        )
        self.period_detail_label.pack(side=tk.RIGHT)

        # === Monthly budget section ===
        sep = tk.Frame(self.content, bg=self.dim, height=1)
        sep.pack(fill=tk.X, pady=(2, 4))

        budget_header = tk.Frame(self.content, bg=self.bg)
        budget_header.pack(fill=tk.X)

        tk.Label(budget_header, text="Monthly Budget", fg=self.dim, bg=self.bg,
                 font=("Segoe UI", 7)).pack(side=tk.LEFT)

        self.budget_pct_label = tk.Label(
            budget_header, text="0%", fg=self.fg, bg=self.bg,
            font=("Segoe UI", 7, "bold"), anchor="e"
        )
        self.budget_pct_label.pack(side=tk.RIGHT)

        cost_frame = tk.Frame(self.content, bg=self.bg)
        cost_frame.pack(fill=tk.X, pady=(1, 0))

        self.cost_label = tk.Label(
            cost_frame, text="$0.00 / $100.00", fg=self.fg, bg=self.bg,
            font=("Segoe UI", 9, "bold"), anchor="w"
        )
        self.cost_label.pack(side=tk.LEFT)

        # Cost progress bar
        self.cost_bar_frame = tk.Frame(self.content, bg=self.bar_bg, height=5)
        self.cost_bar_frame.pack(fill=tk.X, pady=(2, 3))
        self.cost_bar_frame.pack_propagate(False)

        self.cost_bar = tk.Frame(self.cost_bar_frame, bg=self.green, height=5)
        self.cost_bar.place(x=0, y=0, relwidth=0.0, relheight=1.0)

        # Token section
        token_header = tk.Frame(self.content, bg=self.bg)
        token_header.pack(fill=tk.X)

        tk.Label(token_header, text="Monthly Tokens", fg=self.dim, bg=self.bg,
                 font=("Segoe UI", 7)).pack(side=tk.LEFT)

        self.token_pct_label = tk.Label(
            token_header, text="0%", fg=self.fg, bg=self.bg,
            font=("Segoe UI", 7, "bold"), anchor="e"
        )
        self.token_pct_label.pack(side=tk.RIGHT)

        self.token_label = tk.Label(
            self.content, text="0 / 10M", fg=self.fg, bg=self.bg,
            font=("Segoe UI", 8), anchor="w"
        )
        self.token_label.pack(fill=tk.X)

        self.token_bar_frame = tk.Frame(self.content, bg=self.bar_bg, height=5)
        self.token_bar_frame.pack(fill=tk.X, pady=(2, 3))
        self.token_bar_frame.pack_propagate(False)

        self.token_bar = tk.Frame(self.token_bar_frame, bg=self.accent, height=5)
        self.token_bar.place(x=0, y=0, relwidth=0.0, relheight=1.0)

        # Status bar
        status_frame = tk.Frame(self.content, bg=self.bg)
        status_frame.pack(fill=tk.X, pady=(2, 0))

        self.status_label = tk.Label(
            status_frame, text="Connecting...", fg=self.dim, bg=self.bg,
            font=("Segoe UI", 7), anchor="w"
        )
        self.status_label.pack(side=tk.LEFT)

        self.updated_label = tk.Label(
            status_frame, text="", fg=self.dim, bg=self.bg,
            font=("Segoe UI", 7), anchor="e"
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

        cost = p.get("cost_usd", 0)
        tokens = p.get("tokens", 0)
        inp = p.get("input_tokens", 0)
        out = p.get("output_tokens", 0)
        reqs = p.get("requests", 0)

        self.period_cost_label.config(text=f"${cost:.2f}")
        self.period_reqs_label.config(text=f"{reqs} reqs")
        self.period_tokens_label.config(text=f"{self._format_tokens(tokens)} tokens")
        self.period_detail_label.config(text=f"In: {self._format_tokens(inp)} / Out: {self._format_tokens(out)}")

    def _bind_drag(self):
        for widget in [self.main_frame, self.title_label]:
            widget.bind("<ButtonPress-1>", self._start_drag)
            widget.bind("<B1-Motion>", self._on_drag)

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
            self.root.geometry("")
        else:
            self.content.pack(fill=tk.X, pady=(4, 0))
            self.collapse_btn.config(text="─")
            self.root.geometry("")

    def _position_window(self):
        self.root.update_idletasks()
        w = 260
        h = self.root.winfo_reqheight()
        screen_w = self.root.winfo_screenwidth()
        x = screen_w - w - 20
        y = 20
        self.root.geometry(f"{w}x{h}+{x}+{y}")

    def _format_tokens(self, n: int) -> str:
        if n >= 1_000_000:
            return f"{n / 1_000_000:.1f}M"
        elif n >= 1_000:
            return f"{n / 1_000:.1f}K"
        return str(n)

    def _get_bar_color(self, percent: float) -> str:
        if percent >= 90:
            return self.red
        elif percent >= 75:
            return self.yellow
        return self.green

    def _update_ui(self, data: dict):
        cost = data.get("estimated_cost_usd", 0)
        budget = data.get("monthly_budget_usd", 100)
        total_tokens = data.get("total_tokens", 0)
        token_limit = data.get("monthly_token_limit", 10_000_000)
        budget_pct = data.get("budget_used_percent", 0)
        token_pct = data.get("tokens_used_percent", 0)

        # Monthly budget
        self.cost_label.config(text=f"${cost:.2f} / ${budget:.2f}")
        self.budget_pct_label.config(
            text=f"{budget_pct:.1f}%",
            fg=self._get_bar_color(budget_pct)
        )
        bar_width = min(budget_pct / 100, 1.0)
        self.cost_bar.place(x=0, y=0, relwidth=max(bar_width, 0.005), relheight=1.0)
        self.cost_bar.config(bg=self._get_bar_color(budget_pct))

        # Monthly tokens
        self.token_label.config(
            text=f"{self._format_tokens(total_tokens)} / {self._format_tokens(token_limit)}"
        )
        self.token_pct_label.config(
            text=f"{token_pct:.1f}%",
            fg=self._get_bar_color(token_pct)
        )
        token_bar_width = min(token_pct / 100, 1.0)
        self.token_bar.place(x=0, y=0, relwidth=max(token_bar_width, 0.005), relheight=1.0)
        self.token_bar.config(bg=self._get_bar_color(token_pct))

        # Period display
        self._update_period_display()

        # Last updated
        last_updated = data.get("last_updated", "")
        if last_updated:
            try:
                dt = datetime.fromisoformat(last_updated.replace("Z", "+00:00"))
                self.updated_label.config(text=dt.strftime("%H:%M"))
            except ValueError:
                pass

        self.status_label.config(text="● Connected", fg=self.green)

    def _fetch_usage(self):
        try:
            resp = requests.get(f"{self.server_url}/api/usage", timeout=5)
            if resp.status_code == 200:
                self.usage_data = resp.json()
                self.root.after(0, self._update_ui, self.usage_data)
            else:
                self.root.after(0, self.status_label.config,
                               {"text": f"Server error: {resp.status_code}", "fg": self.red})
        except requests.ConnectionError:
            self.root.after(0, self.status_label.config,
                           {"text": "● Server offline", "fg": self.red})
        except Exception as e:
            self.root.after(0, self.status_label.config,
                           {"text": f"Error: {str(e)[:30]}", "fg": self.red})

    def _poll_loop(self):
        interval = self.config.get("refresh_interval_seconds", 30)
        while True:
            self._fetch_usage()
            time.sleep(interval)

    def _start_polling(self):
        t = threading.Thread(target=self._poll_loop, daemon=True)
        t.start()

    def run(self):
        self.root.mainloop()


def main():
    widget = UsageWidget()
    widget.run()


if __name__ == "__main__":
    main()
