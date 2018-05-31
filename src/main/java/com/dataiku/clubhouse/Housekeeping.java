package com.dataiku.clubhouse;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import io.clubhouse4j.api.v3beta.*;

@SuppressWarnings("squid:S2629")
public class Housekeeping {
    private final StoriesService chStoriesService;
    private final EpicsService chEpicsService;
    private final EpicWorkflowService chEpicWorkflowService;

    private static final Logger logger = Logger.getLogger("com.dataiku.clubhouse.housekeeping");
    private final EpicState epicFinishedState;
    private final MilestonesService chMilestonesService;

    public Housekeeping(ClubhouseClient clubhouseClient) throws IOException {
        chStoriesService = new StoriesService(clubhouseClient);
        chEpicsService = new EpicsService(clubhouseClient);
        chMilestonesService = new MilestonesService(clubhouseClient);
        chEpicWorkflowService = new EpicWorkflowService(clubhouseClient);

        epicFinishedState = getEpicFinishedState();
    }

    public void archiveCompletedEpicsAndStories(Duration closeDelay) throws IOException {
        archiveCompletedStories(closeDelay);
        archiveCompletedEpics(closeDelay);
    }

    public void archiveCompletedStories(Duration closeDelay) throws IOException {
        SearchStoriesParams params = new SearchStoriesParams();
        params.archived = false;
        params.completed_at_end = Instant.now().minus(closeDelay);
        List<StorySlim> storiesToArchive = chStoriesService.searchStories(params);
        logger.log(Level.INFO, "Archiving " + storiesToArchive.size() + " stories");

        ExecutorService executor = Executors.newFixedThreadPool(32);
        for (StorySlim story : storiesToArchive) {
            executor.submit(() -> {
                try {
                    archiveStory(story);
                } catch (IOException e) {
                    logger.warning("Failed to archive story " + story.id + " > " + story.name);
                }
            });
        }
        executor.shutdown();
        try {
            executor.awaitTermination(24, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            logger.warning("Interupted while waiting for migration to finish.");
            Thread.currentThread().interrupt();
        }

    }

    private void archiveStory(StorySlim story) throws IOException {
        UpdateStoryParams updateStoryParams = new UpdateStoryParams();
        updateStoryParams.archived = true;
        logger.log(Level.INFO, "Archiving story " + story.id);
        chStoriesService.updateStory(story.id, updateStoryParams);
    }

    public void closeCompletedEpics() throws IOException {
        List<EpicSlim> epics = chEpicsService.listEpics();
        List<EpicSlim> epicsToClose = epics.stream().filter(epic -> nonArchived(epic) && doneButNotComplete(epic)).collect(Collectors.toList());

        logger.log(Level.INFO, "Will close " + epicsToClose.size() + " epics out of " + epics.size());
        for (EpicSlim epic : epicsToClose) {
            logger.info("Closing Epic " + epic.id + ": " + epic.name);
            UpdateEpicParams params = new UpdateEpicParams();
            params.epic_state_id = epicFinishedState.id;
            chEpicsService.updateEpic(epic.id, params);
        }
    }

    public void createMilestonesFromEpics() throws IOException {
        List<Milestone> milestones = chMilestonesService.listMilestones();

        List<EpicSlim> epics = chEpicsService.listEpics();
        List<EpicSlim> matchingEpics = epics.stream().filter(epic -> epic.name.matches("\\d.\\d.\\d Enhancements")).collect(Collectors.toList());
        for (EpicSlim matchingEpic : matchingEpics) {
            if (matchingEpic.milestone_id != null) {
                continue; // Epic already assigned to a milestone.
            }

            String name = matchingEpic.name;
            String milestoneName = "DSS " + name.substring(0, 6);
            Milestone milestone = milestones.stream().filter(m -> m.name.equals(milestoneName)).findAny().orElse(null);
            if (milestone == null) {
                // Create missing milestone
                logger.log(Level.INFO, "Creating missing milestone " + milestoneName);
                CreateMilestoneParams createMilestone = new CreateMilestoneParams();
                createMilestone.completed_at_override = matchingEpic.completed_at_override;
                createMilestone.name = milestoneName;
                createMilestone.state = matchingEpic.state;
                milestone = chMilestonesService.createMilestone(createMilestone);
                milestones.add(milestone);
            }

            // Associate epic with milestone
            logger.log(Level.INFO, "Associating milestone " + milestoneName + " with epic " + matchingEpic.name);
            UpdateEpicParams updateEpicParams = new UpdateEpicParams();
            updateEpicParams.milestone_id = milestone.id;
            chEpicsService.updateEpic(matchingEpic.id, updateEpicParams);
        }

        // Reorder milestones alphabetically
        logger.log(Level.INFO, "Reordering milestones");
        List<Milestone> unsortedMilestones = chMilestonesService.listMilestones();
        List<Milestone> sortedMilestones = new ArrayList<>(unsortedMilestones);
        sortedMilestones.sort((o1, o2) -> o1.name.compareTo(o2.name) * -1);
        Milestone firstUnsortedMilestone = unsortedMilestones.get(0);
        Milestone firstSortedMilestone = sortedMilestones.get(0);

        UpdateMilestoneParams updateMilestoneParams = new UpdateMilestoneParams();
        updateMilestoneParams.before_id = firstUnsortedMilestone.id;
        try {
            logger.info("Move Milestone " + firstSortedMilestone.name + " before " + firstUnsortedMilestone.name);
            chMilestonesService.updateMilestone(firstSortedMilestone.id, updateMilestoneParams);
        } catch (RuntimeException e) {
            logger.info("Milestone " + firstSortedMilestone.name + " already ordered");
        }

        for (int i = 1, milestonesSize = sortedMilestones.size(); i < milestonesSize; i++) {
            Milestone currentMilestone = sortedMilestones.get(i);
            updateMilestoneParams = new UpdateMilestoneParams();
            updateMilestoneParams.after_id = sortedMilestones.get(i - 1).id;
            logger.info("Move Milestone " + currentMilestone.name + " after " + sortedMilestones.get(i - 1).name);
            try {
                chMilestonesService.updateMilestone(currentMilestone.id, updateMilestoneParams);
            } catch (RuntimeException e) {
                logger.info("Milestone " + currentMilestone.name + " already ordered");
            }
        }
    }

    private void archiveCompletedEpics(Duration closeDelay) throws IOException {
        Instant deadline = Instant.now().minus(closeDelay);
        List<EpicSlim> epics = chEpicsService.listEpics();
        List<EpicSlim> epicsToArchive = epics.stream().filter(epic -> nonArchived(epic) && completedBefore(epic, deadline)).collect(Collectors.toList());

        logger.log(Level.INFO, "Archiving " + epicsToArchive.size() + " epics");
        for (EpicSlim epic : epicsToArchive) {
            UpdateEpicParams updateEpicParams = new UpdateEpicParams();
            updateEpicParams.archived = true;
            logger.log(Level.INFO, "Archiving epic " + epic.id);
            chEpicsService.updateEpic(epic.id, updateEpicParams);
        }
    }

    public void archiveEpics(String prefix) throws IOException {
        List<EpicSlim> epics = chEpicsService.listEpics();
        List<EpicSlim> epicsToArchive = epics.stream().filter(epic -> nonArchived(epic) && epic.name.startsWith(prefix)).collect(Collectors.toList());
        logger.log(Level.INFO, "Archiving " + epicsToArchive.size() + " epics");
        for (EpicSlim epic : epicsToArchive) {
            UpdateEpicParams updateEpicParams = new UpdateEpicParams();
            updateEpicParams.archived = true;
            logger.log(Level.INFO, "Archiving epic " + epic.id);
            chEpicsService.updateEpic(epic.id, updateEpicParams);
        }
    }

    private static boolean doneButNotComplete(EpicSlim epic) {
        return !epic.completed &&
                epic.stats.num_stories_done > 0 &&
                epic.stats.num_stories_started == 0 &&
                epic.stats.num_stories_unstarted == 0;
    }

    private static boolean completedBefore(EpicSlim epic, Instant deadline) {
        return epic.completed && epic.completed_at != null && epic.completed_at.isBefore(deadline);
    }

    private static boolean nonArchived(EpicSlim epic) {
        return epic.archived != null && !epic.archived;
    }

    private EpicState getEpicFinishedState() throws IOException {
        EpicWorkflow epicWorkflow = chEpicWorkflowService.getEpicWorkflow();
        for (EpicState epicState : epicWorkflow.epic_states) {
            if ("done".equalsIgnoreCase(epicState.type)) {
                return epicState;
            }
        }
        throw new IllegalArgumentException("Cannot find the Epic Finished state");
    }
}
