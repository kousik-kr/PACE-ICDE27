#!/usr/bin/env python3
"""Regenerate the manuscript's NYC case-study figure from portable plot data.

Only Python's standard library and Matplotlib are required. The input is the
small, independently checked local figure-data snapshot, not the NY graph.
The archived source PDF/PNG in ../figures are preserved by default.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import tempfile
from pathlib import Path

# The default Matplotlib cache may be unwritable in a restricted artifact tree.
cache = Path(tempfile.gettempdir()) / "pace_nyc_matplotlib_cache"
cache.mkdir(parents=True, exist_ok=True)
os.environ.setdefault("MPLCONFIGDIR", str(cache))

import matplotlib as mpl
mpl.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.collections import LineCollection
from matplotlib.lines import Line2D
from matplotlib.ticker import MaxNLocator
from matplotlib.transforms import Bbox

PACKAGE = Path(__file__).resolve().parents[1]
DATA = PACKAGE / "data"
COLORS = ("#0072B2", "#E69F00", "#009E73", "#D55E00", "#CC79A7")
INTERVAL_BEST_GREEN = "#138A36"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_data() -> dict:
    provenance = json.loads((DATA / "provenance.json").read_text(encoding="utf-8"))
    expected = provenance["included_sha256"]
    for name, path in {
        "query_jsonl": DATA / "query.jsonl",
        "result_jsonl": DATA / "result.jsonl",
        "validation_jsonl": DATA / "validation.jsonl",
        "figure_data_json_gz": DATA / "figure_data.json.gz",
    }.items():
        if sha256(path) != expected[name]:
            raise ValueError(f"checksum mismatch: {path}")
    data = json.loads(gzip.decompress((DATA / "figure_data.json.gz").read_bytes()))
    if data["schema_version"] != "nyc-case-study-figure-v1":
        raise ValueError("unsupported figure-data version")
    if data["query_id"] != "NYC-Q012-MORNING-R20":
        raise ValueError("unexpected figure query")
    if len(data["path_segments"]) != len(data["candidate_profiles"]) != 5:
        raise ValueError("expected five path profiles")
    minutes = [m for cell in data["selected_cells"] for m in range(cell["start"], cell["end"] + 1)]
    if minutes != list(range(data["interval_start"], data["interval_end"] + 1)):
        raise ValueError("selected cells do not cover the departure interval")
    return data


def local_clock(minute: int) -> str:
    return f"{minute // 60:02d}:{minute % 60:02d}"


def score_at(profile: dict, minute: int) -> int:
    pieces = profile["scores"]
    for index, (start, end, score) in enumerate(pieces):
        if start <= minute < end or (index == len(pieces) - 1 and minute == end):
            return int(score)
    raise ValueError(f"score absent for P{profile['path_id']} at {minute}")


def step_runs(axis, departures, values, visible, **style) -> None:
    start = None
    for index in range(len(departures) + 1):
        active = index < len(departures) and visible[index]
        if active and start is None:
            start = index
        if not active and start is not None:
            shown_departures = departures[start:index]
            shown_values = values[start:index]
            axis.step(
                shown_departures + [shown_departures[-1] + 1],
                shown_values + [shown_values[-1]],
                where="post", **style,
            )
            start = None


def style_score_axis(axis, start: int, end: int) -> None:
    ticks = list(range(start, end + 1, 30))
    if ticks[-1] != end:
        ticks.append(end)
    axis.set_xticks(ticks)
    axis.tick_params(axis="x", labelbottom=False, length=0)
    axis.set_xlim(start, end + 1)
    axis.set_ylabel("Score (higher is better)", labelpad=7)
    axis.set_title("(b) Score profiles and resulting path profile", loc="left", pad=8)
    axis.yaxis.set_major_locator(MaxNLocator(nbins=5, integer=True))
    axis.set_axisbelow(True)
    axis.grid(axis="both", color="#e2e5e7", linewidth=0.65)
    axis.spines[["right", "top", "bottom"]].set_visible(False)
    axis.spines["left"].set_color("#777777")


def style_strip_axis(axis, start: int, end: int) -> None:
    ticks = list(range(start, end + 1, 30))
    if ticks[-1] != end:
        ticks.append(end)
    axis.set_xticks(ticks, [local_clock(value) for value in ticks])
    axis.set_xlim(start, end + 1)
    axis.set_ylim(-0.72, 1.64)
    axis.set_yticks([])
    axis.set_xlabel("Local departure time (EDT, 14 May 2026)", labelpad=7)
    axis.spines[["left", "right", "top"]].set_visible(False)
    axis.spines["bottom"].set_color("#777777")
    axis.grid(axis="x", color="#e2e5e7", linewidth=0.65, zorder=0)


def render_strip(axis, data: dict) -> None:
    start, end = data["interval_start"], data["interval_end"]
    cells = data["selected_cells"]
    right_edges = [cells[index + 1]["start"] if index + 1 < len(cells) else end + 1
                   for index in range(len(cells))]
    short_positions = {
        1: (start + 31, 1.08),
        3: (start + 50, 1.45),
        4: (start + 70, 1.08),
        6: (start + 101, 1.12),
    }
    for index, (cell, right) in enumerate(zip(cells, right_edges)):
        path_id = cell["path_id"]
        left = cell["start"]
        width = right - left
        axis.barh(
            0.42, width, left=left, height=0.58,
            color=COLORS[path_id - 1], edgecolor="white", linewidth=0.9, zorder=3,
        )
        label = (
            f"P{path_id}\nS={float(cell['average_score']):.0f}\n"
            f"T={float(cell['average_travel_time']):.3f} min"
        )
        center = left + width / 2
        if width >= 14:
            axis.text(
                center, 0.42, label, ha="center", va="center", fontsize=6.7,
                linespacing=1.05,
                color="white" if path_id in (1, 3, 4) else "#111111",
                fontweight="bold", zorder=5,
            )
        else:
            label_x, label_y = short_positions.get(index, (center, 1.08))
            axis.annotate(
                label, xy=(center, 0.73), xytext=(label_x, label_y),
                ha="center", va="bottom", fontsize=6.25, linespacing=1.02,
                bbox={"boxstyle": "round,pad=0.18", "facecolor": "white",
                      "edgecolor": COLORS[path_id - 1], "linewidth": 0.8},
                arrowprops={"arrowstyle": "-", "color": COLORS[path_id - 1],
                            "linewidth": 0.8, "shrinkA": 0, "shrinkB": 1},
                zorder=8,
            )
    axis.text(
        (start + end) / 2, -0.48,
        "maximize transit-corridor affinity subject to T ≤ B",
        ha="center", va="top", fontsize=7.2, color="#333333",
    )


def render_score_panel(score_axis, strip_axis, data: dict) -> None:
    departures = list(range(data["interval_start"], data["interval_end"] + 1))
    profiles = data["candidate_profiles"]
    plotted_values = []
    values_by_path = {}
    for profile in profiles:
        visible = [any(left <= minute <= right for left, right in profile["domain"])
                   for minute in departures]
        values = [score_at(profile, minute) if active else 0
                  for minute, active in zip(departures, visible)]
        values_by_path[profile["path_id"]] = dict(zip(departures, values))
        step_runs(
            score_axis, departures, values, visible,
            color=COLORS[profile["path_id"] - 1], linewidth=1.55,
            alpha=0.72, zorder=3,
        )
        plotted_values.extend(value for value, active in zip(values, visible) if active)
    for cell in data["selected_cells"]:
        path_id = cell["path_id"]
        selected_departures = list(range(cell["start"], cell["end"] + 1))
        selected_values = [values_by_path[path_id][minute] for minute in selected_departures]
        if any(value == 0 for value in selected_values):
            raise ValueError("selected path lies outside its supported score domain")
        score_axis.step(
            selected_departures + [selected_departures[-1] + 1],
            selected_values + [selected_values[-1]], where="post",
            color=COLORS[path_id - 1], linewidth=3.15,
            solid_capstyle="butt", zorder=7,
        )
    render_strip(strip_axis, data)
    score_axis.legend(
        handles=[Line2D([0], [0], color=COLORS[index], linewidth=2.2,
                        label=f"P{index + 1}") for index in range(len(profiles))],
        loc="upper right", ncol=3, frameon=True, framealpha=0.96,
        facecolor="white", edgecolor="#c9ced2", handlelength=2.1,
        columnspacing=0.9,
    )
    score_axis.text(
        0.985, 0.79,
        f"Fixed travel-time budget B = {float(data['budget_minutes']):.3f} min",
        transform=score_axis.transAxes,
        ha="right", va="top", fontsize=7.4, color="#333333",
    )
    span = max(plotted_values) - min(plotted_values)
    padding = max(4.0, span * 0.10)
    score_axis.set_ylim(max(0.0, min(plotted_values) - padding), max(plotted_values) + padding)
    start, end = departures[0], departures[-1]
    style_score_axis(score_axis, start, end)
    style_strip_axis(strip_axis, start, end)
    switches = [cell["start"] for cell in data["selected_cells"][1:]]
    for departure in switches:
        for axis in (score_axis, strip_axis):
            axis.axvline(departure, color="#aeb5ba", linewidth=0.7,
                         linestyle=(0, (1.2, 3.0)), alpha=0.68, zorder=1)
    best = data["interval_best"]
    best_departure = best["departure"]
    for axis in (score_axis, strip_axis):
        axis.axvline(best_departure, color=INTERVAL_BEST_GREEN, linewidth=1.05,
                     linestyle=(0, (4, 3)), alpha=0.9, zorder=9)
    score_axis.scatter(
        [best_departure], [best["score"]], s=38,
        color=INTERVAL_BEST_GREEN, edgecolor="white", linewidth=0.7, zorder=30,
    )
    score_axis.annotate(
        f"Interval-best:\n{local_clock(best_departure)}, P{best['path_id']}",
        xy=(best_departure, best["score"]),
        xytext=(best_departure + 8, best["score"] + 2.5),
        ha="left", va="center", fontsize=7.5, color=INTERVAL_BEST_GREEN,
        arrowprops={"arrowstyle": "-", "color": INTERVAL_BEST_GREEN, "lw": 0.9},
        zorder=31,
    )


def render(data: dict, output_dir: Path, formats: tuple[str, ...]) -> list[Path]:
    mpl.rcParams.update({
        "font.family": "DejaVu Sans", "font.size": 9,
        "axes.titlesize": 10.5, "axes.labelsize": 9.5,
        "legend.fontsize": 8, "pdf.fonttype": 42, "ps.fonttype": 42,
    })
    figure, axes = plt.subplots(
        1, 2, figsize=(14.2, 6.1),
        gridspec_kw={"width_ratios": [1.18, 1.0], "wspace": 0.13},
    )
    map_ax, time_ax = axes
    map_ax.add_collection(LineCollection(
        data["background_segments"], colors="#d3d7da", linewidths=0.34,
        alpha=0.9, zorder=1,
    ))
    map_ax.add_collection(LineCollection(
        data["fastest_segments"], colors="#31363b", linewidths=2.1,
        linestyles="dashed", alpha=0.9, zorder=2,
    ))
    for index, segments in enumerate(data["path_segments"]):
        map_ax.add_collection(LineCollection(
            segments, colors=COLORS[index], linewidths=2.7,
            alpha=0.92, zorder=4 + index,
        ))
    map_ax.scatter(*data["source_xy_m"], marker="o", s=76,
                   facecolor="white", edgecolor="#111111", linewidth=1.4, zorder=20)
    map_ax.scatter(*data["destination_xy_m"], marker="X", s=92,
                   facecolor="#111111", edgecolor="white", linewidth=0.8, zorder=20)
    xmin, ymin, xmax, ymax = data["bounds_xy_m"]
    map_ax.set_xlim(xmin, xmax)
    map_ax.set_ylim(ymin, ymax)
    map_ax.set_aspect("equal")
    map_ax.set_axis_off()
    map_ax.set_title("(a) Local NYC road context and profile paths", loc="left", pad=8)
    legend = [Line2D([0], [0], color=COLORS[i], linewidth=2.7, label=f"P{i + 1}")
              for i in range(len(data["path_segments"]))]
    legend.append(Line2D([0], [0], color="#31363b", linewidth=2.0,
                         linestyle="--", label=f"Fastest at {local_clock(data['fastest_label_minute'])}"))
    legend.extend([
        Line2D([0], [0], marker="o", markerfacecolor="white",
               markeredgecolor="#111111", linestyle="None", markersize=7,
               label="Source"),
        Line2D([0], [0], marker="X", color="#111111", linestyle="None",
               markersize=7, label="Destination"),
    ])
    map_ax.legend(handles=legend, loc="lower left", ncol=2, frameon=True,
                  framealpha=0.94, borderpad=0.6, columnspacing=1.1,
                  handlelength=2.4)
    map_ax.annotate(
        "N", xy=(0.955, 0.94), xytext=(0.955, 0.84),
        xycoords="axes fraction", ha="center", va="center", fontsize=9,
        arrowprops={"arrowstyle": "-|>", "color": "#222222", "lw": 1.2},
    )
    figure.suptitle(f"NYC shuttle query {data['query_id']}", fontsize=13, y=0.995)
    figure.subplots_adjust(left=0.025, right=0.99, bottom=0.15, top=0.90)
    position = time_ax.get_position()
    time_ax.remove()
    panel_width = position.width - 0.003435
    strip_height = position.height * 0.24
    gap_height = position.height * 0.07
    score_height = position.height * 0.69
    strip_axis = figure.add_axes([position.x0, position.y0, panel_width, strip_height])
    score_axis = figure.add_axes([
        position.x0, position.y0 + strip_height + gap_height,
        panel_width, score_height,
    ])
    render_score_panel(score_axis, strip_axis, data)
    figure.canvas.draw()
    tight = figure.get_tightbbox(figure.canvas.get_renderer())
    established_left = tight.x0 - 0.1
    established_top = tight.y1 + 0.1
    def anchored_bbox(width_inches: float, height_inches: float) -> Bbox:
        return Bbox.from_bounds(established_left, established_top - height_inches,
                                width_inches, height_inches)
    output_dir.mkdir(parents=True, exist_ok=True)
    outputs = []
    for extension in formats:
        target = output_dir / f"nyc-case-study.{extension}"
        if extension == "png":
            width, height = data["export_dimensions"]["png_pixels"]
            figure.savefig(target, dpi=300,
                           bbox_inches=anchored_bbox(width / 300.0, height / 300.0),
                           pad_inches=0)
        else:
            width, height = data["export_dimensions"]["pdf_points"]
            figure.savefig(target,
                           bbox_inches=anchored_bbox(width / 72.0, height / 72.0),
                           pad_inches=0)
        outputs.append(target)
    plt.close(figure)
    return outputs


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path,
                        default=PACKAGE / "figures" / "regenerated")
    parser.add_argument("--formats", nargs="+", choices=("pdf", "png", "svg"),
                        default=("pdf", "png", "svg"))
    arguments = parser.parse_args()
    data = load_data()
    for path in render(data, arguments.output_dir, tuple(dict.fromkeys(arguments.formats))):
        print(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
