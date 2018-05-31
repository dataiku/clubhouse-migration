package com.dataiku.clubhouse;

public class UserMapping {
    public String clubhouseName;
    public String githubLogin;
    public String trelloUsername;

    public UserMapping() {
    }

    public UserMapping(String clubhouseName, String githubLogin, String trelloUsername) {
        this.clubhouseName = clubhouseName;
        this.githubLogin = githubLogin;
        this.trelloUsername = trelloUsername;
    }
}
