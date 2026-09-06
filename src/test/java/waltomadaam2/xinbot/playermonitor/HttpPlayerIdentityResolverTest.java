package waltomadaam2.xinbot.playermonitor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xin.bbtt.mcbot.Utils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpPlayerIdentityResolverTest {
    private static final String SERVER = "98465ebe-e619-3b1d-8b25-98352b6abbb9";
    private static final String OTHER = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    private HttpServer server;
    private final AtomicInteger mojangCode = new AtomicInteger(200);
    private final AtomicInteger thirdPartyCode = new AtomicInteger(200);
    private final AtomicReference<String> mojangBody = new AtomicReference<>();
    private final AtomicReference<String> thirdPartyBody = new AtomicReference<>();
    private final AtomicReference<String> thirdPartyMethod = new AtomicReference<>();
    private final AtomicReference<String> thirdPartyRequestBody = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpPlayerIdentityResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mojang/Alice", exchange -> respond(exchange,
                mojangCode.get(), mojangBody.get()));
        server.createContext("/ygg/api/profiles/minecraft", exchange -> {
            requests.incrementAndGet();
            thirdPartyMethod.set(exchange.getRequestMethod());
            thirdPartyRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, thirdPartyCode.get(), thirdPartyBody.get());
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        resolver = new HttpPlayerIdentityResolver(HttpClient.newHttpClient(),
                () -> base + "/mojang/", () -> base + "/ygg/");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void classifiesOfflinePremiumThirdPartyAmbiguousAndUnknownStrictly() throws Exception {
        UUID offline = Utils.getOfflineUUID("Alice");
        assertEquals(PlayerIdentityType.OFFLINE,
                resolver.resolve("Alice", offline, null).identityType());

        setProfiles(SERVER, null);
        assertEquals(PlayerIdentityType.PREMIUM, resolve().identityType());

        setProfiles(OTHER, SERVER);
        assertEquals(PlayerIdentityType.THIRD_PARTY, resolve().identityType());

        setProfiles(SERVER, SERVER);
        assertEquals(PlayerIdentityType.AMBIGUOUS, resolve().identityType());

        setProfiles(OTHER, OTHER);
        assertEquals(PlayerIdentityType.UNKNOWN, resolve().identityType());
    }

    @Test
    void partialExternalFailureIsUnknownAndPreservesIncompleteStatus() throws Exception {
        setProfiles(SERVER, null);
        thirdPartyCode.set(500);

        IdentityResolution resolution = resolve();

        assertEquals(PlayerIdentityType.UNKNOWN, resolution.identityType());
        assertEquals(IdentityResolution.LookupStatus.FOUND, resolution.mojang().status());
        assertEquals(IdentityResolution.LookupStatus.ERROR, resolution.thirdParty().status());
        assertFalse(resolution.successful());
    }

    @Test
    void distinguishesMojang404FromErrorAndUsesConfiguredYggdrasilPost() throws Exception {
        mojangCode.set(404);
        mojangBody.set("");
        thirdPartyBody.set("[]");
        IdentityResolution notFound = resolve();
        assertEquals(IdentityResolution.LookupStatus.NOT_FOUND, notFound.mojang().status());

        mojangCode.set(503);
        IdentityResolution error = resolve();
        assertEquals(IdentityResolution.LookupStatus.ERROR, error.mojang().status());
        assertEquals("POST", thirdPartyMethod.get());
        assertEquals("[\"Alice\"]", thirdPartyRequestBody.get());
        assertTrue(requests.get() >= 2);
    }

    private IdentityResolution resolve() throws IOException {
        return resolver.resolve("Alice", UUID.fromString(SERVER), null);
    }

    private void setProfiles(String mojangUuid, String thirdPartyUuid) {
        mojangCode.set(200);
        thirdPartyCode.set(200);
        mojangBody.set(profile(mojangUuid));
        thirdPartyBody.set(thirdPartyUuid == null ? "[]" : "[" + profile(thirdPartyUuid) + "]");
    }

    private static String profile(String uuid) {
        return "{\"id\":\"" + uuid.replace("-", "") + "\",\"name\":\"Alice\"}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
