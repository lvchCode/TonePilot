package com.tonepilot.infrastructure.runtime.repository.mapper;

import com.tonepilot.domain.runtime.RuntimeEventQuery;
import com.tonepilot.domain.runtime.RuntimeEventRecord;

import java.util.List;

public interface RuntimeIngestMapper {

    RuntimeDeviceStorageRecord findDeviceByFingerprint(String fingerprint);

    int insertUser(RuntimeUserStorageRecord user);

    int insertDevice(RuntimeDeviceStorageRecord device);

    int updateDeviceHeartbeat(RuntimeDeviceHeartbeatCommand command);

    List<RuntimeDeviceStorageRecord> listDevices();

    int insertEvent(RuntimeEventRecord record);

    List<RuntimeEventRecord> listEventsByUser(String userId);

    List<RuntimeEventRecord> listEvents(RuntimeEventQuery query);
}
