package waltomadaam2.xinbot.playermonitor;

import waltomadaam2.xinbot.playermonitor.model.StatSnapshot;

record PlayerOverview(
        String playerName,
        long firstSeenAt,
        long chatCount,
        Long latestLoginAt,
        Long latestLogoutAt,
        Long latestSessionDurationMillis,
        long playtimeLast30DaysMillis,
        StatSnapshot latestStat) {
}
