# Algorithm identities and interpretation

The public selectors are adapters around existing frozen implementations. They do not change the underlying candidate order, temporal arithmetic, budget interpretation, scoring, or termination policy. Each run records the selected engine and effective overrides in its metadata.

| Public selector | Original implementation/selector | Frozen evidence | Objective |
| --- | --- | --- | --- |
| `pace-x` | Historical PACE-X reference implementation | Historical Experiment A measurements; executable hash unavailable | Preference-route reference, with certification caveat below |
| `pace-b` | `pace-b-egpi-v1` with `--egpi-engine-profile anytime` | `ANYTIME_V1` frozen source and executable | Preference envelope |
| `icsp` | `sicsp-pref` (SICSP-Pref) | Frozen v0 source and executable | Preference-maximization adaptation of ICSP |
| `thop` | Final indexed `ThopFullNetworkQueryBatch`, BTHP-v2/PTHP-v4 | Indexed THop final source and executable | Fastest travel-time profile; preference post-evaluated |
| `iscope` | `iscope-opt1` (iSCOPE-Profile) | Frozen v0 source and executable | Preference-profile adaptation of iSCOPE |

## PACE-X

PACE-X is the historical experimental reference implementation. Its stored Experiment A paths pass route-validity checks, but the independent exact replay audit does **not** reproduce the recorded preference score for 54 of 60 queries or the recorded winning route for 37 of 60. Arrival values agree for all 60. The historical executable SHA-256 was not recorded. These facts prevent certification of those historical values as globally optimal preference results. The source is preserved without a speculative search or pruning repair. A new output from this implementation is a reference result under its native status and should not be labeled a certified optimum without an independent proof.

## PACE-B: EGPI

The public `pace-b` selector explicitly invokes the final Envelope-Guided Pivot Insertion engine, `ANYTIME_V1`. The older original registry selector named `pace-b` pointed at a legacy `PaceExperimentAlgorithm`; that legacy mapping is **not** the publication engine.

The engine uses complete-route pivot insertion, an exact temporal route evaluator, envelope-guided candidate priority, separate exploration and exploitation frontiers, incremental exact preference-envelope updates, and completion-aware publication of validated completed evaluations. Its frozen identity records:

- Commit policy `COMPLETION_AWARE_V1`.
- Envelope implementation `CANONICAL_LAZY_V3`.
- Exact kernel `LEHMER` and replay `BALANCED_V1`.
- Experiment query deadline 10 seconds and cleanup allowance 2 seconds.
- 24 worker threads in the primary experiment.

A validated envelope can be returned at a work or time cap. Validation of its reported routes and scores is distinct from proof that no better route exists. Preserve native cap, coverage, and certification fields when comparing runs.

## ICSP-derived SICSP-Pref

The public `icsp` selector invokes the `sicsp-pref` implementation. It adapts interval-constrained shortest-path search to this preference-path comparison, retaining the frozen travel-time budget and the shared exact preference evaluator. It is not a generic shortest-path substitute. Its Experiment A cap was 600 seconds. Interpret its native completion, feasible domain, and returned profile according to the native result fields; a timeout or partial result is not an infeasible result.

## Indexed THop

The public `thop` selector invokes the final indexed THop preference-evaluation path built around `ThopFullNetworkQueryBatch`. The final index format is identified as BTHP-v2/PTHP-v4. This runner computes an indexed time-dependent **fastest travel-time** path/profile, reconstructs witnesses, and then scores those witnesses using the common preference contract. Its post-evaluated preference score is not the objective optimized by THop.

The older `ThopPartitionedExperimentAlgorithm` uses an earlier PTHP-v3 format and exposes only the travel-time profile; it is not the final indexed comparison implementation. The index-free solver is excluded. If a compatible index is missing, the public command must stop with an explicit error.

The verified final indexed evidence is 180/180 accepted Experiment A executions on NY-CTRL. That establishes the indexed implementation for the controlled network. It does not establish indexed NY, FLA, CAL, or USA operation, and no indexed USA result is claimed.

## iSCOPE-Profile

The public `iscope` selector invokes `iscope-opt1`, the actual Experiment A preference-profile adaptation. The adapted search, bounds, travel-budget treatment, termination, and validated result output are preserved. Its frozen Experiment A cap was 5 seconds. As with other bounded search baselines, distinguish a completed validated result from proof of complete temporal coverage or global optimality.

## Comparing outputs

All methods receive the same frozen query row and data representation. The publication result adapter makes query identity, runtime, native status, available profiles, route witnesses, coverage, and provenance comparable. The native algorithm fields remain available alongside the common fields so distinct statuses are not collapsed into `SUCCESS` or `FAILURE`. A missing score is null, not zero. For evidence provenance and exact source/executable identities, see [REPRODUCIBILITY.md](REPRODUCIBILITY.md).