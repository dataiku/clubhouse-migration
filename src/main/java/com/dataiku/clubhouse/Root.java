package com.dataiku.clubhouse;

import static com.dataiku.clubhouse.GithubMigration.IssueState.ALL;
import static com.dataiku.clubhouse.LogConfigurator.configureLogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

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

    public static void main(String[] args) throws IOException {
        configureLogger(logger);
        logger.info("Starting...");
        Credentials credentials = loadCredentials();
        boolean migrationTrello = false;
        boolean migrationGithub = true;
        boolean dryRun = false;

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
}
