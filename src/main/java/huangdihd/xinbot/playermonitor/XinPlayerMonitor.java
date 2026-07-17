package huangdihd.xinbot.playermonitor;

import ch.qos.logback.classic.LoggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.command.Command;
import xin.bbtt.mcbot.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Path;

public final class XinPlayerMonitor implements Plugin {
    private static final String COMMAND_NAME = "player";
    private static final String MANAGEMENT_COMMAND_NAME = "playermonitor";

    private final Logger logger = LoggerFactory.getLogger(XinPlayerMonitor.class.getSimpleName());
    private PlayerMonitorListener listener;
    private LoggerContext loggerContext;
    private StatChatLogFilter statChatLogFilter;

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
            MonitorSettingsStore settings = new MonitorSettingsStore(dataDirectory);
            settings.initialize();
            PluginLog log = new PluginLog(dataDirectory.resolve("log"));
            installStatChatLogFilter();
            listener = new PlayerMonitorListener(service, log, logger, settings);
            Bot.INSTANCE.getPluginManager().events().registerEvents(listener, this);
            Bot.INSTANCE.getPluginManager().registerCommand(
                    new Command(COMMAND_NAME, new String[0], "Query stored player monitoring data",
                            "player <name> [stat|lastlogin|recentlogin]"),
                    new PlayerMonitorCommand(service, logger),
                    this);
            Bot.INSTANCE.getPluginManager().registerCommand(
                    new Command(MANAGEMENT_COMMAND_NAME, new String[0], "Configure player stat scanning",
                            "playermonitor setting|stat scan"),
                    new PlayerMonitorManagementCommand(settings, listener, logger),
                    this);
            log.info("plugin enabled");
            logger.info("XinPlayerMonitor enabled; use player or playermonitor for help.");
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
        removeStatChatLogFilter();
    }

    private void installStatChatLogFilter() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            logger.warn("Unable to hide stat chat output: Logback is unavailable.");
            return;
        }
        loggerContext = context;
        statChatLogFilter = new StatChatLogFilter();
        statChatLogFilter.start();
        loggerContext.addTurboFilter(statChatLogFilter);
    }

    private void removeStatChatLogFilter() {
        if (loggerContext != null && statChatLogFilter != null) {
            loggerContext.getTurboFilterList().remove(statChatLogFilter);
            statChatLogFilter.stop();
        }
        loggerContext = null;
        statChatLogFilter = null;
    }
}
