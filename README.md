# PACE: Constrained Preference Path Profiles

Research code for **PACE: An Efficient Framework for Constrained Preference Path Profiles in Large Time-Dependent Road Networks**, prepared for the ICDE 2027 manuscript. The problem is to evaluate paths over a departure-time interval under a travel-time budget and report how well feasible routes satisfy a time-dependent preference objective.

This repository packages the final PACE-B EGPI implementation, the historical PACE-X reference, preference-adapted SICSP and iSCOPE baselines, and **indexed** THop. The two public query commands use the same result adapter. See [algorithm identities and limitations](docs/ALGORITHMS.md) before interpreting comparisons.

## Algorithms

| Selector | Implementation | Objective and interpretation |
| --- | --- | --- |
| `pace-x` | Historical PACE-X reference | Preference-path reference; historical results have unresolved differences from independent exact replay and are **uncertified**. |
| `pace-b` | Final `ANYTIME_V1` Envelope-Guided Pivot Insertion (EGPI) | Validated preference envelopes under the documented search/time cap; a capped result does not imply global optimality. |
| `icsp` | `SICSP-Pref` | ICSP-derived preference-maximization adaptation, preserving the experimental travel-budget and exact scoring contract. |
| `thop` | Final indexed THop, BTHP-v2/PTHP-v4 | Fastest travel-time path/profile. Preference is evaluated afterward on the returned path; it is not the search objective. |
| `iscope` | `iSCOPE-Profile` | Preference-oriented adaptation of the experimental iSCOPE baseline. |

The verified indexed THop artifact covers the bundled **NY-CTRL** network. Indexed operation on NY, FLA, CAL, or USA is not established by this package. The commands must not switch to an index-free solver to fill that gap.

## Repository layout

| Path | Contents |
| --- | --- |
| `modules/` | Independent Java/Maven source modules for the frozen engines ; the shared publication adapter lives in `scripts/`. Separate modules retain distinct frozen exact kernels. |
| `data/NY-Exact-radius/` | Bundled 64-vertex, 142-arc NY-CTRL network. The public dataset name is `NY-CTRL`. |
| `queries/{NY-CTRL,NY,FLA,CAL,USA}/queries.jsonl` | Original frozen query rows: 60 for NY-CTRL and 50 per large network. |
| `configs/` | Algorithm-specific publication execution settings and provenance. |
| `case_study/NYC/` | Existing NYC manuscript case-study material and figure instructions; see its [README](case_study/NYC/README.md). |
| `docs/` | Dataset, query, algorithm, output, reproducibility, and attribution notes. |
| `results/` | Generated query output; ignored by Git. |

The bundled network is the only complete road-network dataset in this repository. The NYC figure can be regenerated from its included verified plot data. Re-running the historical case-study algorithm requires its external NYC-REAL-V2 graph and historical V2 runner, which are not part of the two general query commands.

## Requirements and build

The frozen Java sources use **Java 21**. The verified development environment used OpenJDK 21.0.7, Maven 3.6.3, Linux x86-64, Jackson 2.17.2, and JUnit 5.10.2. The public commands additionally require Bash and Python 3.10 or newer. A different machine may produce different runtimes even when it produces the same query inputs and output semantics.

From the repository root:

```bash
mvn -q package
./run_all_queries.sh --help
./run_single_query.sh --help
sha256sum --check checksums.sha256
```

The build must use only files in this repository and declared Maven dependencies. See [reproducibility details](docs/REPRODUCIBILITY.md) for frozen executable identities and source provenance. The [local validation report](docs/VALIDATION.md) records the completed build and smoke checks.

## Included dataset and frozen queries

`NY-CTRL` is the original controlled Experiment A road network: 64 vertices, 142 directed arcs, and 33 arcs with nonzero score functions. The selector maps to `data/NY-Exact-radius/` because the frozen query rows retain the internal dataset ID `NY-Exact-radius`. Its graph, temporal, preference, and manifest files are bundled byte-for-byte; [DATASETS.md](docs/DATASETS.md) records graph checksums and interpretation.

Experiment A's 60 NY-CTRL queries and Experiment B's 50 queries each for NY, FLA, CAL, and USA are in `queries/`. The files preserve source, destination, departure interval, budget, IDs, order, and metadata. [QUERY_FORMAT.md](docs/QUERY_FORMAT.md) lists the file hashes. Historical absolute paths in query metadata document provenance only; the runner resolves data through `--data-root`.

## Downloading the Large Road-Network Datasets

Large network payloads are **not** bundled or downloaded automatically. They are available from the project's Google Drive folder:

**[Road-network datasets (Google Drive)](https://drive.google.com/drive/folders/1ukHbqyAsDzNk76fiziH4HeHLSd9VKOjr?usp=sharing)**

| Dataset | Required extracted directory |
| --- | --- |
| NY | `NY/` |
| FLA | `FLA/` |
| CAL | `CAL/` |
| USA | `USA/` |

Download the datasets from that folder and put the directories under one external root, for example `/path/to/downloaded/datasets/NY/`, and pass that root with `--data-root`. Each directory must contain the graph, temporal, preference, and manifest payload expected by the loader. No source edit is needed. The frozen large-network **query files** remain in this repository.

## Run every frozen query

The command executes each listed query in its frozen file order. The default is one measured run per query; PACE-family and baseline Java configurations currently request one warmup run before the measured execution. Indexed THop performs its native warmup before the measured indexed query. This is a convenience invocation, not a recreation of every paper repetition.

```bash
./run_all_queries.sh --algorithm pace-b --dataset NY-CTRL
./run_all_queries.sh --algorithm icsp --dataset NY-CTRL
./run_all_queries.sh --algorithm iscope --dataset NY-CTRL
./run_all_queries.sh --algorithm pace-x --dataset NY-CTRL
./run_all_queries.sh --algorithm thop --dataset NY-CTRL

./run_all_queries.sh --algorithm pace-b --dataset NY \
  --data-root /path/to/downloaded/datasets
```

Both commands accept `--query-root`, `--output-dir`, `--threads`, and `--timeout-seconds` (alias `--timeout-s` where supported). An override is recorded in run metadata. Algorithm-specific frozen caps and worker configurations are documented in [ALGORITHMS.md](docs/ALGORITHMS.md); do not interpret a generic override as the published experimental setting.

Indexed THop needs a compatible index. If the required artifact is absent, the command reports that condition rather than using the index-free implementation. Current indexed verification is restricted to NY-CTRL.

## Run one frozen query

Choose an **existing** ID from the selected JSONL file. For example, the first NY-CTRL frozen query is:

```bash
./run_single_query.sh \
  --algorithm pace-b \
  --dataset NY-CTRL \
  --query-id NY-FINAL-NY-FINAL-P001-C510-B005
```

The single-query command uses the same implementation and per-query output schema as the all-query command. It does not execute the rest of the cohort. An unknown query ID is an error.

## Results

Every query has its own JSON result and preserved native output. A run also writes a summary CSV and run metadata. The common JSON carries the query identity, algorithm, native termination information, elapsed query-processing time, preference and travel-time profiles when available, route witnesses, and provenance. Status, temporal coverage, result validity, and certification must be read separately. Missing or unresolved scores remain null; they are never converted to zero. See [OUTPUT_FORMAT.md](docs/OUTPUT_FORMAT.md) for the field contract and directory layout.

## Reproducing the manuscript evidence

| Study | Network/query file | Interpretation |
| --- | --- | --- |
| Experiment A | Bundled NY-CTRL, 60 frozen queries | Controlled five-algorithm comparison; consult each algorithm's certified-result and cap semantics. |
| Experiment B | External NY, FLA, CAL, USA; 50 frozen queries each | Large-network evaluation where the required implementation and artifacts are available. Do not treat an unsupported indexed THop dataset as a measured failure. |

The manuscript's historical campaigns used algorithm-specific deadlines, repeated measurements, fixed hardware, and frozen executables. The two public commands default to one measured execution per query, with the configured warmup behavior described above, and cannot by themselves reproduce every historical timing condition. [REPRODUCIBILITY.md](docs/REPRODUCIBILITY.md) records the frozen identities, input hashes, known evidence limits, and what a new run can establish.

## NYC Case Study

The [NYC case-study directory](case_study/NYC/README.md) preserves the actual manuscript example query, its validated diagnostic, and original figure. Its standalone plotter regenerates PDF, PNG, and SVG from included verified figure data without rerunning routing. The historical query uses a distinct `nyc-query-v2` schema and a V2 case-study runner; it cannot be passed directly to the two general query commands or presented as a PACE-B reproduction. A full historical rerun requires the external NYC-REAL-V2 graph and that runner. No complete NYC road network is bundled here.

## Citation

Bibliographic metadata is pending. Update this template after publication:

```bibtex
@misc{pace_icde2027,
  title  = {PACE: An Efficient Framework for Constrained Preference Path Profiles in Large Time-Dependent Road Networks},
  author = {Authors to be provided},
  year   = {2027},
  note   = {ICDE 2027 manuscript; update with publication details}
}
```

## License and attribution

The source checkouts and frozen snapshots inspected for this package did **not** contain a top-level `LICENSE` or `NOTICE` that grants redistribution or relicensing rights. No license is invented here. Review [LICENSE_STATUS.md](docs/LICENSE_STATUS.md) and resolve author and upstream dataset permissions before public release. Third-party Maven dependencies retain their own licenses and notices.