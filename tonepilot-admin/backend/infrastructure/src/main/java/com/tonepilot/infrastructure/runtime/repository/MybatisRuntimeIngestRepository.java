package com.tonepilot.infrastructure.runtime.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tonepilot.domain.runtime.RuntimeDeviceRecord;
import com.tonepilot.domain.runtime.RuntimeDeviceRegistrationRequest;
import com.tonepilot.domain.runtime.RuntimeDeviceRegistrationResponse;
import com.tonepilot.domain.runtime.RuntimeEventQuery;
import com.tonepilot.domain.runtime.RuntimeEventRecord;
import com.tonepilot.domain.runtime.RuntimeEventRequest;
import com.tonepilot.infrastructure.runtime.repository.mapper.RuntimeDeviceHeartbeatCommand;
import com.tonepilot.infrastructure.runtime.repository.mapper.RuntimeDeviceStorageRecord;
import com.tonepilot.infrastructure.runtime.repository.mapper.RuntimeIngestMapper;
import com.tonepilot.infrastructure.runtime.repository.mapper.RuntimeUserStorageRecord;
import com.tonepilot.infrastructure.shared.persistence.PersistenceProperties;
import com.tonepilot.repository.runtime.RuntimeIngestRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class MybatisRuntimeIngestRepository implements RuntimeIngestRepository {

    private final List<RuntimeEventRecord> localEvents = new ArrayList<>();
    private final List<RuntimeDeviceRecord> localDevices = new ArrayList<>();

    @Autowired
    private PersistenceProperties persistenceProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ObjectProvider<RuntimeIngestMapper> mapperProvider;

    public synchronized RuntimeDeviceRegistrationResponse registerDevice(RuntimeDeviceRegistrationRequest request) {
        String fingerprint = required(request.fingerprint(), "fingerprint");
        RuntimeIngestMapper mapper = mapperProvider.getIfAvailable();
        if (!persistenceProperties.isEnabled() || mapper == null) {
            String userId = "local-user-" + stableId(fingerprint);
            String deviceId = "local-device-" + stableId(fingerprint);
            Instant now = Instant.now();
            localDevices.removeIf(device -> device.fingerprint().equals(fingerprint));
            localDevices.add(0, new RuntimeDeviceRecord(
                    userId,
                    deviceId,
                    fingerprint,
                    defaultText(request.deviceName(), "TonePilot Local Runtime"),
                    defaultText(request.endpoint(), ""),
                    toJson(request.metadata()),
                    now,
                    now
            ));
            return new RuntimeDeviceRegistrationResponse(userId, deviceId, true);
        }

        RuntimeDeviceStorageRecord existing = mapper.findDeviceByFingerprint(fingerprint);
        if (existing != null) {
            updateDeviceHeartbeat(mapper, existing.id(), request);
            return new RuntimeDeviceRegistrationResponse(existing.userId(), existing.id(), false);
        }

        Instant now = Instant.now();
        String userId = "usr_" + UUID.randomUUID().toString().replace("-", "");
        String deviceId = "dev_" + UUID.randomUUID().toString().replace("-", "");
        mapper.insertUser(new RuntimeUserStorageRecord(
                userId,
                "Lightroom 用户",
                "local-runtime",
                now,
                now
        ));
        mapper.insertDevice(new RuntimeDeviceStorageRecord(
                deviceId,
                userId,
                fingerprint,
                defaultText(request.deviceName(), "TonePilot Local Runtime"),
                defaultText(request.endpoint(), ""),
                toJson(request.metadata()),
                now,
                now,
                now
        ));
        return new RuntimeDeviceRegistrationResponse(userId, deviceId, true);
    }

    public synchronized List<RuntimeDeviceRecord> listDevices() {
        RuntimeIngestMapper mapper = mapperProvider.getIfAvailable();
        if (persistenceProperties.isEnabled() && mapper != null) {
            return mapper.listDevices().stream().map(this::toDeviceRecord).toList();
        }
        return localDevices.stream().limit(100).toList();
    }

    public synchronized RuntimeEventRecord recordEvent(RuntimeEventRequest request) {
        String userId = required(request.userId(), "userId");
        String deviceId = required(request.deviceId(), "deviceId");
        String eventType = required(request.eventType(), "eventType");
        RuntimeEventRecord record = new RuntimeEventRecord(
                "evt_" + UUID.randomUUID().toString().replace("-", ""),
                userId,
                deviceId,
                eventType,
                defaultText(request.sessionId(), ""),
                toJson(request.payload()),
                Instant.now()
        );
        localEvents.add(record);

        RuntimeIngestMapper mapper = mapperProvider.getIfAvailable();
        if (persistenceProperties.isEnabled() && mapper != null) {
            mapper.insertEvent(record);
        }
        return record;
    }

    public synchronized List<RuntimeEventRecord> listEvents(String userId) {
        RuntimeIngestMapper mapper = mapperProvider.getIfAvailable();
        if (persistenceProperties.isEnabled() && mapper != null) {
            return mapper.listEventsByUser(userId);
        }
        return localEvents.stream()
                .filter(event -> event.userId().equals(userId))
                .toList();
    }

    public synchronized List<RuntimeEventRecord> listEvents(RuntimeEventQuery query) {
        String userId = required(query.userId(), "userId");
        RuntimeIngestMapper mapper = mapperProvider.getIfAvailable();
        if (persistenceProperties.isEnabled() && mapper != null) {
            return mapper.listEvents(query);
        }
        return localEvents.stream()
                .filter(event -> event.userId().equals(userId))
                .filter(event -> isBlank(query.sessionId()) || event.sessionId().equals(query.sessionId().trim()))
                .filter(event -> isBlank(query.eventType()) || event.eventType().equals(query.eventType().trim()))
                .filter(event -> isBlank(query.traceId()) || event.payloadJson().contains(query.traceId().trim()))
                .limit(query.normalizedLimit())
                .toList();
    }

    private void updateDeviceHeartbeat(
            RuntimeIngestMapper mapper,
            String deviceId,
            RuntimeDeviceRegistrationRequest request
    ) {
        Instant now = Instant.now();
        mapper.updateDeviceHeartbeat(new RuntimeDeviceHeartbeatCommand(
                deviceId,
                defaultText(request.deviceName(), "TonePilot Local Runtime"),
                defaultText(request.endpoint(), ""),
                toJson(request.metadata()),
                now,
                now
        ));
    }

    private RuntimeDeviceRecord toDeviceRecord(RuntimeDeviceStorageRecord row) {
        return new RuntimeDeviceRecord(
                row.userId(),
                row.id(),
                row.fingerprint(),
                row.deviceName(),
                row.endpoint(),
                row.metadataJson(),
                row.lastSeenAt(),
                row.createdAt()
        );
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("运行时事件 JSON 序列化失败：" + exception.getMessage(), exception);
        }
    }

    private String required(String value, String name) {
        String text = defaultText(value, "");
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return text;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String defaultText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private String stableId(String value) {
        return Integer.toHexString(value.hashCode());
    }
}
