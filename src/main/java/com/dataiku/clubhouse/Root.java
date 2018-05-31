package com.dataiku.clubhouse;

import static com.dataiku.clubhouse.GithubMigration.IssueState.ALL;

import java.io.BufferedReader;
import java.io.File;
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

import org.eclipse.egit.github.core.client.GitHubClient;
import org.trello4j.Trello;
import org.trello4j.TrelloImpl;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import io.clubhouse4j.api.v3beta.ClubhouseClient;
import io.clubhouse4j.api.v3beta.GsonHelper;

/**
 * Hello world!
 */
public class Root {

    private static final Logger logger = Logger.getLogger("com.dataiku");
    private static final LogFormatter LOG_FORMATTER = new LogFormatter();

    static {
        configureLogger();
    }

    public static void main(String[] args) throws IOException {
        logger.info("Starting...");
        Credentials credentials = loadCredentials();
        boolean migrationTrello = true;
        boolean migrationGithub = true;
        boolean dryRun = true;

        ClubhouseClient clubhouseClient = new ClubhouseClient(credentials.clubhouseToken);
        if (migrationTrello) {
            Trello trelloClient = new TrelloImpl(credentials.trelloApiKey, credentials.trelloToken);
            TrelloMigrationParams trelloMigrationParams = loadTrelloMigrationParams();
            TrelloMigration trelloMigration = new TrelloMigration(clubhouseClient, "DIP", trelloClient, "dataikurd", trelloMigrationParams);
            trelloMigration.setDryRun(dryRun);
            trelloMigration.run(32);
        }
        if (migrationGithub) {
            GithubMigrationParams githubMigrationParams = loadGithubMigrationParams();
            GitHubClient githubClient = gitHubClient(credentials.githubToken);
            GithubMigration githubMigration = new GithubMigration(clubhouseClient, "DIP", githubClient, "dip", githubMigrationParams);
            githubMigration.setDryRun(dryRun);
            githubMigration.run(4, ALL);
        }

        if (!dryRun) {
            Housekeeping housekeeping = new Housekeeping(clubhouseClient);
            housekeeping.closeCompletedEpics();
            housekeeping.createMilestonesFromEpics();
        }
    }

    private static Credentials loadCredentials() throws IOException {
        try (BufferedReader bufferedReader = Files.newReader(new File("credentials.json"), Charsets.UTF_8)) {
            return GsonHelper.GSON.fromJson(bufferedReader, Credentials.class);
        }
    }

    private static GithubMigrationParams loadGithubMigrationParams() throws IOException {
        try (BufferedReader bufferedReader = Files.newReader(new File("github-migration.json"), Charsets.UTF_8)) {
            return GsonHelper.GSON.fromJson(bufferedReader, GithubMigrationParams.class);
        }
    }

    private static TrelloMigrationParams loadTrelloMigrationParams() throws IOException {
        try (BufferedReader bufferedReader = Files.newReader(new File("trello-migration.json"), Charsets.UTF_8)) {
            return GsonHelper.GSON.fromJson(bufferedReader, TrelloMigrationParams.class);
        }
    }

    private static GitHubClient gitHubClient(String token) {
        GitHubClient client = new GitHubClient();
        client.setOAuth2Token(token);
        return client;
    }

    private static void configureLogger() {
        Root.logger.setLevel(Level.INFO);
        Root.logger.setUseParentHandlers(false);
        Handler[] handlers = Root.logger.getHandlers();
        if (handlers != null && handlers.length > 0) {
            for (Handler handler : handlers) {
                Root.logger.removeHandler(handler);
            }
        }

        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setFormatter(LOG_FORMATTER);
        Root.logger.addHandler(consoleHandler);

        try {
            FileHandler fileHandler = new FileHandler("clubhouse-migration.log", true);
            fileHandler.setFormatter(LOG_FORMATTER);
            Root.logger.addHandler(fileHandler);
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
