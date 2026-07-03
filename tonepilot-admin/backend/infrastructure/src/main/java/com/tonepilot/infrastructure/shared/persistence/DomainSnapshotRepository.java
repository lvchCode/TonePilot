package com.tonepilot.infrastructure.shared.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tonepilot.repository.shared.DomainSnapshotKey;
import com.tonepilot.repository.shared.DomainSnapshotRecord;
import com.tonepilot.repository.shared.DomainSnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class DomainSnapshotRepository {

    private static final Logger log = LoggerFactory.getLogger(DomainSnapshotRepository.class);

    private final PersistenceProperties properties;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<DomainSnapshotStore> storeProvider;

    @Autowired
    public DomainSnapshotRepository(
            PersistenceProperties properties,
            ObjectMapper objectMapper,
            ObjectProvider<DomainSnapshotStore> storeProvider
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.storeProvider = storeProvider;
    }

    public void save(String type, Object id, Object value) {
        DomainSnapshotStore store = storeProvider.getIfAvailable();
        if (!properties.isEnabled() || store == null || id == null || value == null) {
            return;
        }
        try {
            String domainId = String.valueOf(id);
            Instant now = Instant.now();
            DomainSnapshotRecord record = new DomainSnapshotRecord(
                    type,
                    domainId,
                    objectMapper.writeValueAsString(value),
                    now,
                    now
            );
            if (store.updateSnapshot(record) == 0) {
                store.insertSnapshot(record);
            }
        } catch (Exception exception) {
            log.debug("领域快照写入失败 type={} id={}：{}", type, id, exception.getMessage());
        }
    }

    public <T> Optional<T> find(String type, Object id, Class<T> targetType) {
        DomainSnapshotStore store = storeProvider.getIfAvailable();
        if (!properties.isEnabled() || store == null || id == null) {
            return Optional.empty();
        }
        try {
            String json = store.findPayload(new DomainSnapshotKey(type, String.valueOf(id)));
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(readPayload(json, targetType));
        } catch (Exception exception) {
            log.debug("领域快照读取失败 type={} id={}：{}", type, id, exception.getMessage());
            return Optional.empty();
        }
    }

    public <T> List<T> list(String type, Class<T> targetType) {
        DomainSnapshotStore store = storeProvider.getIfAvailable();
        if (!properties.isEnabled() || store == null) {
            return List.of();
        }
        try {
            return store.listPayloads(type).stream()
                    .map(json -> readPayload(json, targetType))
                    .toList();
        } catch (Exception exception) {
            log.debug("领域快照列表读取失败 type={}：{}", type, exception.getMessage());
            return List.of();
        }
    }

    private <T> T readPayload(String json, Class<T> targetType) {
        try {
            return objectMapper.readValue(json, targetType);
        } catch (Exception exception) {
            throw new IllegalArgumentException("领域快照 JSON 解析失败：" + exception.getMessage(), exception);
        }
    }
}
