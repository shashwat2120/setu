package com.setu.shared.readmodel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Reading a domain event's JSON payload strictly: anything missing or malformed is a {@link PoisonEventException}, never a guess. */
final class EventPayload {

    private final JsonNode node;
    private final ObjectMapper mapper;

    private EventPayload(JsonNode node, ObjectMapper mapper) {
        this.node = node;
        this.mapper = mapper;
    }

    static EventPayload parse(String json, ObjectMapper mapper) {
        try {
            JsonNode node = mapper.readTree(json);
            if (node == null || !node.isObject()) {
                throw new PoisonEventException("event payload is not a JSON object");
            }
            return new EventPayload(node, mapper);
        } catch (JsonProcessingException e) {
            throw new PoisonEventException("event payload is not valid JSON", e);
        }
    }

    String text(String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    String required(String field) {
        String v = text(field);
        if (v == null || v.isBlank()) {
            throw new PoisonEventException("event payload is missing " + field);
        }
        return v;
    }

    UUID uuid(String field) {
        return toUuid(required(field), field);
    }

    UUID optionalUuid(String field) {
        String v = text(field);
        return v == null ? null : toUuid(v, field);
    }

    Instant instant(String field) {
        Instant value = optionalInstant(field);
        if (value == null) {
            throw new PoisonEventException("event payload is missing " + field);
        }
        return value;
    }

    Instant optionalInstant(String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        try {
            return mapper.convertValue(v, Instant.class);
        } catch (IllegalArgumentException e) {
            throw new PoisonEventException(field + " is not a timestamp", e);
        }
    }

    LocalDate optionalDate(String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        try {
            return mapper.convertValue(v, LocalDate.class);
        } catch (IllegalArgumentException e) {
            throw new PoisonEventException(field + " is not a date", e);
        }
    }

    boolean bool(String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isBoolean()) {
            throw new PoisonEventException("event payload is missing " + field);
        }
        return v.asBoolean();
    }

    JsonNode array(String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isArray()) {
            throw new PoisonEventException("event payload is missing " + field);
        }
        return v;
    }

    static UUID toUuid(String value, String what) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new PoisonEventException(what + " is not a UUID: " + value, e);
        }
    }
}
