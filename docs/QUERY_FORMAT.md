# Frozen query files

Each `queries/<dataset>/queries.jsonl` is a byte-for-byte copy of a frozen experimental query manifest. One JSON object occupies each line. The file order is the original experiment order, and query IDs are unique within each file. The execution scripts should select by exact `query_id` and preserve the full row; they must not resample, rewrite, or infer a query.

| Public selector | Rows | SHA-256 of `queries.jsonl` | Frozen source family |
| --- | ---: | --- | --- |
| `NY-CTRL` | 60 | `88151afc2d77e69e510cd66c54383fb5cfc3fa3608e50834cdadb60e3d03a990` | `q_input_frozen.jsonl` |
| `NY` | 50 | `ca081e68a239e53fe2a4ddee8851dc873bcef4b0d81ae40c4019b955394cb7a6` | `icde2027_oneday_verified_v3` |
| `FLA` | 50 | `29ef21c5d8cf649ea2115aac4db212e3b73e33141daa6f30d3a5253e1ab81e50` | `icde2027_oneday_verified_v3` |
| `CAL` | 50 | `da24ed9ba88542bd90221485291b68b4df0d63b72a632da4c4c0053f857ce2eb` | `icde2027_oneday_verified_v3` |
| `USA` | 50 | `26fde5ab58eb7246af4408b26e571fb9d6ca8686e31a0d40b6b4cb536280569b` | `icde2027_m5_v2_usa_local_v1` |

Every row includes `query_id`, `dataset_id`, `source`, `destination`, `interval_start`, `interval_end`, `budget`, `budget_policy`, `query_seed`, and metadata. Preserve the exact numeric fields and metadata: they record the travel-budget derivation, scoring context, and any frozen witness evidence. The NY-CTRL rows use the internal dataset ID `NY-Exact-radius`, even though the public selector is `NY-CTRL`.

NY-CTRL contains 20 queries at each 5-, 10-, and 15-minute budget and 30 queries in each 120-minute morning and evening departure window. The NY, FLA, and CAL query family was released as `COMPLETE_150_OF_150_EXACT_WITNESS_VERIFIED_AND_JAVA_PARSER_VALID`, with closed departure endpoints and exact minute-tick equality in its witness contract. USA uses its separately frozen 50-query manifest. Verify all query-file bytes with the root `checksums.sha256`.

Some frozen metadata fields, including `metadata.dataset_path` and `metadata.validation_path_expected`, name locations in the historical development environment. They are provenance only. Dataset lookup for a published run must use the bundled controlled network or the caller's `--data-root`, never those metadata paths.
