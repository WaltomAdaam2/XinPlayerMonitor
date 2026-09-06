package waltomadaam2.xinbot.playermonitor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.util.function.Supplier;

final class HttpPlayerIdentityResolver implements PlayerIdentityResolver {
    private static final String MOJANG_PROFILE =
            "https://api.minecraftservices.com/minecraft/profile/lookup/name/";

    private final HttpClient client;
    private final Supplier<String> mojangBaseUrl;
    private final Supplier<String> thirdPartyBaseUrl;

    HttpPlayerIdentityResolver() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), () -> MOJANG_PROFILE,
                () -> MonitorSettings.DEFAULT_THIRD_PARTY_YGGDRASIL_BASE_URL);
    }

    HttpPlayerIdentityResolver(Supplier<String> thirdPartyBaseUrl) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), () -> MOJANG_PROFILE,
                thirdPartyBaseUrl);
    }

    HttpPlayerIdentityResolver(HttpClient client, Supplier<String> mojangBaseUrl,
                               Supplier<String> thirdPartyBaseUrl) {
        this.client = client;
        this.mojangBaseUrl = mojangBaseUrl;
        this.thirdPartyBaseUrl = thirdPartyBaseUrl;
    }

    @Override
    public IdentityResolution resolve(String playerName, UUID serverUuid, StoredPlayerIdentity previous) throws IOException {
        String server = serverUuid == null ? null : serverUuid.toString();
        String offline = Utils.getOfflineUUID(playerName).toString();
        if (server == null) {
            return new IdentityResolution(null, offline,
                    IdentityResolution.Lookup.notChecked(), IdentityResolution.Lookup.notChecked(),
                    PlayerIdentityType.UNKNOWN, true);
        }
        if (server.equalsIgnoreCase(offline)) {
            return new IdentityResolution(server, offline,
                    IdentityResolution.Lookup.notChecked(), IdentityResolution.Lookup.notChecked(),
                    PlayerIdentityType.OFFLINE, true);
        }

        IdentityResolution.Lookup mojang = lookupMojang(
                mojangBaseUrl.get() + URLEncoder.encode(playerName, StandardCharsets.UTF_8), playerName);
        IdentityResolution.Lookup thirdParty = lookupThirdParty(playerName);
        boolean mojangMatch = matches(server, mojang);
        boolean thirdPartyMatch = matches(server, thirdParty);
        PlayerIdentityType type;
        if (mojangMatch && thirdPartyMatch) {
            type = PlayerIdentityType.AMBIGUOUS;
        } else if (mojangMatch && thirdParty.completed()) {
            type = PlayerIdentityType.PREMIUM;
        } else if (thirdPartyMatch && mojang.completed()) {
            type = PlayerIdentityType.THIRD_PARTY;
        } else {
            type = PlayerIdentityType.UNKNOWN;
        }
        return new IdentityResolution(server, offline, mojang, thirdParty, type,
                mojang.completed() && thirdParty.completed());
    }

    private IdentityResolution.Lookup lookupMojang(String url, String expectedName) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build();
        return send(request, expectedName, false);
    }

    private IdentityResolution.Lookup lookupThirdParty(String playerName) throws IOException {
        String baseUrl = MonitorSettingsStore.normalizeThirdPartyYggdrasilBaseUrl(thirdPartyBaseUrl.get());
        String body = "[\"" + playerName.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/profiles/minecraft"))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return send(request, playerName, true);
    }

    private IdentityResolution.Lookup send(HttpRequest request, String expectedName, boolean arrayResponse)
            throws IOException {
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
            JsonElement parsed = JsonParser.parseString(response.body());
            if (arrayResponse) {
                JsonArray profiles = parsed.getAsJsonArray();
                for (JsonElement profile : profiles) {
                    IdentityResolution.Lookup match = profile(profile.getAsJsonObject(), expectedName);
                    if (match.status() == IdentityResolution.LookupStatus.FOUND) {
                        return match;
                    }
                }
                return IdentityResolution.Lookup.notFound();
            }
            return profile(parsed.getAsJsonObject(), expectedName);
        } catch (RuntimeException error) {
            return IdentityResolution.Lookup.error();
        }
    }

    private static IdentityResolution.Lookup profile(JsonObject body, String expectedName) {
        String name = body.has("name") ? body.get("name").getAsString() : "";
        String id = body.has("id") ? body.get("id").getAsString() : "";
        if (!name.equalsIgnoreCase(expectedName)) {
            return IdentityResolution.Lookup.notFound();
        }
        return IdentityResolution.Lookup.found(canonicalUuid(id));
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
