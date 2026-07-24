package waltomadaam2.xinbot.playermonitor;

import ch.qos.logback.classic.LoggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.command.Command;
import xin.bbtt.mcbot.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

public final class XinPlayerMonitor implements Plugin {
    private static final String COMMAND_NAME = "playermonitor";

    private final Logger logger = LoggerFactory.getLogger(XinPlayerMonitor.class.getSimpleName());
    private PlayerMonitorService service;
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
            enable(Path.of("playermonitor"));
        } catch (IOException error) {
            throw new IllegalStateException("Unable to initialize XinPlayerMonitor", error);
        }
    }

    void enable(Path dataDirectory) throws IOException {
        PluginLog log = new PluginLog(dataDirectory.resolve("log"));
        Consumer<String> infoSink = message -> {
            logger.info(message);
            log.info(message);
        };
        Consumer<String> warningSink = message -> {
            logger.warn(message);
            log.info("WARN: " + message);
        };
        MonitorSettingsStore settings = new MonitorSettingsStore(dataDirectory);
        settings.setWarningSink(warningSink);
        settings.initialize();
        PlayerMonitorService service = new PlayerMonitorService(dataDirectory, settings);
        service.setWarningSink(warningSink);
        service.setInfoSink(infoSink);
        service.initialize();
        installStatChatLogFilter(settings);
        listener = new PlayerMonitorListener(service, log, logger, settings);
        service.setEvictionGuard(listener::isProtectedFromEviction);
        this.service = service;
        Bot.INSTANCE.getPluginManager().events().registerEvents(listener, this);
        Bot.INSTANCE.getPluginManager().registerCommand(
                new Command(COMMAND_NAME, new String[0], "Query player monitoring data and configure stat scanning",
                        "playermonitor setting|scan-stat|<player> [stat|latestlogin|recentlogin|chat]"),
                new PlayerMonitorManagementCommand(service, settings, listener, logger),
                this);
        log.info("plugin enabled");
        logger.info("XinPlayerMonitor enabled; use playermonitor for help.");
    }

    @Override
    public void onDisable() {
        Bot.INSTANCE.getPluginManager().events().unregisterAll(this);
        Bot.INSTANCE.getPluginManager().commands().unregisterAll(this);
        if (listener != null) {
            listener.close();
            listener = null;
        }
        if (service != null) {
            service.close();
            service = null;
        }
        removeStatChatLogFilter();
    }

    private void installStatChatLogFilter(MonitorSettingsStore settings) {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            logger.warn("Unable to hide stat chat output: Logback is unavailable.");
            return;
        }
        loggerContext = context;
        statChatLogFilter = new StatChatLogFilter(settings::statOutputHide);
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
