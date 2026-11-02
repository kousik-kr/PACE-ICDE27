package edu.ipcmax.core.pcmax;

/** Internal control signal used to keep an incremental frontier transaction atomic. */
final class PaceWorkLimitReachedException extends RuntimeException {
    static final PaceWorkLimitReachedException INSTANCE =
            new PaceWorkLimitReachedException();

    private PaceWorkLimitReachedException() {
        super(null, null, false, false);
    }
}
