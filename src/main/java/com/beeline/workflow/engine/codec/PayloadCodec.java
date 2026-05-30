package com.beeline.workflow.engine.codec;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.Type;

/**
 * Single place that turns activity/sideEffect/version results into the JSONB payload strings
 * written to {@code wflow.events}, and back. The payload formats here are the on-disk contract:
 * keep them stable, history rows written by older code must keep deserializing.
 */
public final class PayloadCodec {

    private final ObjectMapper objectMapper;

    public PayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ── Activity ────────────────────────────────────────────────────────────

    public String encodeActivityResult(Object result, int attempt) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("attempt", attempt);
            node.set("result", objectMapper.valueToTree(result));
            if (result != null) {
                node.put("resultType", result.getClass().getName());
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize activity result", e);
        }
    }

    public Object decodeActivityResult(String payload, Type returnType) {
        if (payload == null) return null;
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode resultNode = node.get("result");
            if (resultNode == null || resultNode.isNull()) return null;
            Type effective = returnType;
            if (effective == null || effective == void.class || effective == Void.class) {
                JsonNode typeNode = node.get("resultType");
                if (typeNode != null && !typeNode.isNull()) {
                    try {
                        effective = Class.forName(typeNode.asString());
                    } catch (ClassNotFoundException cnf) {
                        throw new IllegalStateException(
                                "Recorded activity result type not on classpath: " + typeNode.asString(), cnf);
                    }
                } else {
                    effective = Object.class;
                }
            }
            JavaType jt = objectMapper.constructType(effective);
            return objectMapper.readValue(objectMapper.writeValueAsString(resultNode), jt);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize cached activity result", e);
        }
    }

    public String encodeActivityStartedMarker(int attempt) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("attempt", attempt);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize activity started marker", e);
        }
    }

    public String encodeActivityFailed(int attempt, String reason) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("attempt", attempt);
            node.put("reason", reason);
            node.put("terminal", true);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize activity failure", e);
        }
    }

    public String encodeActivityRetryScheduled(int attempt, java.time.Instant fireAt, String reason) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("attempt", attempt);
            node.put("fireAt", fireAt.toString());
            node.put("reason", reason);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize activity retry marker", e);
        }
    }

    // ── SideEffect ──────────────────────────────────────────────────────────

    public String encodeSideEffectResult(Object result) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.set("result", objectMapper.valueToTree(result));
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize sideEffect result", e);
        }
    }

    public <T> T decodeSideEffectResult(String payload, Class<T> type) {
        if (payload == null) return null;
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode resultNode = node.get("result");
            if (resultNode == null || resultNode.isNull()) return null;
            JavaType jt = objectMapper.constructType(type);
            return objectMapper.readValue(objectMapper.writeValueAsString(resultNode), jt);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize sideEffect result", e);
        }
    }

    // ── Version marker ──────────────────────────────────────────────────────

    public String encodeVersionMarker(String changeId, int version) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "changeId", changeId,
                    "version", version));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize VERSION_MARKER payload", e);
        }
    }

    // ── Workflow input/output (used by WorkflowTurnRunner / WorkflowClient) ─

    public String encodeWorkflowValue(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize workflow value", e);
        }
    }

    public Object decodeWorkflowValue(String json, Type type) {
        if (json == null) return null;
        try {
            JavaType jt = objectMapper.constructType(type);
            return objectMapper.readValue(json, jt);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize workflow value", e);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    public int extractInt(String payload, String field, int defaultValue) {
        if (payload == null) return defaultValue;
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode v = node.get(field);
            return v != null && v.isNumber() ? v.asInt() : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    public JsonNode parse(String payload) {
        if (payload == null) return null;
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            return null;
        }
    }

    /** Encode a free-text reason as {@code {"reason": "..."}} with proper JSON escaping. */
    public String encodeReason(String reason) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("reason", reason);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize reason payload", e);
        }
    }
}
