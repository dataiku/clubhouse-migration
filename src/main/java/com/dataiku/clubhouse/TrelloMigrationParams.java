package com.dataiku.clubhouse;

import java.util.List;
import java.util.Map;

public class TrelloMigrationParams {
    public List<TrelloBoardMigrationParams> boards;

    public List<String> ignoredLists;

    public Map<String, String> labelsMapping;

    // key=trello username, value=clubhouse username
    public Map<String, String> usersMapping;
}
