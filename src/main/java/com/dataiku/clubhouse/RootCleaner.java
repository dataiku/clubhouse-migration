package com.dataiku.clubhouse;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import io.clubhouse4j.api.v3beta.ClubhouseClient;
import io.clubhouse4j.api.v3beta.GsonHelper;

/**
 * Hello world!
 */
public class RootCleaner {

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format", "[%1$tF %1$tT] [%4$-7s] %5$s %n");
    }

    public static void main(String[] args) throws Exception {
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
