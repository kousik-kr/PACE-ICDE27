package edu.ipcmax.experiments;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import edu.ipcmax.baselines.exact.ExactKernel;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceEnvelope;
import edu.ipcmax.baselines.exact.preference.ExactPreferenceProfileCodec;
import edu.ipcmax.baselines.exact.preference.ExactReplayEngine;
import edu.ipcmax.core.pcmax.egpi.EgpiEngineOptions;
import edu.ipcmax.experiments.framework.QueryManifestIO;

/** The anytime profile through the benchmark harness: provenance, anytime evidence and unchanged exact output. */
class EgpiAnytimeIntegrationTest {
    @TempDir Path temporary;

    @AfterEach
    void restoreProcessDefaults() {
        ExactKernel.configure(ExactKernel.Mode.JDK);
        ExactReplayEngine.configure(ExactReplayEngine.Mode.SEQUENTIAL_V1, 8, 16);
    }

    private List<JsonNode> run(String label, String... extra) throws Exception {
        Path output = temporary.resolve(label + ".jsonl");
        List<String> arguments = new ArrayList<>(List.of("--algorithm", "pace-b-egpi-v1", "--dataset", "demo",
                "--query-file", "experiments/manifests/tiny.jsonl", "--output-jsonl", output.toString(),
                "--collect-internal-counters", "--threads", "4", "--serialize-profile", "--profile-output-dir",
                temporary.resolve("profiles-" + label).toString()));
        arguments.addAll(List.of(extra));
        PaceBench.execute(arguments.toArray(new String[0]));
        List<JsonNode> records = new ArrayList<>();
        for (String line : Files.readAllLines(output)) records.add(QueryManifestIO.mapper().readTree(line));
        return records;
    }

    @Test
    void benchOptionsParseTheAnytimeFlagsAndBundleTheExactEngineIntoTheProfile() {
        BenchOptions anytime = BenchOptions.parse(new String[] {"--algorithm", "pace-b-egpi-v1", "--dataset", "demo",
                "--query-file", "experiments/manifests/tiny.jsonl", "--output-jsonl", "target/x.jsonl",
                "--egpi-engine-profile", "anytime", "--anytime-output-dir", "target/anytime", "--anytime-checkpoints-ms", "1000,3000"});
        assertEquals(EgpiEngineOptions.anytimeProfile(), anytime.algorithmConfig().egpiEngineOptions());
        assertEquals(List.of(1000L, 3000L), anytime.anytimeCheckpointsMs);
        anytime.applyExactSelections();
        assertEquals(ExactKernel.Mode.LEHMER, ExactKernel.mode());
        assertEquals(ExactReplayEngine.Mode.BALANCED_V1, ExactReplayEngine.mode());
        // explicit flags win over the bundle, and a run without the profile keeps the defaults
        restoreProcessDefaults();
        BenchOptions mixed = BenchOptions.parse(new String[] {"--algorithm", "pace-b-egpi-v1", "--dataset", "demo",
                "--query-file", "experiments/manifests/tiny.jsonl", "--output-jsonl", "target/x.jsonl",
                "--egpi-engine-profile", "anytime", "--exact-kernel", "jdk", "--exact-replay-engine", "sequential"});
        mixed.applyExactSelections();
        assertEquals(ExactKernel.Mode.JDK, ExactKernel.mode());
        assertEquals(ExactReplayEngine.Mode.SEQUENTIAL_V1, ExactReplayEngine.mode());
        BenchOptions plain = BenchOptions.parse(new String[] {"--algorithm", "pace-b-egpi-v1", "--dataset", "demo",
                "--query-file", "experiments/manifests/tiny.jsonl", "--output-jsonl", "target/x.jsonl"});
        assertEquals(EgpiEngineOptions.referenceProfile(), plain.algorithmConfig().egpiEngineOptions());
        plain.applyExactSelections();
        assertEquals(ExactKernel.Mode.JDK, ExactKernel.mode());
        assertThrows(IllegalArgumentException.class, () -> BenchOptions.parse(new String[] {"--algorithm", "pace-b",
                "--dataset", "demo", "--query-file", "experiments/manifests/tiny.jsonl", "--output-jsonl", "x",
                "--egpi-engine-profile", "anytime"}));
    }

    @Test
    void theAnytimeProfileProducesTheSameExactFunctionAsTheReferenceProfileWithCanonicalCells() throws Exception {
        List<JsonNode> reference = run("reference", "--egpi-engine-profile", "reference");
        List<JsonNode> candidate = run("anytime", "--egpi-engine-profile", "anytime",
                "--anytime-output-dir", temporary.resolve("anytime").toString(), "--anytime-checkpoints-ms", "1,100000");
        assertEquals(reference.size(), candidate.size());
        int compared = 0;
        for (int i = 0; i < reference.size(); i++) {
            JsonNode expected = reference.get(i), actual = candidate.get(i);
            assertEquals(expected.path("query").path("query_id").asText(), actual.path("query").path("query_id").asText());
            assertEquals("ANYTIME_V1", actual.path("configuration").path("egpi_engine").path("engine_profile").asText());
            assertEquals("REFERENCE", expected.path("configuration").path("egpi_engine").path("engine_profile").asText());
            assertNotEquals(expected.path("provenance").path("configuration_sha256").asText(),
                    actual.path("provenance").path("configuration_sha256").asText(),
                    "the engine profile is part of the configuration identity");
            assertEquals("LEHMER", actual.path("configuration").path("exact_kernel").asText().replace("MODE.", ""));
            assertEquals("BALANCED_V1", actual.path("configuration").path("exact_replay_engine").asText());
            assertEquals(expected.path("status").path("status_code").asText(), actual.path("status").path("status_code").asText());
            if (!expected.path("output").path("feasible").asBoolean()) {
                // proven by the static lower bound: a certificate, not an unresolved departure
                assertEquals("STATIC_BOUND_CERTIFIES_NO_ROUTE", actual.path("counters").path("coverage_status").asText());
                continue;
            }
            ExactPreferenceEnvelope refined = ExactPreferenceProfileCodec.decode(expected.path("output").path("profile").toString());
            ExactPreferenceEnvelope canonical = ExactPreferenceProfileCodec.decode(actual.path("output").path("profile").toString());
            assertEquals(ExactPreferenceProfileCodec.encode(refined.canonicalized()), ExactPreferenceProfileCodec.encode(canonical),
                    "the anytime profile is the canonical form of the reference profile");
            assertTrue(canonical.cells().size() <= refined.cells().size());
            assertEquals("FULL_VALIDATED_COVERAGE", actual.path("counters").path("coverage_status").asText());
            JsonNode anytime = actual.path("output").path("anytime");
            assertTrue(anytime.path("final_state_equals_published_envelope").asBoolean());
            assertEquals(3, anytime.path("checkpoints").size());
            JsonNode last = anytime.path("checkpoints").get(2);
            assertEquals("FINAL", last.path("checkpoint").asText());
            JsonNode late = anytime.path("checkpoints").get(1);
            assertEquals(last.path("commit_count").asInt(), late.path("commit_count").asInt(), "a later checkpoint is the final state");
            JsonNode early = anytime.path("checkpoints").get(0);
            assertTrue(early.path("commit_count").asInt() <= last.path("commit_count").asInt());
            Path file = Path.of(last.path("file").asText());
            assertTrue(Files.isRegularFile(file));
            JsonNode checkpoint = QueryManifestIO.mapper().readTree(Files.readString(file));
            assertEquals("egpi-anytime-checkpoint-v1", checkpoint.path("schema").asText());
            assertEquals("FULL_VALIDATED_COVERAGE", checkpoint.path("coverage_status").asText());
            assertEquals(canonical.cells().size(), checkpoint.path("cells").asInt());
            assertEquals("WORK_CAP".equals(actual.path("counters").path("termination_cause").asText()) ? "PATH_CAPPED_NOT_CERTIFIED"
                    : actual.path("status").path("status_code").asText(), actual.path("status").path("status_code").asText());
            compared++;
        }
        assertEquals(2, compared);
        try (Stream<Path> files = Files.list(temporary.resolve("anytime"))) {
            assertTrue(files.count() >= 6, "two feasible queries, each with two checkpoints and the final state");
        }
    }

    @Test
    void ordinaryRunsDoNotRecordAnytimeOutputUnlessAskedTo() throws Exception {
        for (JsonNode record : run("plain")) {
            assertFalse(record.path("output").has("anytime"));
        }
    }
}
