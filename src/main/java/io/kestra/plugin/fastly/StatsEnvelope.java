package io.kestra.plugin.fastly;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Standard Fastly stats response envelope shared by tasks and triggers.
 * The {@code data} field may be a List (per-service field/stats endpoints)
 * or a Map keyed by service id (all-services endpoints).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StatsEnvelope(String status, Map<String, Object> meta, Object data) {}
