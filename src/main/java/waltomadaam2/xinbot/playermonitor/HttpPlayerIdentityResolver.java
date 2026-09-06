package waltomadaam2.xinbot.playermonitor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import xin.bbtt.mcbot.Utils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

final class HttpPlayerIdentityResolver implements PlayerIdentityResolver {
    private static final String MOJANG_PROFILE = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String THIRD_PARTY_PROFILE =
            "https://littleskin.cn/api/yggdrasil/sessionserver/session/minecraft/profile/";

    private final HttpClient client;

    HttpPlayerIdentityResolver() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    HttpPlayerIdentityResolver(HttpClient client) {
        this.client = client;
    }

    @Override
    public IdentityResolution resolve(String playerName, UUID serverUuid, PlayerIdentity previous) throws IOException {
        String server = serverUuid.toString();
        String offline = Utils.getOfflineUUID(playerName).toString();
        if (server.equalsIgnoreCase(offline)) {
            return new IdentityResolution(server, offline,
                    IdentityResolution.Lookup.notChecked(), IdentityResolution.Lookup.notChecked(),
                    IdentityType.OFFLINE, true);
        }

        IdentityResolution.Lookup mojang = lookup(
                MOJANG_PROFILE + URLEncoder.encode(playerName, StandardCharsets.UTF_8), playerName);
        IdentityResolution.Lookup thirdParty = lookup(
                THIRD_PARTY_PROFILE + server.replace("-", ""), playerName);
        boolean mojangMatch = matches(server, mojang);
        boolean thirdPartyMatch = matches(server, thirdParty);
        IdentityType type;
        if (mojangMatch && thirdPartyMatch) {
            type = IdentityType.AMBIGUOUS;
        } else if (mojangMatch) {
            type = IdentityType.PREMIUM;
        } else if (thirdPartyMatch) {
            type = IdentityType.THIRD_PARTY;
        } else if (mojang.completed() && thirdParty.completed()) {
            type = IdentityType.UNKNOWN;
        } else {
            type = previous == null ? null : previous.identityType();
        }
        return new IdentityResolution(server, offline, mojang, thirdParty, type,
                mojang.completed() && thirdParty.completed());
    }

    private IdentityResolution.Lookup lookup(String url, String expectedName) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Identity lookup interrupted", error);
        } catch (IOException error) {
            return IdentityResolution.Lookup.error();
        }
        if (response.statusCode() == 204 || response.statusCode() == 404) {
            return IdentityResolution.Lookup.notFound();
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return IdentityResolution.Lookup.error();
        }
        try {
            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            String name = body.has("name") ? body.get("name").getAsString() : "";
            String id = body.has("id") ? body.get("id").getAsString() : "";
            if (!name.equalsIgnoreCase(expectedName)) {
                return IdentityResolution.Lookup.notFound();
            }
            return IdentityResolution.Lookup.found(canonicalUuid(id));
        } catch (RuntimeException error) {
            return IdentityResolution.Lookup.error();
        }
    }

    private static boolean matches(String serverUuid, IdentityResolution.Lookup lookup) {
        return lookup.status() == IdentityResolution.LookupStatus.FOUND
                && serverUuid.equalsIgnoreCase(lookup.uuid());
    }

    static String canonicalUuid(String value) {
        String compact = value == null ? "" : value.replace("-", "").toLowerCase(Locale.ROOT);
        if (!compact.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Invalid UUID response");
        }
        return UUID.fromString(compact.substring(0, 8) + "-" + compact.substring(8, 12) + "-"
                + compact.substring(12, 16) + "-" + compact.substring(16, 20) + "-"
                + compact.substring(20)).toString();
    }
}
