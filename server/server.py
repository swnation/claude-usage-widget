#!/usr/bin/env python3
"""
Claude Usage Tracking Server
- Fetches usage data from Anthropic API
- Provides REST API for desktop widget and Android app
- Tracks per-event usage log for time-window aggregations (5h, daily, weekly, monthly)
- Sends push notifications via ntfy.sh when thresholds are crossed
"""

import json
import os
import time
import threading
import logging
from datetime import datetime, timedelta, timezone
from pathlib import Path

from flask import Flask, jsonify, request
from apscheduler.schedulers.background import BackgroundScheduler
import requests

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)

app = Flask(__name__)

CONFIG_PATH = os.environ.get("CLAUDE_WIDGET_CONFIG", str(Path(__file__).parent.parent / "config.json"))
DATA_PATH = str(Path(__file__).parent / "usage_data.json")
LOG_PATH = str(Path(__file__).parent / "usage_log.jsonl")

# Global state
usage_state = {
    "input_tokens": 0,
    "output_tokens": 0,
    "total_tokens": 0,
    "estimated_cost_usd": 0.0,
    "monthly_budget_usd": 100.0,
    "monthly_token_limit": 10_000_000,
    "budget_used_percent": 0.0,
    "tokens_used_percent": 0.0,
    "period_start": "",
    "last_updated": "",
    "model_breakdown": {},
    "alert_triggered": [],
    # Time-window aggregations
    "periods": {
        "5h":      {"tokens": 0, "cost_usd": 0.0, "input_tokens": 0, "output_tokens": 0, "requests": 0},
        "daily":   {"tokens": 0, "cost_usd": 0.0, "input_tokens": 0, "output_tokens": 0, "requests": 0},
        "weekly":  {"tokens": 0, "cost_usd": 0.0, "input_tokens": 0, "output_tokens": 0, "requests": 0},
        "monthly": {"tokens": 0, "cost_usd": 0.0, "input_tokens": 0, "output_tokens": 0, "requests": 0},
    },
}

# Anthropic pricing per 1M tokens (as of 2025)
MODEL_PRICING = {
    "claude-opus-4-6": {"input": 15.0, "output": 75.0},
    "claude-sonnet-4-6": {"input": 3.0, "output": 15.0},
    "claude-haiku-4-5": {"input": 0.80, "output": 4.0},
    # Fallback for unknown models
    "default": {"input": 3.0, "output": 15.0},
}

config = {}
notified_thresholds = set()


def load_config():
    global config
    try:
        with open(CONFIG_PATH, "r") as f:
            config = json.load(f)
        logger.info(f"Config loaded from {CONFIG_PATH}")
    except FileNotFoundError:
        logger.warning(f"Config not found at {CONFIG_PATH}, using defaults")
        config = {
            "anthropic_api_key": os.environ.get("ANTHROPIC_API_KEY", ""),
            "organization_id": os.environ.get("ANTHROPIC_ORG_ID", ""),
            "monthly_budget_usd": 100.0,
            "monthly_token_limit": 10_000_000,
            "alert_thresholds": [50, 75, 90, 95],
            "refresh_interval_seconds": 300,
            "server": {"host": "0.0.0.0", "port": 8490},
            "notification": {"ntfy_topic": "", "ntfy_server": "https://ntfy.sh"},
        }


def save_usage_data():
    try:
        with open(DATA_PATH, "w") as f:
            json.dump(usage_state, f, indent=2)
    except Exception as e:
        logger.error(f"Failed to save usage data: {e}")


def load_usage_data():
    global usage_state
    try:
        with open(DATA_PATH, "r") as f:
            saved = json.load(f)
            # Reset if new month
            if saved.get("period_start"):
                saved_month = saved["period_start"][:7]  # YYYY-MM
                current_month = datetime.now(timezone.utc).strftime("%Y-%m")
                if saved_month != current_month:
                    logger.info("New month detected, resetting usage data")
                    return
            usage_state.update(saved)
            # Ensure periods key exists for older data files
            if "periods" not in usage_state:
                usage_state["periods"] = {
                    "5h":      {"tokens": 0, "cost_usd": 0.0, "input_tokens": 0, "output_tokens": 0, "requests": 0},
                    "daily":   {"tokens": 0, "cost_usd": 0.0, "input_tokens": 0, "output_tokens": 0, "requests": 0},
                    "weekly":  {"tokens": 0, "cost_usd": 0.0, "input_tokens": 0, "output_tokens": 0, "requests": 0},
                    "monthly": {"tokens": 0, "cost_usd": 0.0, "input_tokens": 0, "output_tokens": 0, "requests": 0},
                }
    except (FileNotFoundError, json.JSONDecodeError):
        pass


def append_usage_log(entry: dict):
    """Append a usage event to the JSONL log file."""
    try:
        with open(LOG_PATH, "a") as f:
            f.write(json.dumps(entry) + "\n")
    except Exception as e:
        logger.error(f"Failed to write usage log: {e}")


def load_usage_log() -> list:
    """Load all usage log entries."""
    entries = []
    try:
        with open(LOG_PATH, "r") as f:
            for line in f:
                line = line.strip()
                if line:
                    entries.append(json.loads(line))
    except FileNotFoundError:
        pass
    except Exception as e:
        logger.error(f"Failed to read usage log: {e}")
    return entries


def aggregate_periods():
    """
    Recalculate time-window aggregations from the usage log.
    Windows: last 5 hours, today, this week, this month.
    """
    now = datetime.now(timezone.utc)
    cutoffs = {
        "5h": now - timedelta(hours=5),
        "daily": now.replace(hour=0, minute=0, second=0, microsecond=0),
        "weekly": now - timedelta(days=now.weekday()),  # Monday start
        "monthly": now.replace(day=1, hour=0, minute=0, second=0, microsecond=0),
    }
    # Normalize weekly to start of day
    cutoffs["weekly"] = cutoffs["weekly"].replace(hour=0, minute=0, second=0, microsecond=0)

    # Initialize period accumulators
    periods = {}
    for key in cutoffs:
        periods[key] = {"tokens": 0, "cost_usd": 0.0, "input_tokens": 0, "output_tokens": 0, "requests": 0}

    entries = load_usage_log()

    for entry in entries:
        try:
            ts = datetime.fromisoformat(entry["timestamp"].replace("Z", "+00:00"))
        except (KeyError, ValueError):
            continue

        inp = entry.get("input_tokens", 0)
        out = entry.get("output_tokens", 0)
        cost = entry.get("cost_usd", 0.0)
        total = inp + out

        for key, cutoff in cutoffs.items():
            if ts >= cutoff:
                periods[key]["tokens"] += total
                periods[key]["cost_usd"] = round(periods[key]["cost_usd"] + cost, 6)
                periods[key]["input_tokens"] += inp
                periods[key]["output_tokens"] += out
                periods[key]["requests"] += 1

    usage_state["periods"] = periods


def cleanup_old_logs():
    """Remove log entries older than 35 days to keep file manageable."""
    cutoff = datetime.now(timezone.utc) - timedelta(days=35)
    entries = load_usage_log()
    kept = []
    for entry in entries:
        try:
            ts = datetime.fromisoformat(entry["timestamp"].replace("Z", "+00:00"))
            if ts >= cutoff:
                kept.append(entry)
        except (KeyError, ValueError):
            kept.append(entry)

    try:
        with open(LOG_PATH, "w") as f:
            for entry in kept:
                f.write(json.dumps(entry) + "\n")
        logger.info(f"Log cleanup: kept {len(kept)}/{len(entries)} entries")
    except Exception as e:
        logger.error(f"Failed to cleanup log: {e}")


def get_pricing(model: str) -> dict:
    for key in MODEL_PRICING:
        if key in model:
            return MODEL_PRICING[key]
    return MODEL_PRICING["default"]


def calculate_cost(model: str, input_tokens: int, output_tokens: int) -> float:
    pricing = get_pricing(model)
    cost = (input_tokens / 1_000_000) * pricing["input"] + (output_tokens / 1_000_000) * pricing["output"]
    return round(cost, 6)


def fetch_usage_from_api():
    """Fetch usage from Anthropic Admin API (requires admin API key)."""
    api_key = config.get("anthropic_api_key", "")
    org_id = config.get("organization_id", "")

    if not api_key:
        logger.warning("No API key configured")
        # Still recalculate periods from local log
        aggregate_periods()
        save_usage_data()
        return

    now = datetime.now(timezone.utc)
    period_start = now.strftime("%Y-%m-01T00:00:00Z")

    headers = {
        "x-api-key": api_key,
        "anthropic-version": "2023-06-01",
        "Content-Type": "application/json",
    }

    # Try the admin usage API
    try:
        url = "https://api.anthropic.com/v1/usage"
        params = {
            "start_date": now.strftime("%Y-%m-01"),
            "end_date": now.strftime("%Y-%m-%d"),
        }
        if org_id:
            headers["anthropic-organization"] = org_id

        resp = requests.get(url, headers=headers, params=params, timeout=30)

        if resp.status_code == 200:
            data = resp.json()
            process_api_usage(data, period_start)
            return
        else:
            logger.info(f"Admin API returned {resp.status_code}, using local tracking")
    except Exception as e:
        logger.debug(f"Admin API not available: {e}")

    # Fallback: just update timestamp, keep local tracking data
    usage_state["last_updated"] = now.isoformat()
    usage_state["period_start"] = period_start
    recalculate_percentages()
    aggregate_periods()
    save_usage_data()


def process_api_usage(data: dict, period_start: str):
    """Process usage data returned from the API."""
    now = datetime.now(timezone.utc)

    total_input = 0
    total_output = 0
    total_cost = 0.0
    model_breakdown = {}

    for entry in data.get("data", [data]):
        model = entry.get("model", "unknown")
        inp = entry.get("input_tokens", 0)
        out = entry.get("output_tokens", 0)
        cost = entry.get("cost_usd", calculate_cost(model, inp, out))

        total_input += inp
        total_output += out
        total_cost += cost

        if model not in model_breakdown:
            model_breakdown[model] = {"input_tokens": 0, "output_tokens": 0, "cost_usd": 0.0}
        model_breakdown[model]["input_tokens"] += inp
        model_breakdown[model]["output_tokens"] += out
        model_breakdown[model]["cost_usd"] += cost

    usage_state["input_tokens"] = total_input
    usage_state["output_tokens"] = total_output
    usage_state["total_tokens"] = total_input + total_output
    usage_state["estimated_cost_usd"] = round(total_cost, 4)
    usage_state["model_breakdown"] = model_breakdown
    usage_state["period_start"] = period_start
    usage_state["last_updated"] = now.isoformat()

    recalculate_percentages()
    aggregate_periods()
    check_thresholds()
    save_usage_data()


def recalculate_percentages():
    budget = config.get("monthly_budget_usd", 100.0)
    token_limit = config.get("monthly_token_limit", 10_000_000)

    usage_state["monthly_budget_usd"] = budget
    usage_state["monthly_token_limit"] = token_limit

    if budget > 0:
        usage_state["budget_used_percent"] = round(
            (usage_state["estimated_cost_usd"] / budget) * 100, 2
        )
    if token_limit > 0:
        usage_state["tokens_used_percent"] = round(
            (usage_state["total_tokens"] / token_limit) * 100, 2
        )


def check_thresholds():
    thresholds = config.get("alert_thresholds", [50, 75, 90, 95])
    percent = usage_state["budget_used_percent"]
    new_alerts = []

    for t in thresholds:
        if percent >= t and t not in notified_thresholds:
            notified_thresholds.add(t)
            new_alerts.append(t)
            usage_state["alert_triggered"].append(
                {"threshold": t, "time": datetime.now(timezone.utc).isoformat()}
            )

    if new_alerts:
        send_notification(new_alerts, percent)


def send_notification(thresholds: list, percent: float):
    """Send push notification via ntfy.sh."""
    ntfy_topic = config.get("notification", {}).get("ntfy_topic", "")
    ntfy_server = config.get("notification", {}).get("ntfy_server", "https://ntfy.sh")

    if not ntfy_topic:
        logger.info(f"Alert thresholds {thresholds} crossed ({percent}%), but no ntfy topic configured")
        return

    max_t = max(thresholds)
    priority = "high" if max_t >= 90 else "default"
    emoji = "warning" if max_t >= 90 else "chart_with_upwards_trend"

    p = usage_state["periods"]
    message = (
        f"Claude API usage: {percent:.1f}%\n"
        f"Cost: ${usage_state['estimated_cost_usd']:.2f} / ${usage_state['monthly_budget_usd']:.2f}\n"
        f"Tokens: {usage_state['total_tokens']:,} / {usage_state['monthly_token_limit']:,}\n"
        f"─────\n"
        f"5h: ${p['5h']['cost_usd']:.2f} ({p['5h']['requests']} reqs)\n"
        f"Today: ${p['daily']['cost_usd']:.2f} ({p['daily']['requests']} reqs)\n"
        f"Week: ${p['weekly']['cost_usd']:.2f} ({p['weekly']['requests']} reqs)"
    )

    try:
        requests.post(
            f"{ntfy_server}/{ntfy_topic}",
            data=message.encode("utf-8"),
            headers={
                "Title": f"Claude Usage Alert: {max_t}% threshold",
                "Priority": priority,
                "Tags": emoji,
            },
            timeout=10,
        )
        logger.info(f"Notification sent for thresholds {thresholds}")
    except Exception as e:
        logger.error(f"Failed to send notification: {e}")


# === API Endpoints ===

@app.route("/api/usage", methods=["GET"])
def get_usage():
    """Get current usage data including time-window aggregations."""
    return jsonify(usage_state)


@app.route("/api/usage/track", methods=["POST"])
def track_usage():
    """
    Track usage from an API call.
    Send this after each Anthropic API call with the response's usage data.

    Expected JSON body:
    {
        "model": "claude-sonnet-4-6-20250514",
        "input_tokens": 1234,
        "output_tokens": 567
    }
    """
    data = request.get_json()
    if not data:
        return jsonify({"error": "JSON body required"}), 400

    model = data.get("model", "unknown")
    input_tokens = data.get("input_tokens", 0)
    output_tokens = data.get("output_tokens", 0)

    cost = calculate_cost(model, input_tokens, output_tokens)
    now = datetime.now(timezone.utc)

    # Append to usage log for time-window aggregations
    log_entry = {
        "timestamp": now.isoformat(),
        "model": model,
        "input_tokens": input_tokens,
        "output_tokens": output_tokens,
        "cost_usd": cost,
    }
    append_usage_log(log_entry)

    # Update monthly totals
    usage_state["input_tokens"] += input_tokens
    usage_state["output_tokens"] += output_tokens
    usage_state["total_tokens"] += input_tokens + output_tokens
    usage_state["estimated_cost_usd"] = round(usage_state["estimated_cost_usd"] + cost, 4)

    # Model breakdown
    if model not in usage_state["model_breakdown"]:
        usage_state["model_breakdown"][model] = {"input_tokens": 0, "output_tokens": 0, "cost_usd": 0.0}
    usage_state["model_breakdown"][model]["input_tokens"] += input_tokens
    usage_state["model_breakdown"][model]["output_tokens"] += output_tokens
    usage_state["model_breakdown"][model]["cost_usd"] = round(
        usage_state["model_breakdown"][model]["cost_usd"] + cost, 4
    )

    usage_state["last_updated"] = now.isoformat()
    if not usage_state["period_start"]:
        usage_state["period_start"] = now.strftime("%Y-%m-01T00:00:00Z")

    recalculate_percentages()
    aggregate_periods()
    check_thresholds()
    save_usage_data()

    return jsonify({
        "status": "ok",
        "tracked": {
            "model": model,
            "input_tokens": input_tokens,
            "output_tokens": output_tokens,
            "cost_usd": cost,
        },
        "periods": usage_state["periods"],
    })


@app.route("/api/usage/reset", methods=["POST"])
def reset_usage():
    """Reset usage data for the current period."""
    usage_state["input_tokens"] = 0
    usage_state["output_tokens"] = 0
    usage_state["total_tokens"] = 0
    usage_state["estimated_cost_usd"] = 0.0
    usage_state["budget_used_percent"] = 0.0
    usage_state["tokens_used_percent"] = 0.0
    usage_state["model_breakdown"] = {}
    usage_state["alert_triggered"] = []
    usage_state["period_start"] = datetime.now(timezone.utc).strftime("%Y-%m-01T00:00:00Z")
    usage_state["last_updated"] = datetime.now(timezone.utc).isoformat()
    usage_state["periods"] = {
        "5h":      {"tokens": 0, "cost_usd": 0.0, "input_tokens": 0, "output_tokens": 0, "requests": 0},
        "daily":   {"tokens": 0, "cost_usd": 0.0, "input_tokens": 0, "output_tokens": 0, "requests": 0},
        "weekly":  {"tokens": 0, "cost_usd": 0.0, "input_tokens": 0, "output_tokens": 0, "requests": 0},
        "monthly": {"tokens": 0, "cost_usd": 0.0, "input_tokens": 0, "output_tokens": 0, "requests": 0},
    }
    notified_thresholds.clear()

    # Clear log file too
    try:
        with open(LOG_PATH, "w") as f:
            pass
    except Exception:
        pass

    save_usage_data()
    return jsonify({"status": "reset"})


@app.route("/api/config", methods=["GET"])
def get_config():
    """Get current configuration (excluding API key)."""
    safe_config = {k: v for k, v in config.items() if "key" not in k.lower()}
    return jsonify(safe_config)


@app.route("/api/health", methods=["GET"])
def health():
    return jsonify({"status": "ok", "version": "1.1.0"})


def main():
    load_config()
    load_usage_data()

    # Initial aggregation from log
    aggregate_periods()

    # Schedule periodic usage fetch
    scheduler = BackgroundScheduler()
    interval = config.get("refresh_interval_seconds", 300)
    scheduler.add_job(fetch_usage_from_api, "interval", seconds=interval, id="fetch_usage")
    # Cleanup old logs daily
    scheduler.add_job(cleanup_old_logs, "interval", hours=24, id="cleanup_logs")
    scheduler.start()

    # Initial fetch
    threading.Thread(target=fetch_usage_from_api, daemon=True).start()

    host = config.get("server", {}).get("host", "0.0.0.0")
    port = config.get("server", {}).get("port", 8490)

    logger.info(f"Starting Claude Usage Server on {host}:{port}")
    app.run(host=host, port=port, debug=False)


if __name__ == "__main__":
    main()
