package waltomadaam2.xinbot.playermonitor;

record StoredPlayerIdentity(
        String playerName,
        String serverUuid,
        String offlineUuid,
        String mojangUuid,
        String thirdPartyUuid,
        PlayerIdentityType identityType,
        Long mojangCheckedAt,
        Long thirdPartyCheckedAt,
        Long uuidLastCheckedAt,
        Long uuidLastWrittenAt) {
}
