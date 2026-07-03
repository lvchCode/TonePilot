package com.tonepilot.repository.shared;

import java.util.List;

public interface DomainSnapshotStore {

    int updateSnapshot(DomainSnapshotRecord record);

    int insertSnapshot(DomainSnapshotRecord record);

    String findPayload(DomainSnapshotKey key);

    List<String> listPayloads(String domainType);
}
