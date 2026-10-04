#!/usr/bin/env python3
"""Plot the EQX-2 CMS signed-update error experiment.

Reads the CSV files written by CmsSignedUpdateErrorExperimentTest to target/eqx-2/ and writes PNG charts to
docs/src/images/eqx-2/.

    mvn test -Dtest=CmsSignedUpdateErrorExperimentTest
    python3 docs/samples/plot_cms_error.py

Requires matplotlib.
"""
import csv
import pathlib

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[2]
DATA = ROOT / "target" / "eqx-2"
OUT = ROOT / "docs" / "src" / "images" / "eqx-2"

HISTOGRAMS = [
    ("test-1000", "test 1024x3, 1k keys"),
    ("test-10000", "test 1024x3, 10k keys"),
    ("test-50000", "test 1024x3, 50k keys"),
    ("production-10000-faults", "production 65536x5, 10k keys, 0.1% faults"),
]
TIMELINES = [
    ("production-10000-faults", "production 65536x5, 0.1% faults"),
    ("test-10000-faults", "test 1024x3, 0.1% faults"),
    ("test-10000", "test 1024x3, exact accounting"),
]
EVENTS_PER_SECOND = 1000


def read_rows(name):
    with open(DATA / name, newline="") as handle:
        return list(csv.DictReader(handle))


def plot_histograms():
    fig, ax = plt.subplots(figsize=(8, 4.5))
    for run, label in HISTOGRAMS:
        rows = read_rows(f"histogram-{run}.csv")
        errors = [int(row["error"]) for row in rows]
        samples = [int(row["samples"]) for row in rows]
        total = sum(samples)
        ax.plot(errors, [count / total for count in samples], marker="o", markersize=3, label=label)
    ax.set_yscale("log")
    ax.set_xlabel("e_k = CMS estimate - in-flight tasks")
    ax.set_ylabel("fraction of samples (log)")
    ax.set_title("CMS error distribution over one 5-minute watchdog window")
    ax.axvline(0, color="grey", linewidth=0.8)
    ax.grid(True, which="both", alpha=0.3)
    ax.legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(OUT / "error-histogram.png", dpi=120)
    plt.close(fig)


def plot_timelines():
    fig, axes = plt.subplots(1, len(TIMELINES), figsize=(12, 4), sharey=True)
    for ax, (run, label) in zip(axes, TIMELINES):
        rows = read_rows(f"timeline-{run}.csv")
        seconds = [int(row["event"]) / EVENTS_PER_SECOND for row in rows]
        ax.plot(seconds, [int(row["max"]) for row in rows], label="max")
        ax.plot(seconds, [int(row["p99_abs"]) for row in rows], label="p99 |e|")
        ax.plot(seconds, [float(row["mean"]) for row in rows], label="mean")
        ax.plot(seconds, [int(row["min"]) for row in rows], label="min")
        ax.axhline(0, color="grey", linewidth=0.8)
        ax.set_title(label, fontsize=9)
        ax.set_xlabel("seconds since last rebuild")
        ax.grid(True, alpha=0.3)
    axes[0].set_ylabel("e_k")
    axes[0].legend(fontsize=8)
    fig.suptitle("CMS error between watchdog rebuilds")
    fig.tight_layout()
    fig.savefig(OUT / "error-timeline.png", dpi=120)
    plt.close(fig)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    plot_histograms()
    plot_timelines()
    print(f"Wrote charts to {OUT}")


if __name__ == "__main__":
    main()
