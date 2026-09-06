package waltomadaam2.xinbot.playermonitor;

import ch.qos.logback.classic.LoggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xin.bbtt.mcbot.Bot;
import xin.bbtt.mcbot.command.Command;
import xin.bbtt.mcbot.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Path;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class XinPlayerMonitor implements Plugin {
    private static final String COMMAND_NAME = "playermonitor";

    private final Logger logger = LoggerFactory.getLogger(XinPlayerMonitor.class.getSimpleName());
    private PlayerMonitorService service;
    private PlayerMonitorListener listener;
    private LoggerContext loggerContext;
    private StatChatLogFilter statChatLogFilter;
    private SQLiteBackupManager backupManager;

    static String pluginVersion() {
        try (InputStream resource = XinPlayerMonitor.class.getResourceAsStream("/plugin.yml")) {
            Properties properties = new Properties();
            if (resource != null) {
                properties.load(resource);
                return properties.getProperty("version", "unknown");
            }
        } catch (IOException ignored) {
            // Status remains available even when packaged metadata cannot be read.
        }
        return "unknown";
    }

    @Override
    public void onLoad() {
    }

    @Override
    public void onUnload() {
    }

    public CompletableFuture<PlayerIdentity> resolve(String name) {
        PlayerMonitorListener current = listener;
        if (current == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("XinPlayerMonitor is disabled"));
        }
        return current.resolve(name);
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
        Consumer<String> infoSink = logger::info;
        Consumer<String> warningSink = message -> {
            logger.warn(message);
            log.warn(message);
        };

        PlayerMonitorService createdService = null;
        PlayerMonitorListener createdListener = null;
        SQLiteBackupManager createdBackupManager = null;
        boolean eventsMayBeRegistered = false;
        boolean commandMayBeRegistered = false;
        try {
            MonitorSettingsStore settings = new MonitorSettingsStore(dataDirectory);
            settings.setWarningSink(warningSink);
            settings.initialize();

            createdService = new PlayerMonitorService(dataDirectory, settings);
            createdService.setWarningSink(warningSink);
            createdService.setInfoSink(infoSink);
            createdService.initialize();
            this.service = createdService;

            createdListener = new PlayerMonitorListener(createdService, log, logger, settings);
            this.listener = createdListener;
            createdService.setEvictionGuard(createdListener::isProtectedFromEviction);
            installStatChatLogFilter(settings, createdListener);

            eventsMayBeRegistered = true;
            Bot.INSTANCE.getPluginManager().events().registerEvents(createdListener, this);

            PlayerMonitorManagementCommand command = new PlayerMonitorManagementCommand(
                    createdService, settings, createdListener, logger);
            createdBackupManager = new SQLiteBackupManager(dataDirectory, createdService.databasePath(),
                    warningSink, infoSink, () -> {
                        try {
                            PlayerMonitorService current = this.service;
                            if (current == null) {
                                throw new IllegalStateException("Database service is unavailable");
                            }
                            current.flush();
                        } catch (IOException error) {
                            throw new IllegalStateException("Backup pre-flush failed", error);
                        }
                    }, settings::backupMaxCount);
            this.backupManager = createdBackupManager;
            command.setBackupManager(createdBackupManager);
            createdBackupManager.start(settings.backupInterval());

            commandMayBeRegistered = true;
            Bot.INSTANCE.getPluginManager().registerCommand(
                    new Command(COMMAND_NAME, new String[]{"xpm"}, "Query player monitoring data and configure stat scanning",
                            "playermonitor setting|scan stat|scan uuid|status|backup|<player> [playerinfo|stat|uuid|latestlogin|recentlogin|chat]"),
                    command,
                    this);
            log.info("plugin enabled");
            logger.info("XinPlayerMonitor enabled; use playermonitor or xpm for help.");
        } catch (Throwable failure) {
            Throwable cleanupFailure = null;
            if (commandMayBeRegistered) {
                try {
                    Bot.INSTANCE.getPluginManager().commands().unregisterAll(this);
                } catch (Throwable error) {
                    cleanupFailure = appendCleanupFailure(cleanupFailure, error);
                }
            }
            if (eventsMayBeRegistered) {
                try {
                    Bot.INSTANCE.getPluginManager().events().unregisterAll(this);
                } catch (Throwable error) {
                    cleanupFailure = appendCleanupFailure(cleanupFailure, error);
                }
            }
            if (createdBackupManager != null) {
                try {
                    createdBackupManager.stop();
                } catch (Throwable error) {
                    cleanupFailure = appendCleanupFailure(cleanupFailure, error);
                }
            }
            if (createdListener != null) {
                try {
                    createdListener.close();
                } catch (Throwable error) {
                    cleanupFailure = appendCleanupFailure(cleanupFailure, error);
                }
            }
            if (createdService != null) {
                try {
                    createdService.close();
                } catch (Throwable error) {
                    cleanupFailure = appendCleanupFailure(cleanupFailure, error);
                }
            }
            try {
                removeStatChatLogFilter();
            } catch (Throwable error) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, error);
            }
            backupManager = null;
            listener = null;
            service = null;
            if (cleanupFailure != null) {
                failure.addSuppressed(cleanupFailure);
            }
            if (failure instanceof IOException io) {
                throw io;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IOException("Unable to initialize XinPlayerMonitor", failure);
        }
    }

    private static Throwable appendCleanupFailure(Throwable current, Throwable next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }

    @Override
    public void onDisable() {
        Throwable cleanupFailure = null;
        try {
            Bot.INSTANCE.getPluginManager().events().unregisterAll(this);
        } catch (Throwable error) {
            cleanupFailure = appendCleanupFailure(cleanupFailure, error);
        }
        try {
            Bot.INSTANCE.getPluginManager().commands().unregisterAll(this);
        } catch (Throwable error) {
            cleanupFailure = appendCleanupFailure(cleanupFailure, error);
        }
        if (backupManager != null) {
            try {
                backupManager.stop();
            } catch (Throwable error) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, error);
            } finally {
                backupManager = null;
            }
        }
        if (listener != null) {
            try {
                listener.close();
            } catch (Throwable error) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, error);
            } finally {
                listener = null;
            }
        }
        if (service != null) {
            try {
                service.flush();
            } catch (Throwable error) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, error);
            }
            try {
                service.close();
            } catch (Throwable error) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, error);
            } finally {
                service = null;
            }
        }
        try {
            removeStatChatLogFilter();
        } catch (Throwable error) {
            cleanupFailure = appendCleanupFailure(cleanupFailure, error);
        }
        if (cleanupFailure != null) {
            logger.warn("XinPlayerMonitor disabled with one or more cleanup failures", cleanupFailure);
        }
    }

    private void installStatChatLogFilter(MonitorSettingsStore settings, PlayerMonitorListener listener) {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            logger.warn("Unable to hide stat chat output: Logback is unavailable.");
            return;
        }
        loggerContext = context;
        statChatLogFilter = new StatChatLogFilter(settings::statOutputHide, listener::hasActiveStatCapture);
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
