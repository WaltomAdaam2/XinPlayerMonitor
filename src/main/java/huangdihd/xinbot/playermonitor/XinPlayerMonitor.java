package huangdihd.xinbot.playermonitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.command.Command;
import xin.bbtt.mcbot.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Path;

public final class XinPlayerMonitor implements Plugin {
    private static final String COMMAND_NAME = "player";

    private final Logger logger = LoggerFactory.getLogger(XinPlayerMonitor.class.getSimpleName());
    private PlayerMonitorListener listener;

    @Override
    public void onLoad() {
    }

    @Override
    public void onUnload() {
    }

    @Override
    public void onEnable() {
        try {
            Path dataDirectory = Path.of("playermonitor");
            PlayerMonitorService service = new PlayerMonitorService(dataDirectory);
            service.initialize();
            PluginLog log = new PluginLog(dataDirectory.resolve("log"));
            listener = new PlayerMonitorListener(service, log);
            Bot.INSTANCE.getPluginManager().events().registerEvents(listener, this);
            Bot.INSTANCE.getPluginManager().registerCommand(
                    new Command(COMMAND_NAME, new String[0], "Query stored player monitoring data",
                            "player <name> [stat|lastlogin|recentlogin]"),
                    new PlayerMonitorCommand(service, logger),
                    this);
            log.info("plugin enabled");
            logger.info("XinPlayerMonitor enabled; use player <name> [stat|lastlogin|recentlogin]");
        } catch (IOException error) {
            throw new IllegalStateException("Unable to initialize XinPlayerMonitor", error);
        }
    }

    @Override
    public void onDisable() {
        if (listener != null) {
            listener.close();
            listener = null;
        }
    }
}
