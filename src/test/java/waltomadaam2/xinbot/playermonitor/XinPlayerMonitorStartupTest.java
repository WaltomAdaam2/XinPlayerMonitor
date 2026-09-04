package waltomadaam2.xinbot.playermonitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xin.bbtt.mcbot.Bot;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XinPlayerMonitorStartupTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void pluginEnableInitializesSqliteStorageAndRegistersCommand() throws Exception {
        Path dataDirectory = temporaryDirectory.resolve("playermonitor");
        XinPlayerMonitor plugin = new XinPlayerMonitor();

        plugin.enable(dataDirectory);
        try {
            assertTrue(Files.exists(dataDirectory.resolve("settings.json")));
            assertTrue(Files.exists(dataDirectory.resolve("xinpm.db")));
            assertTrue(Files.exists(dataDirectory.resolve("log")));
            var command = Bot.INSTANCE.getPluginManager().commands().getCommandByLabel("playermonitor");
            var alias = Bot.INSTANCE.getPluginManager().commands().getCommandByLabel("xpm");
            assertNotNull(command);
            assertNotNull(alias);
            assertSame(command.command(), alias.command());
            assertSame(command.executor(), alias.executor());
        } finally {
            plugin.onDisable();
        }
    }
}
