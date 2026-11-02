# Package validation — 2026-11-02

This report covers the local publication package at `/home/koushik/Kousik/PACE-ICDE27`. It records small validation runs, not a repeat of Experiments A or B.

## Build and tests

- `mvn -q package` succeeded from the publication directory using Java 21 and Maven 3.6.3.
- R22 module: 40 JUnit tests passed, 0 failures/errors/skips.
- R24 baseline module: 31 JUnit tests passed, 0 failures/errors/skips.
- Indexed THop module: 18 JUnit tests passed, 0 failures/errors/skips.
- The extracted compiled production classes matched the corresponding frozen experiment JAR classes byte for byte: R22 474/474, R24 471/471, indexed THop 505/505. This is a class identity check, not a claim that the newly shaded JAR bytes equal the historic JAR bytes.

## Input and portability checks

- `sha256sum --check checksums.sha256` passed for the six NY-CTRL data files and five frozen query files.
- `sha256sum --check provenance/source_hashes.sha256` passed for 679 packaged source, test, fixture, and module POM files.
- Packaged NY-CTRL has 64 vertices, 142 directed arcs, and 33 score-bearing arcs. Its graph-semantic checksum is `1f83a9e18b7c3475ab617df32f7df57189375dfebd78087ca82c84a1a36871ca`.
- Frozen query counts are NY-CTRL 60, NY 50, FLA 50, CAL 50, USA 50. The original JSONL file bytes and order were retained.
- No symlinks or complete large-network payloads were included. No path in `modules/`, `scripts/`, or `configs/` refers to the development checkout.

## Public command checks

Both `--help` commands worked. Invalid algorithm, dataset, query ID, missing THop index, and unsupported THop timeout arguments returned clear nonzero errors.

A single frozen NY-CTRL query (`NY-FINAL-NY-FINAL-P001-C510-B005`) ran through each public selector and produced a result with the original query identity, source/destination, native status, profile cells or indexed travel pieces, route witnesses, and non-null query-processing timing:

| Selector | Native status | Validated result |
| --- | --- | --- |
| `pace-x` | `COMPLETED` | yes; historical exactness caveat remains |
| `pace-b` | `COMPLETED` | yes; native config confirmed `egpi_engine_profile=r22`, exact kernel R22 and balanced replay |
| `icsp` | `COMPLETED` | yes |
| `iscope` | `CERTIFIED_COMPLETE` | yes |
| `thop` | `ACCEPTED` | yes; indexed BTHP-v2/PTHP-v4 artifacts loaded and independent fastest-path samples matched |

The all-query command was exercised with a one-row canonical query-root override; its PACE-B output matched the single-query result's schema, query payload, native status, exact preference summary, and profile checksum `89dfb6b22b1ed572fd6065b2b42a7c2b44ba8194b4bbe32c16edd07c310c4a0b`. This checked the full-run path without launching all 60 experimental queries.

An additional indexed THop query (`NY-FINAL-NY-FINAL-P008-C1110-B010`) produced nonzero exact post-evaluated preference. Its indexed travel profile hash, routes, preference cells, and travel-time pieces matched the original frozen Experiment A raw record; all 12 independent fastest-arrival samples matched. The adapter keeps `certified=false` because sampled checks are not a formal global optimality certificate.

The NYC plotter ran using only the packaged case-study files and Matplotlib. It generated PDF, PNG, and SVG; the regenerated PNG SHA-256 was byte-identical to the manuscript-matching archived PNG (`ed3aa6b8ae0db6a824ec830f5b7d4d54bc748fe9fdf6b103ae048d52e913c7e0`).

## Limits before public release

- Historical PACE-X outputs remain uncertified, and their historical executable SHA-256 was not recorded.
- Indexed THop is verified on NY-CTRL. Compatible indexes and validated large-network indexed execution are not included for NY, FLA, CAL, or USA.
- Large-network data and author-supplied Google Drive URLs remain external/pending.
- Source, adapted baseline, dataset, and case-study redistribution permissions require author review; no license grant is inferred.
- The historical NYC V2 algorithm rerun needs its external graph and separate V2 runner. The included figure regeneration does not need either.
