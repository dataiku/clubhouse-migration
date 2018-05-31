package com.dataiku.clubhouse;

import static com.dataiku.clubhouse.TrelloBoardMigrationParams.MIGRATE_LISTS_AS_EPICS;
import static com.dataiku.clubhouse.TrelloBoardMigrationParams.MIGRATE_LISTS_AS_LABELS;
import static com.dataiku.clubhouse.TrelloBoardMigrationParams.MIGRATE_LISTS_AS_STATES;

import java.io.IOException;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.trello4j.Trello;
import org.trello4j.model.Action;
import org.trello4j.model.Board;
import org.trello4j.model.Card;
import org.trello4j.model.Checklist;

import com.google.common.base.Joiner;
import io.clubhouse4j.api.v3beta.*;

@SuppressWarnings({"UnusedReturnValue", "unused"})
public class TrelloMigration {

    private static final Logger logger = Logger.getLogger("com.dataiku.clubhouse.migration.trello");
    private static final List<String> BUGS_LABELS = Arrays.asList("bug", "type:bug", "type: bug");
    private static final List<String> REVIEW_LABELS = Arrays.asList("verified", "__fixed", "fixed", "status: fixed (to verify)", "verified - keeping open because needs test", "[ qa ] - to verify", "fixed (to verify)", "to verify (old)", "Done (to verify)");

    private final StoriesService storiesService;
    private final EpicsService epicsService;
    private final LinkedFilesService linkedFileService;
    private final Project project;
    private final TrelloMigrationParams migrationParams;
    private final List<EpicSlim> epicList;
    private final long completedStateId;
    private final long reviewStateId;
    private final Map<String, WorkflowState> workflowStates;

    private final Trello trelloClient;
    private final String trelloOrganization;

    private final TrelloUserMapping userMapping;
    private boolean dryRun;

    public TrelloMigration(ClubhouseClient clubhouseClient, String clubhouseProjectName, Trello trelloClient, String trelloOrganization, TrelloMigrationParams migrationParams) throws IOException {
        this.storiesService = new StoriesService(clubhouseClient);
        this.epicsService = new EpicsService(clubhouseClient);
        this.linkedFileService = new LinkedFilesService(clubhouseClient);
        this.project = MigrationHelpers.getProject(new ProjectsService(clubhouseClient), clubhouseProjectName);

        this.trelloClient = trelloClient;
        this.trelloOrganization = trelloOrganization;
        this.migrationParams = migrationParams;

        this.userMapping = new TrelloUserMapping(clubhouseClient, trelloClient, migrationParams.usersMapping);

        this.workflowStates = MigrationHelpers.getWorkflowStatesMap(new TeamsService(clubhouseClient), project);
        this.completedStateId = workflowStates.get("Completed").id;
        this.reviewStateId = workflowStates.get("Ready for Review").id;
        this.epicList = epicsService.listEpics();
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void run(int threads) {
        logger.info("Starting migration...");
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        scheduleMigrationTasks(executor);
        executor.shutdown();
        logger.info("Waiting for completion of pending tasks...");
        try {
            executor.awaitTermination(24, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            logger.warning("Interupted while waiting for migration to finish.");
            Thread.currentThread().interrupt();
        }
        logger.info("Done.");
    }

    private void scheduleMigrationTasks(ExecutorService executor) { // NOSONAR
        for (Board board : trelloClient.getBoardsByOrganization(trelloOrganization)) {
            if (!board.isClosed() && getBoardMigrationParams(board.getName()).migrate) {
                for (org.trello4j.model.List list : trelloClient.getListByBoard(board.getId())) {
                    if (!migrationParams.ignoredLists.contains(list.getName())) {
                        for (Card card : trelloClient.getCardsByList(list.getId())) {
                            executor.submit(new MigrateTrelloRunnable(board, list, card));
                        }
                    } else {
                        logger.fine("Skipping ignored list: " + list.getName());
                    }
                }
            } else {
                logger.info("Skipping closed or ignored board: " + board.getName());
            }
        }
    }

    private TrelloBoardMigrationParams getBoardMigrationParams(String boardName) {
        Optional<TrelloBoardMigrationParams> boardParams = Optional.empty();
        if (migrationParams.boards != null) {
            boardParams = migrationParams.boards.stream().filter(itBoardParams -> boardName.equalsIgnoreCase(itBoardParams.name)).findFirst();
        }
        return boardParams.orElse(new TrelloBoardMigrationParams(boardName, false, MIGRATE_LISTS_AS_LABELS));
    }

    public void migrateTrelloCard(String cardId) throws IOException {
        Card card = trelloClient.getCard(cardId);
        Board board = trelloClient.getBoard(card.getIdBoard());
        org.trello4j.model.List list = trelloClient.getList(card.getIdList());
        migrateTrelloCard(board, list, card);
    }

    private void migrateTrelloCard(Board board, org.trello4j.model.List list, Card card) throws IOException { // NOSONAR
        Map<String, Object> actionsParams = new HashMap<>();
        actionsParams.put("filter", "all");
        actionsParams.put("limit", 1000);
        List<Action> actions = trelloClient.getActionsByCard(card.getId(), actionsParams);
        actions.sort(Comparator.comparing(Action::getDate));
        Instant firstTimestamp = actions.isEmpty() ? null : toInstant(actions.get(0).getDate());
        Instant lastTimestamp = actions.isEmpty() ? null : toInstant(actions.get(actions.size() - 1).getDate());

        List<String> footerNotes = new ArrayList<>();
        footerNotes.add(MessageFormat.format("* This card has been imported from Trello card [#{0}]({1})", card.getId(), card.getUrl()));

        CreateStoryParams createStoryParams = new CreateStoryParams();
        createStoryParams.name = card.getName();
        createStoryParams.project_id = project.id;
        createStoryParams.story_type = isBug(card) ? "bug" : "feature";
        createStoryParams.created_at = firstTimestamp;
        createStoryParams.updated_at = lastTimestamp;

        // State / workflow
        createStoryParams.workflow_state_id = migrateState(card, board, list);
        if (createStoryParams.workflow_state_id != null && createStoryParams.workflow_state_id == completedStateId) {
            createStoryParams.completed_at_override = lastTimestamp;
        }

        // Owner / Assignee
        org.trello4j.model.Member trelloReporter = actions.isEmpty() ? null : actions.get(0).getMemberCreator();
        Member reporter = userMapping.getClubhouseMember(trelloReporter);
        if (reporter.id != null) {
            createStoryParams.requested_by_id = reporter.id;
        } else if (trelloReporter != null) {
            footerNotes.add(MessageFormat.format("* Originally reported by **{0}**", userMapping.getTrelloUserDisplayName(trelloReporter)));
        }
        if (card.getIdMembers() != null) {
            createStoryParams.owner_ids = card.getIdMembers().stream().
                    map(userMapping::getClubhouseMember).
                    filter(member -> member.id != null).
                    map(member -> member.id).
                    collect(Collectors.toList());
        }

        // Tasks
        createStoryParams.tasks = migrateTasks(card);

        // Labels
        createStoryParams.labels = migrateLabels(card, board, list);

        // Attachments
        createStoryParams.linked_file_ids = migrateAttachments(card);

        // Epic
        createStoryParams.epic_id = migrateEpic(card, board, list);

        // Comments
        createStoryParams.comments = migrateComments(actions);

        // External reference
        createStoryParams.external_tickets = Collections.singletonList(new CreateExternalTicketParams("trello-" + card.getId(), card.getUrl()));
        createStoryParams.external_id = card.getUrl();

        // Body / description
        createStoryParams.description = migrateDescription(card, footerNotes);

        if (!dryRun) {
            storiesService.createStory(createStoryParams);
        }
    }

    private Long migrateState(Card card, Board board, org.trello4j.model.List list) {
        if (card.isClosed()) {
            return completedStateId;
        }

        if (MIGRATE_LISTS_AS_STATES.equalsIgnoreCase(getBoardMigrationParams(board.getName()).migrateListsAs)) {
            Map<String, String> listStateMapping = getBoardMigrationParams(board.getName()).listStateMapping;
            if (listStateMapping == null) {
                throw new IllegalStateException("Missing 'listStateMapping' for board " + board.getName());
            }
            String stateName = listStateMapping.get(list.getName());
            if (stateName == null) {
                throw new IllegalStateException("Missing mapping for " + list.getName() + "in 'listStateMapping' for board " + board.getName());
            }
            WorkflowState state = workflowStates.get(stateName);
            if (state == null) {
                throw new IllegalStateException("Unknown workflow state: " + stateName);
            }
            return state.id;
        }

        if (inReviewState(card)) {
            return reviewStateId;
        }

        // Default state
        return null;
    }

    private List<CreateTaskParams> migrateTasks(Card card) {
        List<CreateTaskParams> createTaskParams = new ArrayList<>();
        for (Checklist checklist : trelloClient.getChecklistByCard(card.getId())) {
            checklist.getCheckItems().stream().sorted(Comparator.comparingDouble(Checklist.CheckItem::getPos)).forEachOrdered(item -> {
                CreateTaskParams createTaskParam = new CreateTaskParams();
                createTaskParam.complete = "complete".equalsIgnoreCase(item.getState());
                createTaskParam.description = item.getName();
                createTaskParams.add(createTaskParam);
            });
        }
        return createTaskParams;
    }

    private String migrateDescription(Card card, List<String> footerNotes) {
        String description = card.getDesc();
        Card.Attachment cover = trelloClient.getCoverByCard(card.getId());
        if (cover != null) {
            description = "![" + cover.getName() + "](" + cover.getUrl() + ")\n\n" + description;
        }
        String descriptionFooter = "\n\n---\n\n#### Migration notes\n\n" + Joiner.on("\n\n").join(footerNotes) + "\n\n---\n\n";
        return description + descriptionFooter;
    }

    private List<CreateCommentParams> migrateComments(List<Action> actions) {
        List<CreateCommentParams> result = new ArrayList<>();
        for (Action comment : extractComments(actions)) {
            CreateCommentParams createComment = new CreateCommentParams();
            createComment.author_id = userMapping.getClubhouseMember(comment.getMemberCreator()).id;
            createComment.created_at = toInstant(comment.getDate());
            String commentText = comment.getData().getText();
            if (createComment.author_id == null) {
                String trelloUserDisplayName = userMapping.getTrelloUserDisplayName(comment.getMemberCreator());
                if (trelloUserDisplayName != null && trelloUserDisplayName.length() > 0) {
                    commentText = "**" + trelloUserDisplayName + ":** " + commentText;
                }
            }
            createComment.text = commentText;
            result.add(createComment);
        }
        return result;
    }

    private Long migrateEpic(Card card, Board board, org.trello4j.model.List list) throws IOException {
        if (dryRun) {
            return null;
        }
        TrelloBoardMigrationParams boardMigrationParams = getBoardMigrationParams(board.getName());
        String epicName = mapEpicName(card, board, boardMigrationParams);
        if (MIGRATE_LISTS_AS_EPICS.equalsIgnoreCase(boardMigrationParams.migrateListsAs)) {
            epicName += " - " + list.getName();
        }
        return getOrCreateEpic(epicName).id;
    }

    private static String mapEpicName(Card card, Board board, TrelloBoardMigrationParams boardMigrationParams) {
        if (boardMigrationParams.migrateLabelsIn != null) {
            for (Card.Label label : card.getLabels()) {
                if (boardMigrationParams.migrateLabelsIn.containsKey(label.getName())) {
                    return boardMigrationParams.migrateLabelsIn.get(label.getName());
                }
            }
        }
        return board.getName();
    }

    private EpicSlim getOrCreateEpic(String epicName) throws IOException {
        synchronized (epicList) {
            return MigrationHelpers.getOrCreateEpic(epicsService, epicList, epicName);
        }
    }

    private List<CreateLabelParams> migrateLabels(Card card, Board board, org.trello4j.model.List list) {
        List<CreateLabelParams> result = new ArrayList<>();
        if (card.getLabels() != null) {
            result.addAll(card.getLabels().stream().
                    filter(label -> !BUGS_LABELS.contains(label.getName().toLowerCase())).
                    map(label -> new CreateLabelParams(mapLabel(label.getName()), getColor(label.getColor()))).
                    collect(Collectors.toList()));
        }
        if (MIGRATE_LISTS_AS_LABELS.equalsIgnoreCase(getBoardMigrationParams(board.getName()).migrateListsAs)) {
            result.add(new CreateLabelParams(mapLabel(list.getName())));
        }
        return result;
    }

    private String mapLabel(String name) {
        return migrationParams.labelsMapping.getOrDefault(name, name);
    }

    private List<Long> migrateAttachments(Card card) throws IOException {
        List<Long> result = new ArrayList<>();
        for (Card.Attachment attachment : trelloClient.getAttachmentsByCard(card.getId())) {
            CreateLinkedFileParams createLinkedFile = new CreateLinkedFileParams();
            createLinkedFile.name = attachment.getName();
            createLinkedFile.url = attachment.getUrl();
            createLinkedFile.type = "url";
            createLinkedFile.size = attachment.getBytes();
            createLinkedFile.description = "Migrated from Trello attachment " + attachment.get_id();
            Member linkedFileUploader = userMapping.getClubhouseMember(attachment.getIdMember());
            if (linkedFileUploader.id != null) {
                createLinkedFile.uploader_id = linkedFileUploader.id;
            }
            if (!dryRun) {
                LinkedFile linkedFile = linkedFileService.createLinkedFile(createLinkedFile);
                result.add(linkedFile.id);
            }
        }
        return result;
    }

    private boolean isBug(Card card) {
        List<Card.Label> labels = card.getLabels();
        return labels != null && labels.stream().anyMatch(label -> BUGS_LABELS.contains(label.getName().toLowerCase()));
    }

    private boolean inReviewState(Card card) {
        List<Card.Label> labels = card.getLabels();
        return labels != null && labels.stream().anyMatch(label -> REVIEW_LABELS.contains(label.getName().toLowerCase()));
    }

    private List<Action> extractComments(List<Action> actions) {
        return actions.stream().filter(action -> "commentCard".equalsIgnoreCase(action.getType())).collect(Collectors.toList());
    }

    private class MigrateTrelloRunnable implements Runnable {
        private final Board board;
        private final org.trello4j.model.List list;
        private final Card card;

        public MigrateTrelloRunnable(Board board, org.trello4j.model.List list, Card card) {
            this.board = board;
            this.list = list;
            this.card = card;
        }

        @Override
        @SuppressWarnings("squid:S2629")
        public void run() {
            try {
                // Checking issue in ClubHouse to see if it is not already present.
                StorySlim existingStory = findTrelloCardInClubhouse(card);
                if (existingStory == null) {
                    migrateTrelloCard(board, list, card);
                    logger.log(Level.INFO, "Migrated issue #" + card.getName());
                } else {
                    logger.log(Level.INFO, "Skipping issue #" + card.getName() + ": already migrated to Clubhouse with id=" + existingStory.id);
                }
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to migrate card #" + card.getId(), e);
            }
        }

        private StorySlim findTrelloCardInClubhouse(Card card) throws IOException {
            if (dryRun) {
                return null;
            }
            SearchStoriesParams searchStoriesParams = new SearchStoriesParams();
            searchStoriesParams.external_id = card.getUrl();
            List<StorySlim> storySlims = storiesService.searchStories(searchStoriesParams);
            if (!storySlims.isEmpty()) {
                return storySlims.get(0);
            }
            return null;
        }

    }

    private static String getColor(String color) {
        if (color == null) {
            return null;
        }
        switch (color) {
            case "lime":
                return "#51e898";
            case "yellow":
                return "#f2d600";
            case "purple":
                return "#c377e0";
            case "blue":
                return "#0079bf";
            case "red":
                return "#eb5a46";
            case "green":
                return "#61bd4f";
            case "orange":
                return "#ffab4a";
            case "black":
                return "#000000";
            case "sky":
                return "#00c2e0";
            case "pink":
                return "#ff80ce";
            default:
                return color.startsWith("#") ? color : "#" + color;
        }
    }

    private static Instant toInstant(Date date) {
        return Instant.ofEpochMilli(date.getTime());
    }
}
