# Output format

The two public commands call `scripts/public_runner.py` and use one per-query JSON adapter. Results preserve both a common interpretation in `shared` and the complete original algorithm record in `native`. A common field does not erase the solver's own coverage, cap, or exactness semantics.

## File layout

`run_all_queries.sh` writes one JSON per frozen query, plus a CSV summary and run metadata:

```text
results/<algorithm>/<dataset>/<run-id>/
  queries/<safe-query-id>.json
  artifacts/<safe-query-id>/
    query.jsonl
    stdout.log
    stderr.log
    native.jsonl or native_thop/...
    profiles/...              # if emitted by the Java runner
  summary.csv
  run_metadata.json
```

`run_single_query.sh` writes `result.json` and `run_metadata.json` beneath `results/<algorithm>/<dataset>/single/<safe-query-id>/<run-id>/`, with the same per-query artifacts directory. It writes no `summary.csv` and runs no other query. Both commands print the actual run directory. The file-safe name includes a short hash; the full original query ID remains inside the JSON.

## Per-query JSON: `pace-public-query-v1`

| Top-level field | Meaning |
| --- | --- |
| `schema_version` | Literal schema identifier `pace-public-query-v1`. |
| `algorithm`, `dataset`, `query_id` | Public selectors and exact frozen query ID. |
| `query` | Complete original frozen query object, including source, destination, interval, budget, and metadata. |
| `shared` | Common interpretation of native status, validation availability, objective, coverage, exact score, timing, and profile pointers. |
| `provenance` | Config/JAR/query-row hashes, Java exit code, artifacts path, and index provenance where applicable. |
| `native` | Full Java `PaceBench` record, or indexed THop receipt and raw result. |

The `shared` object contains `objective`, `native_status`, `native_completed`, `validated_result_available`, `certified`, `exactness_scope`, `coverage`, `query_processing_ns`, `preference_score_exact`, profile and witness pointers, and `failure_reasons`. For `PaceBench` engines it also exposes `preference_score_display`. Missing values are JSON null or absent according to the native record; they are not converted to zero.

The pointers identify where full data sits inside `native`:

| Algorithm family | Preference profile | Travel-time profile | Route witnesses |
| --- | --- | --- | --- |
| PACE-X, PACE-B, ICSP, iSCOPE | `native.output.profile.cells` | Travel-time values in those cells | Paths in those cells |
| Indexed THop | `native.raw.thop_result.preference_cells` | `native.raw.thop_result.travel_time_pieces` | `native.raw.thop_result.routes` |

Exact rational temporal values remain in the native lossless serialization when the engine emits them. `shared.preference_score_exact` is an exact summary value when available; it is not a replacement for the full profile. `query_processing_ns` is the native query-processing measurement in nanoseconds, not a wall-clock duration that includes graph loading. Consult the native phase timings and run metadata for preprocessing and loading evidence.

`shared.certified` must be interpreted with `shared.exactness_scope` and `shared.objective`. For indexed THop, `ACCEPTED` means complete witness validation plus sampled exact fastest-path checks; the adapter leaves `certified=false` because that evidence is not a full formal optimality certificate. Its post-evaluated preference is **not** a preference maximum. A validated capped PACE-B envelope can be available without global certification. An unresolved departure interval is not an infeasible interval. Historical PACE-X exactness uncertainty remains regardless of this adapter's field layout.

## Summary and run metadata

Only the all-query command writes `summary.csv`. It has one row per attempted frozen query in file order, including failures and unresolved results, with these columns:

```text
query_id,source,destination,native_status,validated_result_available,
certified,query_processing_ns,preference_score_exact,result_file
```

Empty numeric CSV cells mean unavailable values. `run_metadata.json` records the command's mode, selected algorithm/dataset, run ID, start/end time, planned/completed query counts, query-file and dataset-manifest hashes, built JAR/config hashes, effective configuration, user overrides, index provenance, and links to individual result files. The artifact directories preserve stdout, stderr, native JSONL or indexed THop receipts/raw files, and any profile files emitted by the engine.

The all-query and single-query commands invoke the same engine mapping and normalization code. With identical frozen input, data, executable, and effective settings, their JSON schema is the same. Deadline-driven searches may produce different valid partial envelopes across runs; compare native caps, coverage, and timing conditions before comparing scores.

## Error and evidence limits

An invalid algorithm or dataset, unknown query ID, missing external network, missing compatible THop index, or unsupported indexed THop timeout override is rejected before query execution. A query-level Java failure is retained as a result with its native or adapter error record. A missing score remains null. Unsupported indexed large-network operation is not represented as a measured timeout.

See [ALGORITHMS.md](ALGORITHMS.md) for objective and certification distinctions and [REPRODUCIBILITY.md](REPRODUCIBILITY.md) for source identities and historical evidence limits.