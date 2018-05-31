package com.dataiku.clubhouse;

import static com.dataiku.clubhouse.Credentials.CH_TOKEN;

import java.io.IOException;

import io.clubhouse4j.api.v3beta.ClubhouseClient;

/**
 * Hello world!
 */
public class RootHousekeeping {

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format", "[%1$tF %1$tT] [%4$-7s] %5$s %n");
    }

    public static void main(String[] args) throws IOException {
        Housekeeping housekeeping = new Housekeeping(clubhouseClient());
        housekeeping.archiveEpics("1.4.");
        housekeeping.archiveEpics("2.3.");
        housekeeping.archiveEpics("3.1.");
    }

    private static ClubhouseClient clubhouseClient() {
        return new ClubhouseClient(CH_TOKEN);
    }
}
