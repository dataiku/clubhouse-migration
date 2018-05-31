package com.dataiku.clubhouse;

import static com.dataiku.clubhouse.Credentials.CH_TOKEN;

import io.clubhouse4j.api.v3beta.ClubhouseClient;

/**
 * Hello world!
 */
public class RootCleaner {

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format", "[%1$tF %1$tT] [%4$-7s] %5$s %n");
    }

    public static void main(String[] args) {
        ClubhouseCleaner clubhouseCleaner = new ClubhouseCleaner(clubhouseClient());
        clubhouseCleaner.run();
    }

    private static ClubhouseClient clubhouseClient() {
        return new ClubhouseClient(CH_TOKEN);
    }
}
