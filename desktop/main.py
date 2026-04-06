#!/usr/bin/env python3
"""
Claude Usage Desktop Widget
Always-on-top floating widget showing Claude API usage in real-time.
"""

import json
import os
import sys
import threading
import time
import tkinter as tk
from tkinter import ttk, font as tkfont
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
    def __init__(self):
        self.config = load_config()
        self.server_url = self._get_server_url()
        self.dragging = False
        self.drag_x = 0
        self.drag_y = 0
        self.collapsed = False
        self.usage_data = {}

        self.root = tk.Tk()
        self.root.title("Claude Usage")
        self.root.overrideredirect(True)  # Remove window decorations
        self.root.attributes("-topmost", True)  # Always on top
        self.root.attributes("-alpha", 0.92)  # Slight transparency

        # Colors
        self.bg = "#1a1a2e"
        self.fg = "#e0e0e0"
        self.accent = "#c084fc"  # Purple
        self.bar_bg = "#2a2a4a"
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
        # Main frame
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

        # Collapse button
        self.collapse_btn = tk.Label(
            header, text="─", fg=self.dim, bg=self.bg,
            font=("Segoe UI", 10), cursor="hand2"
        )
        self.collapse_btn.pack(side=tk.RIGHT, padx=(4, 0))
        self.collapse_btn.bind("<Button-1>", self._toggle_collapse)

        # Close button
        close_btn = tk.Label(
            header, text="✕", fg=self.dim, bg=self.bg,
            font=("Segoe UI", 10), cursor="hand2"
        )
        close_btn.pack(side=tk.RIGHT)
        close_btn.bind("<Button-1>", lambda e: self.root.quit())

        # Content frame (collapsible)
        self.content = tk.Frame(self.main_frame, bg=self.bg)
        self.content.pack(fill=tk.X, pady=(4, 0))

        # Cost section
        cost_frame = tk.Frame(self.content, bg=self.bg)
        cost_frame.pack(fill=tk.X, pady=(2, 0))

        tk.Label(cost_frame, text="Cost", fg=self.dim, bg=self.bg,
                 font=("Segoe UI", 8)).pack(side=tk.LEFT)

        self.cost_label = tk.Label(
            cost_frame, text="$0.00 / $100.00", fg=self.fg, bg=self.bg,
            font=("Segoe UI", 9, "bold"), anchor="e"
        )
        self.cost_label.pack(side=tk.RIGHT)

        # Cost progress bar
        self.cost_bar_frame = tk.Frame(self.content, bg=self.bar_bg, height=6)
        self.cost_bar_frame.pack(fill=tk.X, pady=(2, 4))
        self.cost_bar_frame.pack_propagate(False)

        self.cost_bar = tk.Frame(self.cost_bar_frame, bg=self.green, height=6)
        self.cost_bar.place(x=0, y=0, relwidth=0.0, relheight=1.0)

        # Token section
        token_frame = tk.Frame(self.content, bg=self.bg)
        token_frame.pack(fill=tk.X, pady=(2, 0))

        tk.Label(token_frame, text="Tokens", fg=self.dim, bg=self.bg,
                 font=("Segoe UI", 8)).pack(side=tk.LEFT)

        self.token_label = tk.Label(
            token_frame, text="0 / 10M", fg=self.fg, bg=self.bg,
            font=("Segoe UI", 9, "bold"), anchor="e"
        )
        self.token_label.pack(side=tk.RIGHT)

        # Token progress bar
        self.token_bar_frame = tk.Frame(self.content, bg=self.bar_bg, height=6)
        self.token_bar_frame.pack(fill=tk.X, pady=(2, 4))
        self.token_bar_frame.pack_propagate(False)

        self.token_bar = tk.Frame(self.token_bar_frame, bg=self.accent, height=6)
        self.token_bar.place(x=0, y=0, relwidth=0.0, relheight=1.0)

        # Detail row
        detail_frame = tk.Frame(self.content, bg=self.bg)
        detail_frame.pack(fill=tk.X, pady=(0, 2))

        self.input_label = tk.Label(
            detail_frame, text="In: 0", fg=self.dim, bg=self.bg,
            font=("Segoe UI", 7)
        )
        self.input_label.pack(side=tk.LEFT)

        self.output_label = tk.Label(
            detail_frame, text="Out: 0", fg=self.dim, bg=self.bg,
            font=("Segoe UI", 7)
        )
        self.output_label.pack(side=tk.LEFT, padx=(8, 0))

        self.updated_label = tk.Label(
            detail_frame, text="", fg=self.dim, bg=self.bg,
            font=("Segoe UI", 7), anchor="e"
        )
        self.updated_label.pack(side=tk.RIGHT)

        # Status bar
        self.status_label = tk.Label(
            self.content, text="Connecting...", fg=self.dim, bg=self.bg,
            font=("Segoe UI", 7), anchor="w"
        )
        self.status_label.pack(fill=tk.X)

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
        w = 240
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
        input_tokens = data.get("input_tokens", 0)
        output_tokens = data.get("output_tokens", 0)
        budget_pct = data.get("budget_used_percent", 0)
        token_pct = data.get("tokens_used_percent", 0)

        # Cost
        self.cost_label.config(text=f"${cost:.2f} / ${budget:.2f}")
        bar_width = min(budget_pct / 100, 1.0)
        self.cost_bar.place(x=0, y=0, relwidth=max(bar_width, 0.005), relheight=1.0)
        self.cost_bar.config(bg=self._get_bar_color(budget_pct))

        # Tokens
        self.token_label.config(
            text=f"{self._format_tokens(total_tokens)} / {self._format_tokens(token_limit)}"
        )
        token_bar_width = min(token_pct / 100, 1.0)
        self.token_bar.place(x=0, y=0, relwidth=max(token_bar_width, 0.005), relheight=1.0)
        self.token_bar.config(bg=self._get_bar_color(token_pct))

        # Details
        self.input_label.config(text=f"In: {self._format_tokens(input_tokens)}")
        self.output_label.config(text=f"Out: {self._format_tokens(output_tokens)}")

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
