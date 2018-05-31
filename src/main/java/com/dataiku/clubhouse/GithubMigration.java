package com.dataiku.clubhouse;

import static java.util.Collections.emptyList;

import java.io.IOException;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.egit.github.core.Comment;
import org.eclipse.egit.github.core.Issue;
import org.eclipse.egit.github.core.Milestone;
import org.eclipse.egit.github.core.Repository;
import org.eclipse.egit.github.core.User;
import org.eclipse.egit.github.core.client.GitHubClient;
import org.eclipse.egit.github.core.client.PageIterator;
import org.eclipse.egit.github.core.service.IssueService;
import org.eclipse.egit.github.core.service.RepositoryService;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import io.clubhouse4j.api.v3beta.*;

@SuppressWarnings("unused")
public class GithubMigration {

    public enum IssueState {
        OPEN("open"),
        CLOSED("closed"),
        ALL("all");

        public final String githubState;

        IssueState(String githubState) {
            this.githubState = githubState;
        }
    }

    private static final Pattern GITHUB_IMG_HTML = Pattern.compile("<img(?<width>\\s+width=\"[0-9]+\")?(?<alt>\\s+alt=\"(?<desc>[^\"]*)\")?\\s+src=\"(?<src>[^\"]*)\">");
    private static final Logger logger = Logger.getLogger("com.dataiku.clubhouse.migration.github");

    private final StoriesService storiesService;
    private final EpicsService epicsService;
    private final WorkflowState finishedState;
    private final Project project;
    private final List<EpicSlim> epicList;

    private final Repository githubRepository;
    private final IssueService githubIssueService;

    private final GithubUserMapping userMapping;
    private boolean dryRun;

    public GithubMigration(ClubhouseClient clubhouseClient, String clubhouseProjectName, GitHubClient githubClient, String gitRepositoryName, GithubMigrationParams migrationParams) throws IOException {
        this.storiesService = new StoriesService(clubhouseClient);
        this.epicsService = new EpicsService(clubhouseClient);
        this.project = MigrationHelpers.getProject(new ProjectsService(clubhouseClient), clubhouseProjectName);

        this.githubRepository = getRepository(new RepositoryService(githubClient), gitRepositoryName);
        this.githubIssueService = new IssueService(githubClient);

        this.userMapping = new GithubUserMapping(clubhouseClient, githubClient, migrationParams.usersMapping);
        this.finishedState = MigrationHelpers.getStoryState(new TeamsService(clubhouseClient), project, "Completed");
        this.epicList = epicsService.listEpics();
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void migrateGithubIssues() {
        migrateGithubIssues(IssueState.OPEN);
    }

    public void migrateGithubIssues(IssueState issueState) {
        logger.info("Collecting issues to migrate.");
        Map<String, String> filterData = new HashMap<>();
        if (issueState != null) {
            filterData.put("state", issueState.githubState);
        }

        Map<Integer, Issue> issuesToMigrate = new HashMap<>();
        PageIterator<Issue> pageIterator = githubIssueService.pageIssues(githubRepository, filterData);
        for (Collection<Issue> page : pageIterator) {
            List<Issue> issues = page.stream().filter(GithubMigration::isValidGithubIssue).collect(Collectors.toList());
            for (Issue issue : issues) {
                issuesToMigrate.put(issue.getNumber(), issue);
            }
            logger.info("Found " + issuesToMigrate.size() + " issues to migrate.");
        }

        logger.info("Migrating the Github issues.");
        ExecutorService executor = Executors.newFixedThreadPool(32);
        for (Issue issue : issuesToMigrate.values()) {
            executor.submit(new MigrateGithubIssueRunnable(issue));
        }
        executor.shutdown();
        try {
            executor.awaitTermination(24, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            logger.warning("Interrupted while waiting for migration to finish.");
            Thread.currentThread().interrupt();
        }
    }

    public void migrateGithubIssue(int issueNumber) throws IOException {
        Issue githubIssue = githubIssueService.getIssue(githubRepository, issueNumber);
        if (isPullRequest(githubIssue)) {
            throw new IllegalArgumentException("Cannot migrate pull requests into Clubhouse but Issue #" + issueNumber + " is a PR.");
        }
        migrateGithubIssue(githubIssue);
    }

    private void migrateGithubIssue(Issue githubIssue) throws IOException {
        int issueNumber = githubIssue.getNumber();

        List<String> footerNotes = new ArrayList<>();
        footerNotes.add(MessageFormat.format("* This card has been imported from Github issue [#{0,number,0}]({1})", issueNumber, githubIssue.getHtmlUrl()));

        CreateStoryParams createStoryParams = new CreateStoryParams();
        createStoryParams.name = githubIssue.getTitle();
        createStoryParams.project_id = project.id;
        createStoryParams.story_type = "bug";
        createStoryParams.created_at = toInstant(githubIssue.getCreatedAt());
        createStoryParams.updated_at = toInstant(githubIssue.getUpdatedAt());

        // State / workflow
        if ("closed".equals(githubIssue.getState())) {
            createStoryParams.workflow_state_id = finishedState.id;
        }
        if (githubIssue.getClosedAt() != null) {
            createStoryParams.completed_at_override = toInstant(githubIssue.getClosedAt());
        }

        // Labels
        createStoryParams.labels = migrateLabels(githubIssue);

        // Owner / Assignee
        User githubReporter = githubIssue.getUser();
        Member reporter = userMapping.getClubhouseMember(githubReporter);
        Member assignee = userMapping.getClubhouseMember(githubIssue.getAssignee());
        if (reporter.id != null) {
            createStoryParams.requested_by_id = reporter.id;
        } else if (githubReporter != null) {
            footerNotes.add(MessageFormat.format("* Originally reported by **{0}**", userMapping.getGithubUserDisplayName(githubReporter)));
        }
        createStoryParams.owner_ids = assignee.id == null ? null : Collections.singletonList(assignee.id);

        // Comments
        createStoryParams.comments = migrateComments(issueNumber);

        // Milestone
        createStoryParams.epic_id = migrateEpic(githubIssue);

        // External reference
        createStoryParams.external_tickets = Collections.singletonList(new CreateExternalTicketParams("github-" + issueNumber, githubIssue.getHtmlUrl()));
        createStoryParams.external_id = githubIssue.getHtmlUrl();

        // Body / description
        String description = postProcessImages(githubIssue.getBody());
        String descriptionFooter = "\n\n---\n\n#### Migration notes\n\n" + Joiner.on("\n\n").join(footerNotes) + "\n\n---\n\n";
        createStoryParams.description = description + descriptionFooter;

        if (!dryRun) {
            storiesService.createStory(createStoryParams);
        }
    }

    private List<CreateLabelParams> migrateLabels(Issue githubIssue) {
        if (githubIssue.getLabels() == null) {
            return emptyList();
        }
        return githubIssue.getLabels().stream().map(label -> new CreateLabelParams(label.getName(), getColor(label.getColor()))).collect(Collectors.toList());
    }

    private List<CreateCommentParams> migrateComments(int issueNumber) throws IOException {
        List<CreateCommentParams> result = new ArrayList<>();
        for (Comment comment : githubIssueService.getComments(githubRepository, issueNumber)) {
            CreateCommentParams createComment = new CreateCommentParams();
            createComment.author_id = userMapping.getClubhouseMemberUUID(comment.getUser());
            createComment.created_at = toInstant(comment.getCreatedAt());
            createComment.text = postProcessImages(comment.getBody());
            createComment.updated_at = toInstant(comment.getUpdatedAt());
            result.add(createComment);
        }
        return result;
    }

    private Long migrateEpic(Issue githubIssue) {
        if (dryRun) {
            return null;
        }
        Milestone milestone = githubIssue.getMilestone();
        if (milestone == null) {
            return null;
        }

        String epicName = milestone.getTitle();
        if (epicName.matches("V\\s[0-9]+\\..*")) {
            epicName = epicName.substring(2) + " Enhancements";
        }
        synchronized (epicList) {
            return MigrationHelpers.getOrCreateEpic(epicsService, epicList, epicName).id;
        }
    }

    @VisibleForTesting
    static String postProcessImages(String content) {
        Matcher matcher = GITHUB_IMG_HTML.matcher(content);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            String src = matcher.group("src");
            String desc = matcher.group("desc");
            if (desc == null) {
                String[] split = src.split("/");
                desc = split.length > 0 ? split[split.length - 1] : "image.png";
            }
            content = content.substring(0, start) + "![" + desc + "](" + src + ")" + content.substring(end);

            matcher = GITHUB_IMG_HTML.matcher(content);
        }
        return content;
    }

    private static Repository getRepository(RepositoryService repositoryService, String name) throws IOException {
        List<Repository> repositories = repositoryService.getRepositories();
        for (Repository repository : repositories) {
            if (name.equals(repository.getName())) {
                return repository;
            }
        }
        return null;
    }

    private static boolean isValidGithubIssue(Issue issue) {
        return !isPullRequest(issue);
    }

    private static boolean isPullRequest(Issue issue) {
        return issue.getPullRequest() != null && issue.getPullRequest().getHtmlUrl() != null;
    }

    private static String getColor(String color) {
        return color == null || color.startsWith("#") ? color : "#" + color;
    }

    private static Instant toInstant(Date date) {
        return Instant.ofEpochMilli(date.getTime());
    }

    private class MigrateGithubIssueRunnable implements Runnable {
        private final Issue issue;

        public MigrateGithubIssueRunnable(Issue issue) {
            this.issue = issue;
        }

        @Override
        @SuppressWarnings("squid:S2629")
        public void run() {
            boolean retry = false;
            do {
                try {
                    // Checking issue in ClubHouse to see if it is not already present.
                    StorySlim existingStory = findGithubIssueInClubhouse(issue);
                    if (existingStory == null) {
                        migrateGithubIssue(issue);
                        logger.log(Level.INFO, "Migrated issue #" + issue.getNumber());
                    } else {
                        logger.log(Level.INFO, "Skipping issue #" + issue.getNumber() + ": already migrated to Clubhouse with id=" + existingStory.id);
                    }
                } catch (org.eclipse.egit.github.core.client.RequestException re) {
                    if ((re.getStatus() == 403 && re.getMessage().startsWith("You have triggered an abuse")) || re.getStatus() == 502) {
                        try {
                            long coolDown = 60L + Math.round(Math.random() * 60.0d);
                            logger.log(Level.WARNING, "We have triggered an abuse on Github servers. Waiting for " + coolDown + " seconds before retrying.");
                            Thread.sleep(coolDown * 1000L);
                            retry = true;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            logger.log(Level.WARNING, "Failed to migrate issue #" + issue.getNumber(), e);
                        }
                    }
                    // Lets
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to migrate issue #" + issue.getNumber(), e);
                }
            } while (retry);
        }

        private StorySlim findGithubIssueInClubhouse(Issue githubIssue) {
            if (!dryRun) {
                SearchStoriesParams searchStoriesParams = new SearchStoriesParams();
                searchStoriesParams.external_id = githubIssue.getHtmlUrl();
                List<StorySlim> storySlims = storiesService.searchStories(searchStoriesParams);
                if (!storySlims.isEmpty()) {
                    return storySlims.get(0);
                }
            }
            return null;
        }
    }
}
