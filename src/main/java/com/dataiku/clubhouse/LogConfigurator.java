package com.dataiku.clubhouse;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class LogConfigurator {
    private static final LogFormatter LOG_FORMATTER = new LogFormatter();

    public static void configureLogger(Logger logger) {
        logger.setLevel(Level.INFO);
        logger.setUseParentHandlers(false);
        Handler[] handlers = logger.getHandlers();
        if (handlers != null && handlers.length > 0) {
            for (Handler handler : handlers) {
                logger.removeHandler(handler);
            }
        }

        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setFormatter(LOG_FORMATTER);
        logger.addHandler(consoleHandler);

        try {
            FileHandler fileHandler = new FileHandler("clubhouse-migration.log", true);
            fileHandler.setFormatter(LOG_FORMATTER);
            logger.addHandler(fileHandler);
        } catch (IOException e) {
            // Do not log into file
        }
    }

    private static class LogFormatter extends SimpleFormatter {
        private static final String FORMAT = "[%1$tF %1$tT] [%2$-7s] %3$s - %4$s %n";

        @Override
        public synchronized String format(LogRecord record) {
            String msg = String.format(FORMAT,
                    new Date(record.getMillis()),
                    record.getLevel().getLocalizedName(),
                    record.getLoggerName(),
                    record.getMessage());
            Throwable exception = record.getThrown();
            if (exception != null) {
                StringWriter sw = new StringWriter();
                exception.printStackTrace(new PrintWriter(sw));
                msg += sw.toString();
            }
            return msg;
        }
    }
}
