package com.dataiku.clubhouse;

import static com.dataiku.clubhouse.Credentials.CH_TOKEN;
import static com.dataiku.clubhouse.Credentials.GH_TOKEN;
import static com.dataiku.clubhouse.Credentials.TRELLO_API_KEY;
import static com.dataiku.clubhouse.Credentials.TRELLO_TOKEN;
import static com.dataiku.clubhouse.GithubMigration.IssueState.ALL;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;
import java.util.logging.ConsoleHandler;
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

    static {
        configureLogger(logger);
    }

    public static void main(String[] args) throws IOException {
        boolean migrationTrello = false;
        boolean migrationGithub = true;
        boolean dryRun = true;

        if (migrationTrello) {
            TrelloMigrationParams trelloMigrationParams = loadTrelloMigrationParams();
            TrelloMigration trelloMigration = new TrelloMigration(clubhouseClient(), "DIP", trelloClient(), "dataikurd", trelloMigrationParams);
            trelloMigration.setDryRun(dryRun);
            trelloMigration.run();
        }
        if (migrationGithub) {
            GithubMigrationParams githubMigrationParams = loadGithubMigrationParams();
            GithubMigration githubMigration = new GithubMigration(clubhouseClient(), "DIP", gitHubClient(), "dip", githubMigrationParams);
            githubMigration.setDryRun(dryRun);
            githubMigration.migrateGithubIssues(ALL);
        }

        if (!dryRun) {
            Housekeeping housekeeping = new Housekeeping(clubhouseClient());
            housekeeping.closeCompletedEpics();
            housekeeping.createMilestonesFromEpics();
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

    private static Trello trelloClient() {
        return new TrelloImpl(TRELLO_API_KEY, TRELLO_TOKEN);
    }

    private static ClubhouseClient clubhouseClient() {
        return new ClubhouseClient(CH_TOKEN);
    }

    private static GitHubClient gitHubClient() {
        GitHubClient client = new GitHubClient();
        client.setOAuth2Token(GH_TOKEN);
        return client;
    }

    private static void configureLogger(Logger logger) {
        ConsoleHandler ch = new ConsoleHandler();
        ch.setFormatter(new SimpleFormatter() {
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
        });
        Root.logger.setUseParentHandlers(false);
        logger.addHandler(ch);
        Root.logger.setLevel(Level.INFO);
    }
}
