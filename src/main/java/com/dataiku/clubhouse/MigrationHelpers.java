package com.dataiku.clubhouse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.clubhouse4j.api.v3beta.CreateEpicParams;
import io.clubhouse4j.api.v3beta.Epic;
import io.clubhouse4j.api.v3beta.EpicSlim;
import io.clubhouse4j.api.v3beta.EpicsService;
import io.clubhouse4j.api.v3beta.Project;
import io.clubhouse4j.api.v3beta.ProjectsService;
import io.clubhouse4j.api.v3beta.Team;
import io.clubhouse4j.api.v3beta.TeamsService;
import io.clubhouse4j.api.v3beta.WorkflowState;

public class MigrationHelpers {

    public static Project getProject(ProjectsService projectsService, String projectName) {
        return projectsService.listProjects().stream()
                .filter(p -> projectName.equals(p.name))
                .findAny()
                .orElseThrow(() -> new IllegalArgumentException("Unknown project on Clubhouse: " + projectName));
    }

    public static EpicSlim getOrCreateEpic(EpicsService chEpicsService, List<EpicSlim> chEpicList, String epicName) {
        EpicSlim epic = getEpic(chEpicList, epicName);
        if (epic == null) {
            CreateEpicParams params = new CreateEpicParams();
            params.name = epicName;
            Epic createdEpic = chEpicsService.createEpic(params);
            epic = EpicSlim.fromEpic(createdEpic);
            chEpicList.add(epic);
        }
        return epic;
    }

    public static EpicSlim getEpic(List<EpicSlim> chEpicList, String epicName) {
        for (EpicSlim epic : chEpicList) {
            if (Objects.equals(epic.name, epicName)) {
                return epic;
            }
        }
        return null;
    }

    public static WorkflowState getStoryState(TeamsService teamsService, Project project, String stateName) {
        Team team = teamsService.getTeam(project.team_id);
        if (team == null) {
            throw new IllegalStateException("Unknown team: " + project.team_id);
        }
        List<WorkflowState> states = team.workflow.states;
        for (WorkflowState state : states) {
            if (state.name.equalsIgnoreCase(stateName)) {
                return state;
            }
        }
        throw new IllegalArgumentException("Cannot find the state '" + stateName + "'for project " + project.name);
    }

    public static List<WorkflowState> getWorkflowStates(TeamsService teamsService, Project project) {
        Team team = teamsService.getTeam(project.team_id);
        if (team == null) {
            throw new IllegalStateException("Unknown team: " + project.team_id);
        }
        return team.workflow.states;
    }

    public static Map<String, WorkflowState> getWorkflowStatesMap(TeamsService teamsService, Project project) {
        Map<String, WorkflowState> workflowStates = new HashMap<>();
        for (WorkflowState workflowState : getWorkflowStates(teamsService, project)) {
            workflowStates.put(workflowState.name, workflowState);
        }
        return workflowStates;
    }

}
