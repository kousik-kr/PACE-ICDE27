package edu.ipcmax.core.pcmax;

/** Candidate construction contract independent of shared graph and temporal data. */
public interface VersionedCandidateGenerator<R> {
    R generate(QuerySpec query);

    /** Stable algorithm identity, never inferred from an unversioned display name. */
    String algorithmVersion();
}
