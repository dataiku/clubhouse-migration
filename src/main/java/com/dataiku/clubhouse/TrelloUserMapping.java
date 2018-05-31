package com.dataiku.clubhouse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.trello4j.Trello;

import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
import io.clubhouse4j.api.v3beta.ClubhouseClient;
import io.clubhouse4j.api.v3beta.Member;
import io.clubhouse4j.api.v3beta.Profile;
import io.clubhouse4j.api.v3beta.UsersService;

public class TrelloUserMapping {

    public static final Member UNKNOWN_MEMBER = new Member();

    private final Trello trelloClient;
    private final List<Member> members;
    private final Map<String, String> clubhouseNameByTrelloLogin = new HashMap<>();
    private final Cache<String, Member> mappingCache = CacheBuilder.newBuilder().build();
    private final LoadingCache<String, String> githubUserCache = CacheBuilder.newBuilder().build(new TrelloUserDisplayNameLoader());

    public TrelloUserMapping(ClubhouseClient chClient, Trello trelloClient, Map<String, String> userMappings) {
        this.members = new UsersService(chClient).listMembers();
        this.clubhouseNameByTrelloLogin.putAll(userMappings);
        this.trelloClient = trelloClient;
    }

    public Member getClubhouseMember(org.trello4j.model.Member trelloMember) {
        if (trelloMember == null) {
            return UNKNOWN_MEMBER;
        }
        String trelloUsername = trelloMember.getUsername();
        Member result = mappingCache.getIfPresent(trelloUsername);
        if (result == null) {
            result = findMember(trelloMember);
            if (result.id != null) {
                mappingCache.put(trelloUsername, result);
            } else {
                org.trello4j.model.Member trelloMember2 = trelloClient.getMember(trelloUsername);
                result = (trelloMember2 == null) ? UNKNOWN_MEMBER : findMember(trelloMember2);
                if (trelloMember2 == null) {
                    System.out.println("@@@ " + trelloMember.getUsername() + " > " + trelloMember.getFullName());
                }
                mappingCache.put(trelloUsername, result);
            }
        }

        if (result.id == null) {
            System.err.println("Missing trello->clubhouse user mapping for " + trelloMember.getUsername());
        }
        return result;
    }

    public Member getClubhouseMember(String trelloUsername) {
        if (trelloUsername == null) {
            return UNKNOWN_MEMBER;
        }
        Member result = mappingCache.getIfPresent(trelloUsername);
        if (result == null) {
            org.trello4j.model.Member trelloMember2 = trelloClient.getMember(trelloUsername);
            result = (trelloMember2 == null) ? UNKNOWN_MEMBER : findMember(trelloMember2);
            mappingCache.put(trelloUsername, result);
        }
        return result;
    }

    public String getTrelloUserDisplayName(org.trello4j.model.Member member) {
        if (member.getFullName() != null && member.getFullName().length() > 0) {
            return member.getFullName();
        }
        return getTrelloUserDisplayName(member.getUsername());
    }

    public String getTrelloUserDisplayName(String userLogin) {
        try {
            return githubUserCache.get(userLogin);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException) {
                throw new UncheckedIOException((IOException) e.getCause());
            } else {
                throw new UncheckedExecutionException(e.getCause());
            }
        }
    }

    private Member findMember(org.trello4j.model.Member trelloMember) {
        Preconditions.checkNotNull(trelloMember);
        for (Member member : members) {
            if (matches(member, trelloMember)) {
                return member;
            }
        }
        return UNKNOWN_MEMBER;
    }

    private boolean matches(Member clubhouseMember, org.trello4j.model.Member trelloMember) {
        Preconditions.checkNotNull(clubhouseMember);
        Preconditions.checkNotNull(trelloMember);

        Profile profile = clubhouseMember.profile;
        if (equalsIgnoreCase(trelloMember.getUsername(), profile.mention_name) || equalsIgnoreCase(trelloMember.getFullName(), profile.mention_name)) {
            return true;
        }
        if (equalsIgnoreCase(trelloMember.getUsername(), profile.name) || equalsIgnoreCase(trelloMember.getFullName(), profile.name)) {
            return true;
        }
        String clubhouseName = clubhouseNameByTrelloLogin.get(trelloMember.getUsername());
        return equalsIgnoreCase(clubhouseName, profile.mention_name);
    }

    private static boolean equalsIgnoreCase(String str1, String str2) {
        return str1 != null && str1.equalsIgnoreCase(str2);
    }

    private class TrelloUserDisplayNameLoader extends CacheLoader<String, String> {
        @Override
        public String load(String userLogin) throws IOException {
            org.trello4j.model.Member userFull = trelloClient.getMember(userLogin);
            if (userFull != null && userFull.getFullName() != null && userFull.getFullName().length() > 0) {
                return userFull.getFullName();
            }
            if (userFull != null && userFull.getUsername() != null && userFull.getUsername().length() > 0) {
                return userFull.getUsername();
            }
            return userLogin;
        }
    }
}
