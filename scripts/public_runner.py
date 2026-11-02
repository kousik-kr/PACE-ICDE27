#!/usr/bin/env python3
"""Portable CLI adapter around the frozen Java research implementations.

This file coordinates input selection, provenance, and output serialization. It does
not implement routing, temporal arithmetic, preference scoring, or THop indexing.
"""
from __future__ import annotations

import argparse
import csv
from datetime import datetime, timezone
from fractions import Fraction
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
ALGORITHMS = ("pace-x", "pace-b", "icsp", "thop", "iscope")
DATASETS = ("NY-CTRL", "NY", "FLA", "CAL", "USA")
SCHEMA = "pace-public-query-v1"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    temporary.replace(path)


def clean_id(value: str) -> str:
    head = re.sub(r"[^A-Za-z0-9_.-]", "_", value).strip("._")[:100] or "query"
    return head + "-" + hashlib.sha256(value.encode()).hexdigest()[:10]


def parse_args(mode: str) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=f"Run {'one exact frozen query' if mode == 'single' else 'every frozen query in file order'} through a published PACE implementation."
    )
    parser.add_argument("--algorithm", required=True, choices=ALGORITHMS)
    parser.add_argument("--dataset", default="NY-CTRL", choices=DATASETS)
    if mode == "single":
        parser.add_argument("--query-id", required=True, help="Exact query_id in the selected JSONL file")
    parser.add_argument("--data-root", type=Path, help="Root containing external NY/, FLA/, CAL/, USA/ datasets")
    parser.add_argument("--query-root", type=Path, default=ROOT / "queries", help="Root containing <dataset>/queries.jsonl")
    parser.add_argument("--query-file", type=Path, help="Explicit canonical JSONL query file (recorded as an input override)")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "results")
    parser.add_argument("--index-dir", type=Path, help="Indexed THop boundary.bthp and inner.pthp directory")
    parser.add_argument("--threads", type=int, help="Override the algorithm's recorded worker/processor count")
    parser.add_argument("--timeout-s", "--timeout-seconds", dest="timeout_seconds", type=int,
                        help="Override supported per-query solver deadline (not supported by indexed THop)")
    parser.add_argument("--heap", help="Override Java maximum heap, for example 4g or 200g")
    return parser.parse_args()


def dataset_path(args: argparse.Namespace) -> Path:
    if args.dataset == "NY-CTRL":
        if args.data_root:
            for candidate in (args.data_root / "NY-Exact-radius", args.data_root / "NY-CTRL"):
                if candidate.is_dir():
                    return candidate.resolve()
            raise ValueError("NY-CTRL dataset missing under --data-root (expected NY-Exact-radius/ or NY-CTRL/)")
        return (ROOT / "data" / "NY-Exact-radius").resolve()
    if not args.data_root:
        raise ValueError(f"{args.dataset} road network is external; supply --data-root containing {args.dataset}/")
    return (args.data_root / args.dataset).resolve()


def read_queries(path: Path, dataset: str, physical_id: str) -> list[tuple[str, dict]]:
    if not path.is_file():
        raise ValueError(f"frozen query file missing: {path}")
    rows = []
    seen = set()
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as error:
            raise ValueError(f"invalid JSON in {path}:{number}: {error}") from error
        if not isinstance(row, dict) or not isinstance(row.get("query_id"), str):
            raise ValueError(f"missing string query_id in {path}:{number}")
        query_id = row["query_id"]
        if query_id in seen:
            raise ValueError(f"duplicate query_id {query_id!r} in {path}")
        seen.add(query_id)
        required = ("source", "destination", "interval_start", "interval_end", "budget")
        if any(key not in row for key in required):
            raise ValueError(f"query {query_id!r} is not a canonical experiment row; missing one of {required}")
        if row.get("dataset_id") not in (physical_id, dataset):
            raise ValueError(f"query {query_id!r} dataset_id {row.get('dataset_id')!r} does not match {dataset}")
        rows.append((line, row))
    if not rows:
        raise ValueError(f"query file is empty: {path}")
    return rows


def validate_inputs(args: argparse.Namespace, config: dict) -> tuple[Path, Path, list[tuple[str, dict]], Path, Path | None, dict | None]:
    directory = dataset_path(args)
    needed = ("manifest.json", "edges_static.csv.gz", "nodes.csv.gz",
              "travel_time_functions.jsonl.gz", "score_functions.jsonl.gz")
    if not directory.is_dir() or any(not (directory / item).is_file() for item in needed):
        raise ValueError(f"incomplete dataset directory: {directory}; expected manifest, graph, temporal and score files")
    query_file = (args.query_file or args.query_root / args.dataset / "queries.jsonl").resolve()
    rows = read_queries(query_file, args.dataset, directory.name)
    if getattr(args, "query_id", None):
        rows = [item for item in rows if item[1]["query_id"] == args.query_id]
        if not rows:
            raise ValueError(f"unknown query ID {args.query_id!r} in {query_file}")
    jar = ROOT / "modules" / config["module"] / "target" / "pace-bench.jar"
    if not jar.is_file():
        raise ValueError(f"built JAR missing: {jar}; run 'mvn package' from the repository root")
    index_dir = None
    index_provenance = None
    if args.algorithm == "thop":
        index_dir = (args.index_dir or ROOT / "prepared" / args.dataset).resolve()
        boundary, inner = index_dir / "boundary.bthp", index_dir / "inner.pthp"
        if not boundary.is_file() or not inner.is_file():
            raise ValueError(f"indexed THop artifacts missing in {index_dir}; see docs/REPRODUCIBILITY.md for index construction. No index-free fallback exists.")
        provenance_file = index_dir / "INDEX_PROVENANCE.json"
        if provenance_file.is_file():
            index_provenance = json.loads(provenance_file.read_text(encoding="utf-8"))
            for name, actual in (("boundary_index_sha256", sha256(boundary)), ("inner_index_sha256", sha256(inner))):
                if index_provenance.get(name) != actual:
                    raise ValueError(f"indexed THop {name} differs from {provenance_file}")
        else:
            index_provenance = {"status": "USER_SUPPLIED_UNVERIFIED", "boundary_index_sha256": sha256(boundary),
                                "inner_index_sha256": sha256(inner)}
    return directory, query_file, rows, jar, index_dir, index_provenance


def run_java(command: list[str], work: Path) -> tuple[int, str | None]:
    try:
        with (work / "stdout.log").open("w", encoding="utf-8") as stdout, (work / "stderr.log").open("w", encoding="utf-8") as stderr:
            finished = subprocess.run(command, cwd=ROOT, stdout=stdout, stderr=stderr, check=False)
        return finished.returncode, None
    except OSError as error:
        return 127, str(error)


def pace_result(row: dict, work: Path, config: dict, dataset: Path, jar: Path,
                args: argparse.Namespace) -> tuple[dict, int, str | None]:
    query_file = work / "query.jsonl"
    native_file = work / "native.jsonl"
    settings = config
    timeout = args.timeout_seconds if args.timeout_seconds is not None else settings["timeout_seconds"]
    threads = args.threads if args.threads is not None else settings["threads"]
    heap = args.heap or settings["jvm_heap"]
    command = ["java", f"-Xmx{heap}", f"-XX:ActiveProcessorCount={threads}", "-jar", str(jar),
               "--algorithm", settings["internal_algorithm"], "--dataset", str(dataset),
               "--query-file", str(query_file), "--output-jsonl", str(native_file),
               "--experiment-name", f"pace-public-{args.algorithm}-{args.dataset}",
               "--repetitions", "1", "--warmup-runs", str(settings["warmup_runs"]),
               "--threads", str(threads), "--timeout-seconds", str(timeout),
               "--query-cleanup-grace-seconds", str(settings["cleanup_seconds"]),
               "--preprocessing-timeout-seconds", str(settings["preprocessing_timeout_seconds"]),
               "--collect-phase-timings", "--collect-memory", "--collect-internal-counters",
               "--serialize-profile", "--profile-output-dir", str(work / "profiles")]
    if settings["memory_limit_mb"] is not None:
        command += ["--memory-limit-mb", str(settings["memory_limit_mb"])]
    command += settings["extra_args"]
    if args.algorithm == "pace-b":
        command += ["--anytime-output-dir", str(work / "anytime")]
    code, launch_error = run_java(command, work)
    native = None
    if native_file.is_file():
        for line in native_file.read_text(encoding="utf-8").splitlines():
            if line.strip():
                item = json.loads(line)
                if not item.get("run", {}).get("warmup", False) and item.get("query", {}).get("query_id") == row["query_id"]:
                    native = item
    if native is None:
        native = {"status": {"status_code": "MISSING_NATIVE_RESULT", "completed": False},
                  "error": {"message": launch_error or f"Java exit code {code}; inspect artifacts/stderr.log"}}
    return native, code, launch_error


def thop_result(row: dict, query_line: str, work: Path, dataset: Path, jar: Path,
                index_dir: Path, index_provenance: dict, args: argparse.Namespace) -> tuple[dict, int, str | None]:
    query_id = row["query_id"]
    exec_id = "thop-indexed__" + clean_id(query_id) + "__r1"
    plan = {"schema": "pace-public-thop-plan-v1", "thop_planned_executions": [
        {"dataset": args.dataset, "query_id": query_id, "rep": 1, "exec_id": exec_id,
         "query_row_sha256": hashlib.sha256(query_line.encode("utf-8")).hexdigest()}]}
    plan_file = work / "plan.json"
    write_json(plan_file, plan)
    out_dir = work / "native_thop"
    expected = {"boundary_index_sha256": index_provenance["boundary_index_sha256"],
                "inner_index_sha256": index_provenance["inner_index_sha256"]}
    if index_provenance.get("graph_semantic_checksum"):
        expected["graph_semantic_checksum"] = index_provenance["graph_semantic_checksum"]
    java_config = {"dataset": args.dataset, "dataset_dir": str(dataset),
                   "boundary_file": str(index_dir / "boundary.bthp"), "inner_file": str(index_dir / "inner.pthp"),
                   "manifest_file": str(plan_file), "queries_file": str(work / "query.jsonl"),
                   "out_dir": str(out_dir), "campaign_id": "pace-public-indexed-thop",
                   "executable_sha256": sha256(jar), "expected": expected,
                   "independent_samples": 12}
    config_file = work / "thop_config.json"
    write_json(config_file, java_config)
    threads = args.threads if args.threads is not None else 24
    heap = args.heap or "12g"
    command = ["java", f"-Xmx{heap}", f"-XX:ActiveProcessorCount={threads}", "-cp", str(jar),
               "edu.ipcmax.experiments.ThopFullNetworkQueryBatch", str(config_file)]
    code, launch_error = run_java(command, work)
    receipt_file = out_dir / "receipts" / (exec_id + ".json")
    raw_file = out_dir / "raw" / (exec_id + ".json")
    if receipt_file.is_file() and raw_file.is_file():
        native = {"receipt": json.loads(receipt_file.read_text(encoding="utf-8")),
                  "raw": json.loads(raw_file.read_text(encoding="utf-8"))}
    else:
        native = {"receipt": {"state": "MISSING_NATIVE_RESULT", "reasons": [launch_error or f"Java exit code {code}"]},
                  "raw": None}
    return native, code, launch_error


def normalized(algorithm: str, native: dict) -> dict:
    if algorithm == "thop":
        receipt = native["receipt"]
        raw = native.get("raw") or {}
        profile = raw.get("thop_result") or {}
        accepted = receipt.get("state") == "ACCEPTED"
        exact_average = None
        if accepted:
            try:
                domain = Fraction(profile["coverage"]["profile_domain_measure"])
                integral = sum((Fraction(cell["interval"]["end"]) - Fraction(cell["interval"]["start"]))
                               * Fraction(cell["score"]) for cell in profile["preference_cells"] if cell["feasible"])
                exact_average = str(integral / domain) if domain else None
            except (KeyError, ValueError, ZeroDivisionError):
                exact_average = None
        return {"objective": "FASTEST_TRAVEL_TIME_WITH_POST_EVALUATED_PREFERENCE",
                "native_status": receipt.get("state"), "native_completed": accepted,
                "validated_result_available": accepted, "certified": False,
                "exactness_scope": "INDEXED_FASTEST_PROFILE_WITNESS_AND_SAMPLED_ORACLE_VALIDATED" if accepted else None,
                "coverage": profile.get("coverage") if accepted else None,
                "query_processing_ns": receipt.get("timing_ns", {}).get("query_processing"),
                "preference_score_exact": exact_average,
                "preference_profile_pointer": "native.raw.thop_result.preference_cells" if accepted else None,
                "travel_time_profile_pointer": "native.raw.thop_result.travel_time_pieces" if accepted else None,
                "route_witnesses_pointer": "native.raw.thop_result.routes" if accepted else None,
                "failure_reasons": receipt.get("reasons", [])}
    output = native.get("output") or {}
    status = native.get("status") or {}
    profile = output.get("profile")
    available = isinstance(profile, dict) and bool(profile.get("cells"))
    return {"objective": output.get("objective"), "native_status": status.get("status_code"),
            "native_completed": status.get("completed"), "validated_result_available": available,
            "certified": bool(algorithm != "pace-x" and status.get("completed") and status.get("exactness_scope") == "GLOBAL_CERTIFIED"),
            "exactness_scope": status.get("exactness_scope"),
            "coverage": {"status": output.get("coverage_status"),
                         "feasible_departure_coverage_exact": output.get("feasible_departure_coverage_exact"),
                         "feasible_coverage_fraction": output.get("feasible_coverage_fraction"),
                         "full_domain_covered": output.get("full_domain_covered")},
            "query_processing_ns": (native.get("timing_ns") or {}).get("query_processing"),
            "preference_score_exact": output.get("average_selected_preference_exact"),
            "preference_score_display": output.get("average_selected_preference", output.get("average_selected_score")),
            "preference_profile_pointer": "native.output.profile.cells" if available else None,
            "travel_time_profile_pointer": "native.output.profile.cells[*].travel_time" if available else None,
            "route_witnesses_pointer": "native.output.profile.cells[*].path" if available else None,
            "failure_reasons": [native.get("error", {}).get("message")] if native.get("error", {}).get("message") else []}


def main(mode: str) -> int:
    args = parse_args(mode)
    if args.threads is not None and args.threads < 1:
        raise ValueError("--threads must be positive")
    if args.timeout_seconds is not None and args.timeout_seconds < 1:
        raise ValueError("--timeout-s must be positive")
    if args.heap and not re.fullmatch(r"[1-9][0-9]*[kKmMgG]", args.heap):
        raise ValueError("--heap must be a positive size such as 2g or 200g")
    if args.algorithm == "thop" and args.timeout_seconds is not None:
        raise ValueError("indexed THop has no solver-level per-query timeout; --timeout-s is unsupported")
    config_file = ROOT / "configs" / f"{args.algorithm}.json"
    config = json.loads(config_file.read_text(encoding="utf-8"))
    directory, query_file, rows, jar, index_dir, index_provenance = validate_inputs(args, config)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    run_id = stamp + "-" + uuid.uuid4().hex[:8]
    base = args.output_dir.resolve() / args.algorithm / args.dataset
    if mode == "single":
        base = base / "single" / clean_id(rows[0][1]["query_id"])
    run_dir = base / run_id
    run_dir.mkdir(parents=True, exist_ok=False)
    override = {key: value for key, value in (("threads", args.threads), ("timeout_seconds", args.timeout_seconds),
                ("jvm_heap", args.heap), ("query_file", str(args.query_file.resolve()) if args.query_file else None),
                ("data_root", str(args.data_root.resolve()) if args.data_root else None),
                ("index_dir", str(args.index_dir.resolve()) if args.index_dir else None)) if value is not None}
    metadata = {"schema": "pace-public-run-v1", "mode": mode, "algorithm": args.algorithm,
                "dataset": args.dataset, "run_id": run_id, "started_utc": datetime.now(timezone.utc).isoformat(),
                "planned_queries": len(rows), "query_file": str(query_file), "query_file_sha256": sha256(query_file),
                "dataset_dir": str(directory), "dataset_manifest_sha256": sha256(directory / "manifest.json"),
                "java_jar": str(jar), "java_jar_sha256": sha256(jar), "config_file": str(config_file),
                "config_sha256": sha256(config_file), "recorded_config": config, "overrides": override,
                "index_provenance": index_provenance, "results": []}
    write_json(run_dir / "run_metadata.json", metadata)
    summary = []
    for position, (line, row) in enumerate(rows, 1):
        query_id = row["query_id"]
        work = run_dir / "artifacts" / clean_id(query_id)
        work.mkdir(parents=True)
        (work / "query.jsonl").write_text(line + "\n", encoding="utf-8")
        if args.algorithm == "thop":
            native, exit_code, launch_error = thop_result(row, line, work, directory, jar, index_dir, index_provenance, args)
        else:
            native, exit_code, launch_error = pace_result(row, work, config, directory, jar, args)
        shared = normalized(args.algorithm, native)
        result = {"schema_version": SCHEMA, "algorithm": args.algorithm, "dataset": args.dataset,
                  "query_id": query_id, "query": row, "shared": shared,
                  "provenance": {"config_sha256": metadata["config_sha256"], "jar_sha256": metadata["java_jar_sha256"],
                                 "query_row_sha256": hashlib.sha256(line.encode("utf-8")).hexdigest(),
                                 "index": index_provenance, "java_exit_code": exit_code,
                                 "launch_error": launch_error, "artifact_dir": str(work.relative_to(run_dir))},
                  "native": native}
        result_path = run_dir / "result.json" if mode == "single" else run_dir / "queries" / (clean_id(query_id) + ".json")
        write_json(result_path, result)
        elapsed = shared.get("query_processing_ns")
        summary.append({"query_id": query_id, "source": row["source"], "destination": row["destination"],
                        "native_status": shared["native_status"], "validated_result_available": shared["validated_result_available"],
                        "certified": shared["certified"], "query_processing_ns": elapsed,
                        "preference_score_exact": shared.get("preference_score_exact"),
                        "result_file": str(result_path.relative_to(run_dir))})
        metadata["results"].append({"query_id": query_id, "result_file": str(result_path.relative_to(run_dir)),
                                    "native_status": shared["native_status"]})
        write_json(run_dir / "run_metadata.json", metadata)
        print(f"[{position}/{len(rows)}] {query_id}: {shared['native_status']} "
              f"validated={shared['validated_result_available']} elapsed_ns={elapsed if elapsed is not None else 'NULL'}", flush=True)
    if mode == "all":
        with (run_dir / "summary.csv").open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(summary[0]))
            writer.writeheader()
            writer.writerows(summary)
    metadata["ended_utc"] = datetime.now(timezone.utc).isoformat()
    metadata["completed_queries"] = len(summary)
    write_json(run_dir / "run_metadata.json", metadata)
    print(f"Results: {run_dir}")
    return 0 if all(item["native_status"] not in ("MISSING_NATIVE_RESULT", None) for item in summary) else 2


if __name__ == "__main__":
    try:
        if len(sys.argv) < 2 or sys.argv[1] not in ("all", "single"):
            raise ValueError("internal usage: public_runner.py {all|single} [options]")
        selected_mode = sys.argv.pop(1)
        raise SystemExit(main(selected_mode))
    except (ValueError, FileNotFoundError, KeyError) as failure:
        print(f"error: {failure}", file=sys.stderr)
        raise SystemExit(2)
