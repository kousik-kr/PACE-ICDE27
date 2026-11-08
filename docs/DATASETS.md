# Datasets

## Bundled NY-CTRL network

`data/NY-Exact-radius/` is the complete controlled network used for Experiment A. The public dataset selector `NY-CTRL` maps to this directory; its frozen query rows retain the internal `dataset_id` `NY-Exact-radius`. The six distributed files are byte-for-byte copies of the authoritative controlled payload, which was also identical to the frozen input copy.

| Property | Recorded value |
| --- | --- |
| Vertices | 64 |
| Directed arcs | 142 |
| Arcs with nonzero score functions | 33 |
| JVM payload checksum (`PACE-GRAPH-CHECKSUM-v1`) | `cae3f9debec83eb1301892a51eb0ce5a8de4a52650310bc53e1bf1878f5ccdaa` |
| Graph semantic checksum | `1f83a9e18b7c3475ab617df32f7df57189375dfebd78087ca82c84a1a36871ca` |
| Generator manifest `dataset_checksum` | `ab3014b15e4c2ebbdac2304dfaf6b1eb69d2861d53f84b279c6247473aa50995` |

The runtime payload comprises `nodes.csv.gz`, `edges_static.csv.gz`, `travel_time_functions.jsonl.gz`, and `score_functions.jsonl.gz`. `manifest.json` records generation and temporal semantics; `README_generated.md` records its origin. Run `sha256sum --check checksums.sha256` from the repository root to verify every bundled dataset and query file.

The controlled network is a deterministic 4.5-minute radius induced subgraph of the NY road network. The author's declared experimental conversion is `minutes = DIMACS_weight / 6000`; this is a normalization for these experiments, not an assertion about the physical unit of DIMACS transit weights. The original generator manifest includes absolute paths to the historical DIMACS source inputs in its `input_files` provenance field. Those paths are historical records and must not be used for runtime dataset lookup.

## Downloading the large road-network datasets

The full NY, FLA, CAL, and USA road networks are not distributed here. Put each downloaded dataset beneath a configurable external data root and pass `--data-root /path/to/datasets` to a query script. The required layout is:

```text
/path/to/datasets/
  NY/
  FLA/
  CAL/
  USA/
```

Each directory must contain the same graph, temporal, preference, and manifest files expected by the loader. All four datasets are available from the [project Google Drive folder](https://drive.google.com/drive/folders/1ukHbqyAsDzNk76fiziH4HeHLSd9VKOjr?usp=drive_link); download them and place each directory under the data root as shown above. The frozen query files are small and included in `queries/`.

The source repository contains no top-level software license or distribution notice for this derived dataset. Confirm the upstream DIMACS redistribution terms and the project's release permissions before publishing the bundle.
