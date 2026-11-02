# NYC data-calibrated shuttle-routing case study

This directory preserves the qualitative NYC example in the ICDE 2027 PACE manuscript. It is a compact figure-reproduction package. The full NY road graph and the NYC DOT/MTA-derived temporal graph are not included.

## Query and result

The selected query is `NYC-Q012-MORNING-R20`: NY graph vertices **146634 → 151351**, 07:00–09:00 EDT on 14 May 2026, at every integer minute from 420 through 540. Its fixed travel-time budget is **8.382654224124 minutes** (`rho=0.2`). The score is a transit-corridor affinity proxy: for each directed road edge and 15-minute bin, it is the number of distinct active MTA route IDs whose exactly mapped GTFS shape uses that edge, capped at 15.

The corrected V2 30-second diagnostic completed with five distinct selected paths and seven route switches. All 121 sampled departures have validated, budget-feasible selected paths. The interval-best candidate is P3 at 07:53 with score 86, but P3 is selected at only 16 departures. This is qualitative evidence about the need for a departure-time profile; it is excluded from the manuscript's five-second scalability statistics. The result's `exactness_scope` is `NOT_CERTIFIED`: route validity does not imply global preference optimality.

| File | Contents |
| --- | --- |
| `data/query.jsonl` | Exact selected line from the frozen 100-query NYC V2 manifest. |
| `data/result.jsonl` | Exact selected line from the completed 30-second diagnostic. |
| `data/validation.jsonl` | Exact selected line from the 121-point independent story/replay record. |
| `data/config.json` | Readable query and historical execution configuration. |
| `data/graph_manifest.json` | Manifest for the external processed NYC-REAL-V2 graph. |
| `data/provenance.json` | Original and included-file SHA-256 hashes, extraction checks, and geometry counts. |
| `data/figure_data.json.gz` | Only the local mapped road segments, five path geometries, score supports, selected cells, and plot metadata needed for the figure. |
| `figures/nyc-case-study.pdf` and `.png` | Byte-for-byte copies of the manuscript-matching original Role A figure. |
| `plots/plot_case_study.py` | Standalone Matplotlib plotter; it reads only files in this directory. |

The compressed plot data is about **117 KB**. It contains 11,108 local road segments, 68 fastest-path segments, and five selected routes. Map coordinates are EPSG:32618. It omits the 733,846-arc graph, the 32 MB source geometry Parquet file, and the 251 MB compressed travel-time-function file.

## Regenerate the figure

Use Python 3.10 or newer with a compatible Matplotlib installation. The pinned versions in `requirements.txt` match the verified rendering environment.

```bash
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
python plots/plot_case_study.py
```

The default output directory is `figures/regenerated/`; the archived originals are preserved. The script emits PDF, PNG, and SVG. To write elsewhere, pass `--output-dir PATH`. To select formats, use `--formats pdf png svg` with any subset.

The generated PNG is byte-for-byte identical to the archived PNG in the verified environment. Matplotlib embeds creation time in PDFs. To make the generated PDF byte-for-byte identical as well, set the archived creation epoch:

```bash
SOURCE_DATE_EPOCH=1786863573 python plots/plot_case_study.py --formats pdf png
```

Archived SHA-256 values:

```text
d6f786518548d98af7e3503ab0f1a8792d59fd4bc53b99645e73ca6ec1c89cb0  figures/nyc-case-study.pdf
ed3aa6b8ae0db6a824ec830f5b7d4d54bc748fe9fdf6b103ae048d52e913c7e0  figures/nyc-case-study.png
```

The plotter checks the included query, result, validation, and figure-data hashes before drawing. It does not recompute travel-time functions or rerun the routing algorithm.

## Historical execution and limits

The archived diagnostic used `edu.ipcmax.casestudy.nyc.NycShuttleCaseStudyBench` with the `EXACT_FIFO_AT_CONNECTED_CELL_MIDPOINT_ONE_STRUCTURE_V2` connector policy, `L=32`, `theta=2`, `Kf=16`, `Mb=1,000,000`, one PACE worker, eight fastest-profile baseline workers, and a 30-second query cap. The frozen historical JAR is identified in `data/provenance.json` by source location and SHA-256; it and the full graph are not included here. The source data came from the official NYC DOT traffic-speed and street-centerline records, MTA bus schedules, and official GTFS static shapes described in the manuscript.

The raw NYC `nyc-query-v2` JSONL row is accepted by that historical case-study runner. It is **not** accepted directly by the general `PaceBench --query-file` reader, which requires a different integer-versioned query schema. Converting the row for a current `PaceBench` run would create a new experiment. The current `pace-b-egpi-v1` implementation has a different algorithm identity and cannot be claimed to reproduce this archived selected profile.
