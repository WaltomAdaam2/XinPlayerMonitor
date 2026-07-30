package waltomadaam2.xinbot.playermonitor;

record DatabaseHealth(
        String writerState,
        boolean writerAlive,
        int queueSize,
        int queueCapacity,
        long lastCommittedAt,
        long lastFailureAt,
        String lastFailureMessage,
        long failedEventLines,
        long replayedFailedEvents,
        long pendingFailedEvents,
        long malformedFailedEvents,
        long databaseBytes,
        long walBytes,
        long shmBytes) {
}
