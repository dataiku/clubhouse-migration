package com.dataiku.clubhouse;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import io.clubhouse4j.api.v3beta.*;

@SuppressWarnings("unused")
public class ClubhouseCleaner {

    private static final Logger logger = Logger.getLogger("com.dataiku.clubhouse.cleaner");

    private final StoriesService storiesService;
    private final EpicsService epicsService;
    private final LabelsService labelsService;
    private final MilestonesService milestonesService;

    public ClubhouseCleaner(ClubhouseClient clubhouseClient) {
        this.storiesService = new StoriesService(clubhouseClient);
        this.epicsService = new EpicsService(clubhouseClient);
        this.labelsService = new LabelsService(clubhouseClient);
        this.milestonesService = new MilestonesService(clubhouseClient);
    }

    public void run() throws IOException {
        ExecutorService executor = Executors.newFixedThreadPool(32);
        logger.info("Deleting labels...");
        for (Label label : labelsService.listLabels()) {
            executor.submit(() -> {
                logger.info("Deleting label " + label.id + " > " + label.name);
                try {
                    labelsService.deleteLabel(label.id);
                } catch (IOException e) {
                    logger.warning("Failed to delete label " + label.id + " > " + label.name);
                }
            });
        }
        logger.info("Deleting epics...");
        for (EpicSlim epic : epicsService.listEpics()) {
            executor.submit(() -> {
                logger.info("Deleting epic " + epic.id + " > " + epic.name);
                try {
                    epicsService.deleteEpic(epic.id);
                } catch (IOException e) {
                    logger.warning("Failed to delete epic " + epic.id + " > " + epic.name);
                }
            });
        }
        logger.info("Deleting milestones...");
        for (Milestone milestone : milestonesService.listMilestones()) {
            executor.submit(() -> {
                logger.info("Deleting milestone " + milestone.id + " > " + milestone.name);
                try {
                    milestonesService.deleteMilestone(milestone.id);
                } catch (IOException e) {
                    logger.warning("Failed to delete milestone " + milestone.id + " > " + milestone.name);
                }
            });
        }
        logger.info("Deleting stories...");
        executor.submit(() -> {
            try {
                SearchStoriesParams searchStoriesParams = new SearchStoriesParams();
                searchStoriesParams.archived = false;
                List<Long> storiesToArchiveIds = storiesService.searchStories(searchStoriesParams).stream().map(s -> s.id).collect(Collectors.toList());
                if (!storiesToArchiveIds.isEmpty()) {
                    logger.info(() -> "Archiving " + storiesToArchiveIds.size() + " stories");
                    UpdateMultipleStoriesParams updateMultipleStoriesParams = new UpdateMultipleStoriesParams();
                    updateMultipleStoriesParams.story_ids = storiesToArchiveIds;
                    updateMultipleStoriesParams.archived = true;
                    storiesService.updateMultipleStories(updateMultipleStoriesParams);
                }
                searchStoriesParams.archived = true;
                List<Long> storiesToDeleteIds = storiesService.searchStories(searchStoriesParams).stream().map(s -> s.id).collect(Collectors.toList());
                if (!storiesToDeleteIds.isEmpty()) {
                    logger.info(() -> "Deleting " + storiesToDeleteIds.size() + " stories");
                    storiesService.deleteMultipleStories(storiesToDeleteIds);
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error while deleting stories", e);
            }
        });
        logger.info("Waiting for completion of remaining tasks...");
        executor.shutdown();
        try {
            executor.awaitTermination(24, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            logger.warning("Interupted while waiting for migration to finish.");
            Thread.currentThread().interrupt();
        }
        logger.info("Done.");
    }
}
