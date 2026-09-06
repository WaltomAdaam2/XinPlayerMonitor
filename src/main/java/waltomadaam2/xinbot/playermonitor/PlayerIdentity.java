package waltomadaam2.xinbot.playermonitor;

record PlayerIdentity(
        String playerName,
        String serverUuid,
        String offlineUuid,
        String mojangUuid,
        String thirdPartyUuid,
        IdentityType identityType,
        Long mojangCheckedAt,
        Long thirdPartyCheckedAt,
        Long uuidLastCheckedAt,
        Long uuidLastWrittenAt) {
}
