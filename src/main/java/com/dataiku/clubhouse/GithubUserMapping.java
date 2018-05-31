package com.dataiku.clubhouse;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import org.eclipse.egit.github.core.User;
import org.eclipse.egit.github.core.client.GitHubClient;
import org.eclipse.egit.github.core.service.UserService;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
import io.clubhouse4j.api.v3beta.ClubhouseClient;
import io.clubhouse4j.api.v3beta.Member;
import io.clubhouse4j.api.v3beta.UsersService;

public class GithubUserMapping {

    public static final Member UNKNOWN_MEMBER = new Member();

    private final GitHubClient githubClient;
    private final List<Member> members;
    private final Map<String, String> clubhouseNameByGithubLogin = new HashMap<>();
    private final Cache<User, Member> mappingCache = CacheBuilder.newBuilder().build(new CacheLoader<User, Member>() {
        @Override
        public Member load(User user) throws Exception {
            return findMember(user);
        }
    });
    private final LoadingCache<String, String> githubUserCache = CacheBuilder.newBuilder().build(new GithubUserDisplayNameLoader());
    private UserService userService;

    public GithubUserMapping(ClubhouseClient chClient, GitHubClient gitHubClient, Map<String, String> userMappings) {
        this.members = new UsersService(chClient).listMembers();
        this.githubClient = gitHubClient;
        this.clubhouseNameByGithubLogin.putAll(userMappings);
        userService = new UserService(githubClient);
    }

    public Member getClubhouseMember(User user) {
        if (user == null) {
            return UNKNOWN_MEMBER;
        }
        try {
            return mappingCache.get(user, () -> findMember(user));
        } catch (ExecutionException e) {
            return UNKNOWN_MEMBER;
        }
    }

    public UUID getClubhouseMemberUUID(User user) {
        return getClubhouseMember(user).id;
    }

    public String getGithubUserDisplayName(User user) throws IOException {
        if (user.getName() != null && user.getName().length() > 0) {
            return user.getName();
        }
        return getGithubUserDisplayName(user.getLogin());
    }

    public String getGithubUserDisplayName(String userLogin) throws IOException {
        try {
            return githubUserCache.get(userLogin);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            } else {
                throw new UncheckedExecutionException(e.getCause());
            }
        }
    }

    private Member findMember(User user) {
        for (Member member : members) {
            if (matches(member, user)) {
                return member;
            }
        }
        User user1;
        try {
            user1 = userService.getUser(user.getLogin());
        } catch (IOException e) {
            user1 = user;
        }
        System.err.println("Missing github->clubhouse user mapping for " + user.getLogin() + " (" + user1.getName() + ") @" + user1.getEmail());
        return UNKNOWN_MEMBER;
    }

    private boolean matches(Member member, User user) {
        if (equalsIgnoreCase(user.getEmail(), member.profile.email_address)) {
            return true;
        }

        if (equalsIgnoreCase(user.getLogin(), member.profile.mention_name) || equalsIgnoreCase(user.getName(), member.profile.mention_name)) {
            return true;
        }
        if (equalsIgnoreCase(user.getLogin(), member.profile.name) || equalsIgnoreCase(user.getName(), member.profile.name)) {
            return true;
        }
        String clubhouseName = clubhouseNameByGithubLogin.get(user.getLogin());
        return equalsIgnoreCase(clubhouseName, member.profile.mention_name);
    }

    private static boolean equalsIgnoreCase(String str1, String str2) {
        return str1 != null && str1.equalsIgnoreCase(str2);
    }

    private class GithubUserDisplayNameLoader extends CacheLoader<String, String> {
        @Override
        public String load(String userLogin) throws IOException {
            User userFull = new UserService(githubClient).getUser(userLogin);
            if (userFull != null && userFull.getName() != null && userFull.getName().length() > 0) {
                return userFull.getName();
            }
            return userLogin;
        }
    }
}
