# Reproducibility and provenance

This repository is an extracted publication codebase, not a rewrite of the search algorithms. It retains separate Maven modules because the frozen PACE-B and baseline source trees contain different versions of shared exact temporal internals. Merging those internals would change executable behavior. `provenance/source_selection_manifest.json` lists the selected source files and relevant root/test classes; `provenance/source_hashes.sha256` checks the packaged module files. Original development repositories and their experiment directories are **not runtime dependencies**.

## Frozen implementation identities

The paths below identify historical local evidence in the source development checkout. They are provenance labels, not input paths used by the publication commands.

| Engine | Historical source evidence | Frozen executable SHA-256 | Verification and limit |
| --- | --- | --- | --- |
| PACE-X reference | Historical reference source in the development tree; no independently frozen historical executable identified | **Unavailable** | Experiment A stored route validity confirmed; exact replay disagreed on preference score in 54/60, winner in 37/60, arrival in 0/60. No global-optimum certification. |
| PACE-B EGPI | Archived final EGPI source snapshot (external to this package); SHA-256 `38a46cc7520fe740a13504c186f051cbdbc29a86e0aa2d03a9b7f93b8cea858c` | `1b116792df8095c01322b424a546729aab0cb8bc3a58a7870e69db0577611c4f` | `pace-b-egpi-v1 --egpi-engine-profile anytime`, engine `ANYTIME_V1`. Extracted source compiled to byte-identical class files, 474/474. |
| iSCOPE-Profile and SICSP-Pref, v0 | Archived v0 baseline source snapshot (external to this package), indexed by `provenance/source_snapshot_index.json` | `d11ed5025646072ce6f2dc73b313015959c0aa233b622457842761a19cebbe22` | Extracted source compiled to byte-identical class files, 471/471. |
| Indexed THop final | Sibling development checkout `I-PC-Max-thop-dev`, source HEAD `70eb5f0dd2a5e0ea3470f501f918ee2a9ae60f64` plus recorded working-tree files; `icde2027_thop_final_execution_v1/provenance/EXPERIMENT_A_PRELAUNCH_PROVENANCE.json` | `d81a80e6ad39c3c24353570cd75948efadca67fc938b9f5d1f980a4a759cb460` | Final indexed BTHP-v2/PTHP-v4; extracted source compiled to byte-identical class files, 505/505. NY-CTRL Experiment A 180/180 accepted; large-network indexed execution unverified. |

The frozen PACE-B and baseline development HEAD was `f3e0bbe208fa82b5963786fd6e7876eb8843a4d3`, but relevant sources were untracked or modified. The archived source snapshots, not that Git commit alone, are authoritative. The indexed THop evidence likewise records a sibling HEAD plus working-tree changes. The publication `source_selection_manifest.json` records exactly what was extracted. A class-byte match demonstrates successful reconstruction of the archived Java classes; it does not, by itself, certify every runtime result.

After that comparison, internal development-round labels were removed from identifiers, comments, configuration values, and metadata strings (for example the exact-kernel mode names, the EGPI engine-profile names, and the legacy `pace-b` selector alias). The renames do not change algorithmic behavior and the full module test suites pass, but the renamed classes are no longer byte-identical to the archived JARs.

## Source and test inventory

| Module | Principal source packages/classes | Relevant retained tests | Why separated |
| --- | --- | --- | --- |
| `modules/pace` | `edu.ipcmax.experiments`: `PaceBench`, `AlgorithmRegistry`, `EgpiExperimentAlgorithm`, reference algorithm adapter; `edu.ipcmax.core` exact temporal, route, and preference utilities | `EgpiAnytimeIntegrationTest`, `EgpiBalancedReplayEquivalenceTest`, `EgpiCompletionAwareCommitTest`, `EgpiExactCompositionPropertyTest`, `ExactPreferenceProfileTest` | Holds the final EGPI kernel and reference path without substituting the legacy `pace-b` registry default. |
| `modules/baselines` | `edu.ipcmax.experiments`: `SicspPrefExperimentAlgorithm`, `IScopePartialLabelAlgorithm`, runner/registry; baseline-specific exact utilities | `SicspPreferenceEngineTest`, `SicspPrefTemporalContractTest`, `IScopePartialLabelOracleTest`, `PreferenceAdaptersCrossOracleTest` | Preserves frozen v0 baseline semantics and its own exact internals. |
| `modules/indexed_thop` | `edu.ipcmax.experiments.ThopFullNetworkQueryBatch`, `edu.ipcmax.baselines` index builder/loader, partition and witness utilities | `PartitionedTHopIndexTest`, `ThopFullNetworkQueryBatchTest`, `ThopRouteWitnessesTest`, `BoundaryTHopIndexFingerprintTest` | Uses the final indexed implementation; index-free solver is not a public fallback. |

The source hash list gives the exhaustive packaged file list; the selection manifest records source-selection counts, test roots, and exclusions. The retained tests target temporal arithmetic, preference scoring, route witnesses, EGPI completion-aware commits, and indexed THop serialization. Test pass status for this independent package should be read from its actual local build log or continuous integration, not inferred from the historical experiment result.

## Input identities and semantics

The bundled NY-CTRL data lives at `data/NY-Exact-radius/` and is byte-identical to the verified controlled Experiment A payload. It has 64 vertices, 142 directed arcs, and 33 score-bearing arcs. The recorded dataset payload checksum is `cae3f9debec83eb1301892a51eb0ce5a8de4a52650310bc53e1bf1878f5ccdaa`; the graph-semantic checksum is `1f83a9e18b7c3475ab617df32f7df57189375dfebd78087ca82c84a1a36871ca`. The graph and all query-file SHA-256 values are in [DATASETS.md](DATASETS.md), [QUERY_FORMAT.md](QUERY_FORMAT.md), and root `checksums.sha256`.

The public `NY-CTRL` selector maps to internal dataset ID `NY-Exact-radius`. Frozen query JSONL bytes are unchanged: 60 controlled queries and 50 each for NY, FLA, CAL, and USA. Do not regenerate IDs, resample rows, rewrite numeric fields, or dereference historical absolute paths in query metadata. The NY/FLA/CAL witness contract records 1e-12 minute tick equality with closed interval endpoints. The author's road-weight conversion for these inputs is `minutes = DIMACS_weight / 6000`; it is an experimental normalization, not a claim about official DIMACS units.

Use the included checksums to verify packaged inputs:

```bash
sha256sum --check checksums.sha256
```

Only NY-CTRL is bundled. A full-network rerun needs externally obtained NY, FLA, CAL, or USA data under a caller-provided `--data-root`. The datasets are available from the [project Google Drive folder](https://drive.google.com/drive/folders/1ukHbqyAsDzNk76fiziH4HeHLSd9VKOjr?usp=drive_link). The prepared indexed THop artifact for NY-CTRL has its own fingerprint and provenance in `prepared/NY-CTRL/INDEX_PROVENANCE.json`; do not reuse it for a graph with a different checksum.

## Execution profiles

| Historical experiment | Recorded setting | Interpretation |
| --- | --- | --- |
| PACE-B primary | 24 threads; 10-second query budget and 2-second cleanup; `ANYTIME_V1` | Capped validated preference envelope; native coverage and certification still matter. |
| iSCOPE-Profile | `iscope-opt1`, 5-second cap | Preference-adapted baseline result under its native termination semantics. |
| SICSP-Pref | `sicsp-pref`, 600-second cap | ICSP-derived preference result under its native termination semantics. |
| Indexed THop Experiment A | Final BTHP-v2/PTHP-v4 artifact; 180/180 accepted NY-CTRL executions | Fastest travel-time profile plus post-evaluated preference. |

The public `configs/*.json` record effective algorithm settings. PACE-X historical records preserve a 256000 MB memory limit but do not identify the original JVM `-Xmx`; the public adapter uses the corresponding 250 GiB maximum heap as a documented translation, not a recovered historical JVM flag. The two public scripts default to one measured execution per frozen query. The current PACE-family and baseline Java configurations request one warmup run; indexed THop performs its native warmup before one measured indexed execution. Documented overrides are allowed. This convenience run is not automatically equivalent to the manuscript's repetition schedule, resource isolation, or timing conditions. Record all overrides in output metadata. A new run can test current source and input behavior; it cannot retroactively certify the historical PACE-X values.

## Standalone verification

A clean audit should establish all of the following from the publication repository root:

1. `mvn -q package` succeeds without the original development checkout, hidden classpath files, or symlinks.
2. `sha256sum --check checksums.sha256` and `sha256sum --check provenance/source_hashes.sha256` pass.
3. Both public `--help` commands work and reject unknown algorithms, datasets, and query IDs clearly.
4. One frozen NY-CTRL query per runnable algorithm produces a result with matching input identity, native status, and preserved route/profile evidence.
5. A selected single-query result and the same row of an all-query run use the same engine and JSON schema. Compare results with cap and scheduling variability in mind.
6. No complete NY, FLA, CAL, or USA network payload is inside the repository.
7. No runtime path refers to the development repository. Historical paths in provenance or frozen metadata are exempt because they are never dereferenced.

The independent package validation report should state actual command outcomes and any blocked algorithm or index. Historical timing comparability requires the original hardware, JVM configuration, repetition schedule, data state, and resource isolation; portable correctness alone does not guarantee matching milliseconds.

## Known evidence limits

- PACE-X's historical executable hash is unavailable, and its Experiment A score/winner discrepancies remain unresolved.
- The indexed THop package is verified for NY-CTRL. No indexed USA success or general large-network coverage is asserted.
- The complete NY, FLA, CAL, and USA datasets are external, not bundled, and distributed through the [project Google Drive folder](https://drive.google.com/drive/folders/1ukHbqyAsDzNk76fiziH4HeHLSd9VKOjr?usp=drive_link).
- Source, derived-data, and upstream attribution permissions are unresolved; see [LICENSE_STATUS.md](LICENSE_STATUS.md).
- The NYC manuscript case study has separate input and figure provenance in [case_study/NYC/README.md](../case_study/NYC/README.md). Its archived `nyc-query-v2` row needs a historical V2 case-study runner and the external NYC-REAL-V2 graph; it is not a query accepted by the general `PaceBench` reader or a reproducible PACE-B run.