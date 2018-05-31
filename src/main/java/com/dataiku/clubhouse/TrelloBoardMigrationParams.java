package com.dataiku.clubhouse;

import java.util.HashMap;
import java.util.Map;

public class TrelloBoardMigrationParams {
    public static final String MIGRATE_LISTS_AS_STATES = "state";
    public static final String MIGRATE_LISTS_AS_LABELS = "label";
    public static final String MIGRATE_LISTS_AS_EPICS = "epic";

    public String name;
    public Boolean migrate;
    public String migrateListsAs;
    public Map<String, String> migrateLabelsIn = new HashMap<>();
    public Map<String, String> listStateMapping = new HashMap<>();

    public TrelloBoardMigrationParams() {
    }

    public TrelloBoardMigrationParams(String name, Boolean migrate, String migrateListsAs) {
        this.name = name;
        this.migrate = migrate;
        this.migrateListsAs = migrateListsAs;
    }
}
