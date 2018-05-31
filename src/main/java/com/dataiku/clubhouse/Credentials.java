package com.dataiku.clubhouse;

public class Credentials {
    //public static final String CH_TOKEN = "5b0c78fb-f504-4aa8-b802-97e15df09b05"; // apichery > Dataiku org
    //public static final String CH_TOKEN = "5afab41c-24a0-4077-8bb5-9e0fad6d3c48"; // apichery > dataiku-trello-migration org
    public static final String CH_TOKEN = "5b0e7ba5-8a3b-4728-97eb-5ebadf144105"; // migration.tool > dataiku-trello-migration
    private static final String[] GH_TOKENS = new String[]{
            "a9f0e0561ecf8b640ee55497cd0f404540ecaf55", // Github2Clubhouse
            "ee5d84315e1dfd1120a15a0fe828396e58d40c6c", // Github2Clubhouse (alternate)
            "8d30e431a79e271fcfa1ff48af7e6e29bbe66f48", // Github2Clubhouse 2
            "f14ee40b81e5fed5215a24809cc6bf1513b340f6", // Github2Clubhouse 3
            "a0315c084cb09efd2366e77ca7f89b60a6bdea78", // Github2Clubhouse 4
            "ff337823f3dfb2abe1967667d208806a0dd7c496", // Github2Clubhouse 5
            "aae2adb4856137309a7ee8d5bf12bd8e4f5ac4ab", // Github2Clubhouse 6
            "1bae2adbff4521a169cae0f8e177466cd4f8b751" // Github2Clubhouse 7
    };
    public static final String GH_TOKEN = GH_TOKENS[0];

    public static final String TRELLO_API_KEY = "bae807e3810b3619ebb36f01743ad971";
    public static final String TRELLO_TOKEN = "9e106c37b040fc12dc0ba71ad0f06021995c1aed92a2d43551c28a9e964d126b";
}
