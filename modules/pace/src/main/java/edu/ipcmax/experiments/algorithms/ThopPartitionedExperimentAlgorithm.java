package edu.ipcmax.experiments.algorithms;

import edu.ipcmax.baselines.BoundaryTHopIndex;
import edu.ipcmax.baselines.GraphPartitionMetadata;
import edu.ipcmax.baselines.PartitionedTHopIndex;
import edu.ipcmax.baselines.exact.ExactArrivalProfile;
import edu.ipcmax.core.function.Domain;
import edu.ipcmax.core.graph.TDGraph;
import edu.ipcmax.experiments.framework.*;
import edu.ipcmax.core.pcmax.QuerySpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Fastest-profile adapter; exact rational cells are authoritative, with no PACE score/budget projection. */
public final class ThopPartitionedExperimentAlgorithm implements ExperimentAlgorithm {
    private TDGraph preparedGraph;
    private Map<String, String> preparedOptions;
    private PartitionedTHopIndex index;
    private final Map<String, Object> preparation = new LinkedHashMap<>();
    private long queryOrdinal;

    @Override public String id() { return "thop-partitioned"; }

    @Override public synchronized void prepare(TDGraph graph, AlgorithmConfig config) {
        if (index != null && graph == preparedGraph && config.objectiveOptions().equals(preparedOptions)) return;
        close();
        try {
            Path directory = Path.of(ObjectiveAdapterIO.required(config, "thop_index_dir"));
            Path assignmentFile = Path.of(ObjectiveAdapterIO.required(config, "thop_partitions"));
            var snapshot = ObjectiveAdapterIO.readVersionOne(assignmentFile, config, "thop_partitions");
            var root = snapshot.root();
            if (!root.path("assignments").isObject()) throw new IOException("partition assignments object is required");
            Map<Integer, Integer> assignments = new TreeMap<>();
            var fields = root.path("assignments").fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!field.getValue().isIntegralNumber()) throw new IOException("partition ID must be an integer");
                assignments.put(Integer.parseInt(field.getKey()), field.getValue().intValue());
            }
            GraphPartitionMetadata metadata = GraphPartitionMetadata.fromAssignments(graph, assignments);
            Domain support = Domain.closed(
                    Double.parseDouble(ObjectiveAdapterIO.required(config, "thop_domain_start")),
                    Double.parseDouble(ObjectiveAdapterIO.required(config, "thop_domain_end")));
            if (support.isEmpty()) throw new IllegalArgumentException("THop index domain must be nonempty");
            Path boundary = directory.resolve("boundary.bthp");
            Path inner = directory.resolve("inner.pthp");
            String lifecycle = config.objectiveOptions().getOrDefault("thop_lifecycle", "load");
            if (!List.of("build", "load", "load-or-build").contains(lifecycle)) {
                throw new IllegalArgumentException("unknown THop lifecycle: " + lifecycle);
            }
            boolean any = Files.exists(boundary) || Files.exists(inner);
            boolean build = lifecycle.equals("build") || (lifecycle.equals("load-or-build") && !any);
            long boundaryNanos = 0;
            long innerNanos = 0;
            if (build) {
                if (any) throw new IOException("refusing to overwrite existing THop index files");
                Files.createDirectories(directory);
                long started = System.nanoTime();
                BoundaryTHopIndex.build(graph, metadata, support, boundary,
                        metadata.boundaryVertexIds(), config.threads());
                boundaryNanos = System.nanoTime() - started;
                started = System.nanoTime();
                PartitionedTHopIndex.build(graph, boundary, inner);
                innerNanos = System.nanoTime() - started;
            }
            long started = System.nanoTime();
            PartitionedTHopIndex loaded = PartitionedTHopIndex.load(graph, boundary, inner);
            long loadNanos = System.nanoTime() - started;
            if (!loaded.partitionMetadata().partitionByVertex().equals(metadata.partitionByVertex())
                    || !loaded.indexDomain().equals(support)) {
                throw new IOException("loaded THop partition assignment or temporal domain differs from configuration");
            }
            index = loaded;
            preparedGraph = graph;
            preparedOptions = Map.copyOf(config.objectiveOptions());
            preparation.put("boundary_preprocessing_nanos", boundaryNanos);
            preparation.put("inner_preprocessing_nanos", innerNanos);
            preparation.put("index_loading_nanos", loadNanos);
            preparation.put("index_lifecycle_action", build ? "build_then_lazy_reload" : "lazy_load_existing");
            preparation.put("index_bytes_on_disk", Files.size(boundary) + Files.size(inner));
            preparation.put("boundary_index_bytes", Files.size(boundary));
            preparation.put("inner_index_bytes", Files.size(inner));
            preparation.put("boundary_index_sha256", ObjectiveAdapterIO.sha256(boundary));
            preparation.put("inner_index_sha256", ObjectiveAdapterIO.sha256(inner));
            preparation.put("partition_input_sha256", snapshot.sha256());
            preparation.put("boundary_vertex_count", metadata.boundaryVertexCount());
            preparation.put("partition_count", metadata.partitionCount());
            preparation.put("index_profile_pieces", loaded.constructionStatistics().temporalPieces()
                    + loaded.boundaryIndex().constructionStatistics().retainedFunctionPieces());
            preparation.put("index_format", "BTHP-v2/PTHP-v3");
            preparation.put("construction_schedule", "boundary_shortcuts_plus_incremental_dominance_pruning_during_search");
            preparation.put("paper_incremental_search_prune", true);
            preparation.put("inner_search_calls", loaded.constructionStatistics().searchCalls());
            preparation.put("inner_profiles_generated", loaded.constructionStatistics().profilesGenerated());
            preparation.put("inner_labels_retained", loaded.constructionStatistics().labelsRetained());
            preparation.put("inner_labels_pruned", loaded.constructionStatistics().labelsPruned());
            preparation.put("inner_search_expansions_skipped", loaded.constructionStatistics().searchExpansionsSkipped());
            preparation.put("inner_forward_search_expansions", loaded.constructionStatistics().forwardSearchExpansions());
            preparation.put("inner_backward_search_expansions", loaded.constructionStatistics().backwardSearchExpansions());
            preparation.put("inner_temporal_compositions", loaded.constructionStatistics().temporalCompositions());
            preparation.put("inner_envelope_operations", loaded.constructionStatistics().envelopeOperations());
            preparation.put("index_binary_determinism", "PTHP contains measured construction times and sampled heap; semantic profiles deterministic");
            preparation.put("supported_departure_coverage", intervals(support));
        } catch (IOException failure) {
            close();
            throw new IllegalArgumentException("THop preparation failed: " + failure.getMessage(), failure);
        }
    }

    @Override public synchronized AlgorithmResult run(TDGraph graph, QuerySpec query,
            AlgorithmConfig config, ExperimentInstrumentation instrumentation) {
        if (index == null) prepare(graph, config);
        if (graph != preparedGraph || !config.objectiveOptions().equals(preparedOptions)) {
            throw new IllegalArgumentException("THop prepared graph/options differ; call prepare explicitly");
        }
        try {
            PartitionedTHopIndex activeIndex = index;
            Map<String, Object> scalars = new LinkedHashMap<>(preparation);
            long ordinal = ++queryOrdinal;
            long started = System.nanoTime();
            var result = activeIndex.query(query.source(), query.destination(), query.departureDomain());
            long elapsed = System.nanoTime() - started;
            var stats = result.queryStatistics();
            instrumentation.setTiming("fastest_profile_query", elapsed);
            instrumentation.setTiming("index_lazy_loading", stats.lazyLoadNanos());
            instrumentation.addCounter("profile_compositions", stats.profileCompositions());
            instrumentation.addCounter("envelope_operations", stats.envelopeOperations());
            instrumentation.addCounter("boundary_combinations_examined", stats.boundaryCombinationsExamined());
            instrumentation.addCounter("disk_bytes_read", stats.diskBytesRead());
            instrumentation.addCounter("result_profile_pieces", stats.resultProfilePieces());
            scalars.put("objective", "fastest_profile");
            scalars.put("numerical_contract", "exact_rational_after_decimal_canonical_input-v1");
            scalars.put("status_schema_version", 2);
            scalars.put("query_ordinal", ordinal);
            scalars.put("query_cache_state", stats.diskBytesRead() > 0 ? "cold_partition_labels" : "warm_partition_labels");
            scalars.put("disk_bytes_read_measurement", "logical_lazy_partition_payload_bytes");
            scalars.put("cold_query_nanos", stats.diskBytesRead() > 0 ? elapsed : 0L);
            scalars.put("warm_query_nanos", stats.diskBytesRead() == 0 ? elapsed : 0L);
            scalars.put("preference_score_applicable", false);
            scalars.put("pace_budget_applied", false);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("objective", "fastest_profile");
            output.put("precision", "exact_rational");
            output.put("feasible", !result.exactTravelTimeProfile().isEmpty());
            output.put("requested_departure_domain", intervals(query.departureDomain()));
            output.put("fastest_travel_time_profile", cells(result.exactTravelTimeProfile()));
            scalars.put("objective_result", output);
            return new AlgorithmResult(result.exactTravelTimeProfile().isEmpty()
                    ? ExperimentStatus.NO_FEASIBLE_PATH : ExperimentStatus.COMPLETED,
                    null, ExactnessScope.GLOBAL_CERTIFIED, scalars, null, null);
        } catch (IOException failure) {
            throw new IllegalArgumentException("THop query index read failed: " + failure.getMessage(), failure);
        }
    }

    static List<Map<String, Object>> cells(ExactArrivalProfile profile) {
        List<Map<String, Object>> cells = new ArrayList<>();
        for (var cell : profile.segments()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("start", cell.interval().start().toString());
            value.put("end", cell.interval().end().toString());
            value.put("start_inclusive", cell.interval().startInclusive());
            value.put("end_inclusive", cell.interval().endInclusive());
            value.put("slope", cell.slope().toString());
            value.put("intercept", cell.intercept().toString());
            cells.add(value);
        }
        return cells;
    }

    private static List<Map<String, Object>> intervals(Domain domain) {
        return domain.intervals().stream().map(i -> Map.<String, Object>of(
                "start", i.start(), "end", i.end(),
                "start_inclusive", i.startInclusive(), "end_inclusive", i.endInclusive())).toList();
    }

    @Override public void close() {
        index = null;
        preparedGraph = null;
        preparedOptions = null;
        preparation.clear();
        queryOrdinal = 0;
    }
}
