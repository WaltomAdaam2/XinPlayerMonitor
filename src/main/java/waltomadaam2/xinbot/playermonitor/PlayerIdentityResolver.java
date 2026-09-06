package waltomadaam2.xinbot.playermonitor;

import java.io.IOException;
import java.util.UUID;

interface PlayerIdentityResolver {
    IdentityResolution resolve(String playerName, UUID serverUuid, PlayerIdentity previous) throws IOException;
}
