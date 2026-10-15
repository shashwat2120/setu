package com.setu.testsupport;

import com.setu.shared.events.DomainEvent;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every event the platform can publish, discovered from the real {@link DomainEvent} classes rather than from a list someone has to keep: its
 * type, payload version, producer, aggregate (topic) and payload fields. The one source for the frozen schema file, the generated schema
 * document, and the consumer-contract tests that feed each event's real shape to the consumers.
 */
public final class EventSchemas {

    /** @param fields payload field name to simple type name, in declaration order */
    public record Schema(String eventType, int version, String producer, String aggregateType, Class<? extends DomainEvent> eventClass,
                         Map<String, String> fields) {

        /** The frozen-file line: tab separated, fields as {@code name:Type;...}. */
        public String line() {
            StringBuilder f = new StringBuilder();
            fields.forEach((name, type) -> f.append(f.length() == 0 ? "" : ";").append(name).append(':').append(type));
            return eventType + "\t" + version + "\t" + producer + "\t" + aggregateType + "\t" + eventClass.getName() + "\t" + f;
        }
    }

    private EventSchemas() {
    }

    /** All events, sorted by type; an event type claimed by two classes is returned twice, for the caller to reject. */
    public static List<Schema> all() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isConcrete();
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(DomainEvent.class));
        List<Schema> schemas = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com/setu")) {
            Class<?> type;
            try {
                type = Class.forName(definition.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
            if (!type.isRecord() || type.getName().startsWith("com.setu.testsupport.")) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Class<? extends DomainEvent> eventClass = (Class<? extends DomainEvent>) type;
            schemas.addAll(describe(eventClass));
        }
        schemas.sort(Comparator.comparing(Schema::eventType).thenComparing(s -> s.eventClass().getName()));
        return schemas;
    }

    /** One schema per event type the class carries: one, or several for the envelope classes that take the type as a field. */
    private static List<Schema> describe(Class<? extends DomainEvent> eventClass) {
        Map<String, String> fields = new LinkedHashMap<>();
        boolean typeIsAField = false;
        for (RecordComponent component : eventClass.getRecordComponents()) {
            fields.put(component.getName(), component.getGenericType().getTypeName().replaceAll("[a-z]+\\.", ""));
            typeIsAField |= component.getName().equals("eventType");
        }
        List<String> types = new ArrayList<>();
        if (typeIsAField) {
            for (java.lang.reflect.Field constant : eventClass.getDeclaredFields()) {
                int modifiers = constant.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(modifiers) && java.lang.reflect.Modifier.isPublic(modifiers) && constant.getType() == String.class) {
                    try {
                        types.add((String) constant.get(null));
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        } else {
            types.add(sample(eventClass, null).eventType());
        }
        List<Schema> result = new ArrayList<>();
        for (String type : types) {
            DomainEvent sample = sample(eventClass, type);
            result.add(new Schema(sample.eventType(), sample.eventVersion(), sample.producer(), sample.aggregateType(), eventClass, fields));
        }
        return result;
    }

    /** A sample of this schema's event, with the right type when the class carries several. */
    public static DomainEvent sample(Schema schema) {
        return sample(schema.eventClass(), schema.eventType());
    }

    /** An instance with plausible values for every field, valid enough to serialize and to feed a consumer. */
    public static DomainEvent sample(Class<? extends DomainEvent> eventClass) {
        return sample(eventClass, null);
    }

    private static DomainEvent sample(Class<? extends DomainEvent> eventClass, String eventType) {
        try {
            Constructor<?> constructor = eventClass.getDeclaredConstructors()[0];
            Object[] args = new Object[constructor.getParameterCount()];
            Class<?>[] types = constructor.getParameterTypes();
            java.lang.reflect.Type[] generic = constructor.getGenericParameterTypes();
            for (int i = 0; i < args.length; i++) {
                args[i] = eventType != null && eventClass.getRecordComponents()[i].getName().equals("eventType")
                    ? eventType : sampleValue(types[i], generic[i]);
            }
            return (DomainEvent) constructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot build a sample of " + eventClass.getName(), e);
        }
    }

    private static Object sampleValue(Class<?> type, java.lang.reflect.Type generic) {
        if (type == UUID.class) {
            return UUID.randomUUID();
        }
        if (type == Instant.class) {
            return Instant.now();
        }
        if (type == LocalDate.class) {
            return LocalDate.now();
        }
        if (type == String.class) {
            return "sample";
        }
        if (type == boolean.class || type == Boolean.class) {
            return true;
        }
        if (type == int.class || type == Integer.class) {
            return 1;
        }
        if (type == long.class || type == Long.class) {
            return 1L;
        }
        if (type == short.class) {
            return (short) 1;
        }
        if (type == List.class) {
            boolean ids = generic instanceof java.lang.reflect.ParameterizedType p && p.getActualTypeArguments()[0] == UUID.class;
            return ids ? List.of(UUID.randomUUID(), UUID.randomUUID()) : List.of("VIEW_ATTENDANCE", "VIEW_GRADES");
        }
        return null;
    }
}
