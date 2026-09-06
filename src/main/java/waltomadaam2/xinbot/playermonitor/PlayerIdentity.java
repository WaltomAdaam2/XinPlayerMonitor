package waltomadaam2.xinbot.playermonitor;

import java.time.Instant;
import java.util.UUID;

public record PlayerIdentity(
        String name,
        UUID serverUuid,
        UUID offlineUuid,
        UUID mojangUuid,
        UUID thirdPartyUuid,
        PlayerIdentityType type,
        boolean online,
        Instant lastSeen) {
}
