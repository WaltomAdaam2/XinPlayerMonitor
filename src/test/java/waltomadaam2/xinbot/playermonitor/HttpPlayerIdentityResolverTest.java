package waltomadaam2.xinbot.playermonitor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xin.bbtt.mcbot.Utils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpPlayerIdentityResolverTest {
    private static final String SERVER = "98465ebe-e619-3b1d-8b25-98352b6abbb9";
    private static final String OTHER = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final long CACHE_TTL = TimeUnit.HOURS.toMillis(24);

    @TempDir
    Path directory;

    private HttpServer server;
    private String base;
    private PlayerMonitorService service;
    private final AtomicInteger mojangCode = new AtomicInteger(200);
    private final AtomicInteger thirdPartyCode = new AtomicInteger(200);
    private final AtomicReference<String> mojangBody = new AtomicReference<>();
    private final AtomicReference<String> thirdPartyBody = new AtomicReference<>();
    private final AtomicReference<String> thirdPartyMethod = new AtomicReference<>();
    private final AtomicReference<String> thirdPartyRequestBody = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger mojangRequests = new AtomicInteger();
    private final AtomicLong now = new AtomicLong(1_000L);
    private HttpPlayerIdentityResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mojang/Alice", exchange -> {
            mojangRequests.incrementAndGet();
            respond(exchange, mojangCode.get(), mojangBody.get());
        });
        server.createContext("/ygg/api/profiles/minecraft", exchange -> {
            requests.incrementAndGet();
            thirdPartyMethod.set(exchange.getRequestMethod());
            thirdPartyRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, thirdPartyCode.get(), thirdPartyBody.get());
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        resolver = newResolver();
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
        if (server != null) {
            server.stop(0);
        }
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

    @Test
    void cachedServicesUnder24HoursAvoidHttpAndKeepCheckedTimesAcrossRestart() throws Exception {
        openService();
        setProfiles(SERVER, OTHER);
        resolveAndRecord(SERVER);
        now.addAndGet(CACHE_TTL - 1L);

        IdentityResolution cached = resolver.resolve("Alice", UUID.fromString(SERVER),
                service.playerIdentity("Alice").orElseThrow());
        assertTrue(cached.mojang().cached());
        assertTrue(cached.thirdParty().cached());
        StoredPlayerIdentity compared = service.recordIdentityCheck("Alice", cached, now.get());
        assertEquals(1_000L, compared.mojangCheckedAt());
        assertEquals(1_000L, compared.thirdPartyCheckedAt());
        assertEquals(now.get(), compared.uuidLastCheckedAt());
        assertEquals(1_000L, compared.uuidLastWrittenAt());
        assertRequestCounts(1, 1);

        service.close();
        openService();
        resolver = newResolver();
        StoredPlayerIdentity restarted = resolveAndRecord(SERVER);
        assertRequestCounts(1, 1);
        assertEquals(1_000L, restarted.mojangCheckedAt());
        assertEquals(1_000L, restarted.thirdPartyCheckedAt());
    }

    @Test
    void identityAt24HourBoundaryQueriesBothServicesAgain() throws Exception {
        openService();
        setProfiles(SERVER, OTHER);
        resolveAndRecord(SERVER);
        now.addAndGet(CACHE_TTL);

        StoredPlayerIdentity refreshed = resolveAndRecord(SERVER);

        assertRequestCounts(2, 2);
        assertEquals(now.get(), refreshed.mojangCheckedAt());
        assertEquals(now.get(), refreshed.thirdPartyCheckedAt());
        assertEquals(now.get(), refreshed.uuidLastCheckedAt());
        assertEquals(1_000L, refreshed.uuidLastWrittenAt());
    }

    @Test
    void changedServerUuidBypassesFreshExternalCache() throws Exception {
        openService();
        setProfiles(SERVER, OTHER);
        resolveAndRecord(SERVER);
        now.incrementAndGet();
        setProfiles(OTHER, null);

        StoredPlayerIdentity refreshed = resolveAndRecord(OTHER);

        assertRequestCounts(2, 2);
        assertEquals(OTHER, refreshed.serverUuid());
        assertEquals(OTHER, refreshed.mojangUuid());
        assertEquals(PlayerIdentityType.PREMIUM, refreshed.identityType());
        assertEquals(now.get(), refreshed.mojangCheckedAt());
        assertEquals(now.get(), refreshed.thirdPartyCheckedAt());
        assertEquals(now.get(), refreshed.uuidLastWrittenAt());
    }

    @Test
    void onlyExpiredServiceRefreshesWhenCheckedTimesDiffer() throws Exception {
        openService();
        setProfiles(SERVER, OTHER);
        resolveAndRecord(SERVER);
        now.addAndGet(CACHE_TTL);
        long thirdPartyCheckedAt = now.get() - CACHE_TTL / 2;
        service.recordIdentityCheck("Alice", new IdentityResolution(SERVER,
                Utils.getOfflineUUID("Alice").toString(), IdentityResolution.Lookup.cached(SERVER),
                IdentityResolution.Lookup.found(OTHER), PlayerIdentityType.PREMIUM, true), thirdPartyCheckedAt);

        StoredPlayerIdentity refreshed = resolveAndRecord(SERVER);

        assertRequestCounts(2, 1);
        assertEquals(now.get(), refreshed.mojangCheckedAt());
        assertEquals(thirdPartyCheckedAt, refreshed.thirdPartyCheckedAt());
        assertEquals(SERVER, refreshed.mojangUuid());
        assertEquals(OTHER, refreshed.thirdPartyUuid());
    }

    @Test
    void serverErrorsPreserveServiceChecksButNotFoundAdvancesThem() throws Exception {
        openService();
        setProfiles(SERVER, OTHER);
        resolveAndRecord(SERVER);
        now.addAndGet(CACHE_TTL);
        mojangCode.set(503);
        thirdPartyCode.set(503);

        StoredPlayerIdentity failed = resolveAndRecord(SERVER);

        assertEquals(1_000L, failed.mojangCheckedAt());
        assertEquals(1_000L, failed.thirdPartyCheckedAt());
        assertEquals(1_000L, failed.uuidLastCheckedAt());
        assertEquals(SERVER, failed.mojangUuid());
        assertEquals(OTHER, failed.thirdPartyUuid());

        mojangCode.set(404);
        mojangBody.set("");
        thirdPartyCode.set(200);
        thirdPartyBody.set("[]");
        IdentityResolution notFound = resolver.resolve("Alice", UUID.fromString(SERVER), failed);
        assertEquals(IdentityResolution.LookupStatus.NOT_FOUND, notFound.mojang().status());
        assertEquals(IdentityResolution.LookupStatus.NOT_FOUND, notFound.thirdParty().status());
        StoredPlayerIdentity refreshed = service.recordIdentityCheck("Alice", notFound, now.get());
        assertRequestCounts(3, 3);
        assertEquals(now.get(), refreshed.mojangCheckedAt());
        assertEquals(now.get(), refreshed.thirdPartyCheckedAt());
        assertEquals(now.get(), refreshed.uuidLastCheckedAt());
        assertEquals(null, refreshed.mojangUuid());
        assertEquals(null, refreshed.thirdPartyUuid());
    }

    @Test
    void networkErrorsDoNotAdvanceServiceChecksOrClearStoredUuids() throws Exception {
        openService();
        setProfiles(SERVER, OTHER);
        resolveAndRecord(SERVER);
        now.addAndGet(CACHE_TTL);
        server.stop(0);
        server = null;

        IdentityResolution error = resolver.resolve("Alice", UUID.fromString(SERVER),
                service.playerIdentity("Alice").orElseThrow());
        assertEquals(IdentityResolution.LookupStatus.ERROR, error.mojang().status());
        assertEquals(IdentityResolution.LookupStatus.ERROR, error.thirdParty().status());
        StoredPlayerIdentity failed = service.recordIdentityCheck("Alice", error, now.get());

        assertEquals(1_000L, failed.mojangCheckedAt());
        assertEquals(1_000L, failed.thirdPartyCheckedAt());
        assertEquals(1_000L, failed.uuidLastCheckedAt());
        assertEquals(SERVER, failed.mojangUuid());
        assertEquals(OTHER, failed.thirdPartyUuid());
    }

    private HttpPlayerIdentityResolver newResolver() {
        return new HttpPlayerIdentityResolver(HttpClient.newHttpClient(),
                () -> base + "/mojang/", () -> base + "/ygg/", now::get);
    }

    private void openService() throws IOException {
        service = new PlayerMonitorService(directory.resolve("identity-cache"));
        service.initialize();
    }

    private StoredPlayerIdentity resolveAndRecord(String serverUuid) throws IOException {
        IdentityResolution resolution = resolver.resolve("Alice", UUID.fromString(serverUuid),
                service.playerIdentity("Alice").orElse(null));
        return service.recordIdentityCheck("Alice", resolution, now.get());
    }

    private void assertRequestCounts(int mojang, int thirdParty) {
        assertEquals(mojang, mojangRequests.get(), "Mojang HTTP request count");
        assertEquals(thirdParty, requests.get(), "third-party HTTP request count");
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
