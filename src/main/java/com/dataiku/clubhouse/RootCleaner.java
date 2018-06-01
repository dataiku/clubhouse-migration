package com.dataiku.clubhouse;

import static com.dataiku.clubhouse.LogConfigurator.configureLogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import io.clubhouse4j.api.v3beta.ClubhouseClient;
import io.clubhouse4j.api.v3beta.GsonHelper;

/**
 * Hello world!
 */
public class RootCleaner {

    private static final Logger logger = Logger.getLogger("com.dataiku");

    public static void main(String[] args) throws Exception {
        configureLogger(logger);

        Credentials credentials = loadCredentials();
        ClubhouseCleaner clubhouseCleaner = new ClubhouseCleaner(clubhouseClient(credentials.clubhouseToken));
        clubhouseCleaner.run();
    }

    private static ClubhouseClient clubhouseClient(String token) {
        return new ClubhouseClient(token);
    }

    private static Credentials loadCredentials() throws IOException {
        try (BufferedReader bufferedReader = Files.newReader(new File("credentials.json"), Charsets.UTF_8)) {
            return GsonHelper.GSON.fromJson(bufferedReader, Credentials.class);
        }
    }

}
