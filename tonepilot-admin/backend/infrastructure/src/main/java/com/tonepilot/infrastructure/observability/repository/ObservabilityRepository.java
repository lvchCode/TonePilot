package com.tonepilot.infrastructure.observability.repository;

import com.tonepilot.domain.observability.AuditEvent;
import com.tonepilot.domain.observability.LlmCallLog;
import com.tonepilot.infrastructure.observability.config.ObservabilityProperties;
import com.tonepilot.infrastructure.observability.repository.mapper.ObservabilityLimitQuery;
import com.tonepilot.infrastructure.observability.repository.mapper.ObservabilityLogMapper;
import com.tonepilot.infrastructure.shared.persistence.PersistenceProperties;
import com.tonepilot.repository.observability.ObservabilityLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

@Component
public class ObservabilityRepository implements ObservabilityLogRepository {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityRepository.class);

    private final PersistenceProperties persistenceProperties;
    private final ObservabilityProperties observabilityProperties;
    private final ObjectProvider<ObservabilityLogMapper> mapperProvider;
    private final Deque<LlmCallLog> localLlmLogs = new ArrayDeque<>();
    private final Deque<AuditEvent> localAuditEvents = new ArrayDeque<>();

    @Autowired
    public ObservabilityRepository(
            PersistenceProperties persistenceProperties,
            ObservabilityProperties observabilityProperties,
            ObjectProvider<ObservabilityLogMapper> mapperProvider
    ) {
        this.persistenceProperties = persistenceProperties;
        this.observabilityProperties = observabilityProperties;
        this.mapperProvider = mapperProvider;
    }

    public synchronized void saveLlmCall(LlmCallLog item) {
        addBounded(localLlmLogs, item);
        ObservabilityLogMapper mapper = mapperProvider.getIfAvailable();
        if (!persistenceProperties.isEnabled() || mapper == null) {
            return;
        }
        try {
            mapper.insertLlmCall(item);
        } catch (Exception exception) {
            log.debug("LLM 调用日志写入数据库失败，已保留在本地缓存：{}", exception.getMessage());
        }
    }

    public synchronized void saveAuditEvent(AuditEvent item) {
        addBounded(localAuditEvents, item);
        ObservabilityLogMapper mapper = mapperProvider.getIfAvailable();
        if (!persistenceProperties.isEnabled() || mapper == null) {
            return;
        }
        try {
            mapper.insertAuditEvent(item);
        } catch (Exception exception) {
            log.debug("审计事件写入数据库失败，已保留在本地缓存：{}", exception.getMessage());
        }
    }

    public List<LlmCallLog> latestLlmCalls(int limit) {
        ObservabilityLogMapper mapper = mapperProvider.getIfAvailable();
        if (persistenceProperties.isEnabled() && mapper != null) {
            try {
                return mapper.listLatestLlmCalls(new ObservabilityLimitQuery(limit));
            } catch (Exception exception) {
                log.debug("LLM 调用日志读取数据库失败，改用本地缓存：{}", exception.getMessage());
            }
        }
        return localLlmLogs.stream()
                .sorted(Comparator.comparing(LlmCallLog::startedAt).reversed())
                .limit(limit)
                .toList();
    }

    public List<AuditEvent> latestAuditEvents(int limit) {
        ObservabilityLogMapper mapper = mapperProvider.getIfAvailable();
        if (persistenceProperties.isEnabled() && mapper != null) {
            try {
                return mapper.listLatestAuditEvents(new ObservabilityLimitQuery(limit));
            } catch (Exception exception) {
                log.debug("审计事件读取数据库失败，改用本地缓存：{}", exception.getMessage());
            }
        }
        return localAuditEvents.stream()
                .sorted(Comparator.comparing(AuditEvent::createdAt).reversed())
                .limit(limit)
                .toList();
    }

    private <T> void addBounded(Deque<T> values, T item) {
        values.addFirst(item);
        int maxSize = Math.max(10, observabilityProperties.getLocalBufferSize());
        while (values.size() > maxSize) {
            values.removeLast();
        }
    }
}
