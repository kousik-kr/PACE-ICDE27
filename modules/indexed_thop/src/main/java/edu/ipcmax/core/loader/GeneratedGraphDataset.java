package edu.ipcmax.core.loader;

import edu.ipcmax.core.graph.TDGraph;

import java.nio.file.Path;

/**
 * Loaded generated synthetic graph plus lightweight manifest metadata.
 */
public record GeneratedGraphDataset(TDGraph graph, ManifestSummary manifest, Path directory) {
}
