package com.tonepilot.repository.workflow;

public interface WorkflowRunSnapshotStore {

    int updateSnapshot(WorkflowRunStorageRecord record);

    int insertSnapshot(WorkflowRunStorageRecord record);

    String findSnapshotJson(String runId);
}
