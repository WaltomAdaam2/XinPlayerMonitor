package waltomadaam2.xinbot.playermonitor;

import waltomadaam2.xinbot.playermonitor.model.ChatEntry;
import waltomadaam2.xinbot.playermonitor.model.StatSnapshot;

import java.util.List;

record PlayerOverview(
        String playerName,
        long firstSeenAt,
        long chatCount,
        List<ChatEntry> recentChats,
        Long latestLoginAt,
        Long latestLogoutAt,
        Long latestSessionDurationMillis,
        long playtimeLast30DaysMillis,
        StatSnapshot latestStat,
        StoredPlayerIdentity identity) {
}
