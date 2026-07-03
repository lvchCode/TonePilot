package com.tonepilot.infrastructure.observability.repository.mapper;

import com.tonepilot.domain.observability.AuditEvent;
import com.tonepilot.domain.observability.LlmCallLog;

import java.util.List;

public interface ObservabilityLogMapper {

    int insertLlmCall(LlmCallLog item);

    int insertAuditEvent(AuditEvent item);

    List<LlmCallLog> listLatestLlmCalls(ObservabilityLimitQuery query);

    List<AuditEvent> listLatestAuditEvents(ObservabilityLimitQuery query);
}
