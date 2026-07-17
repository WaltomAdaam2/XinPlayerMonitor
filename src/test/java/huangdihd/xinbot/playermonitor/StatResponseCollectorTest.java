package huangdihd.xinbot.playermonitor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatResponseCollectorTest {
    @Test
    void expiresUnansweredStatRequestForRetry() throws Exception {
        StatResponseCollector collector = new StatResponseCollector(1L);
        collector.expect("WaltomAdaam");

        Thread.sleep(10L);

        assertEquals(List.of("WaltomAdaam"), collector.expire());
        assertEquals(List.of(), collector.expire());
    }
}
