package com.setu.shared.readmodel;

import com.setu.shared.enrollment.CourseSectionDirectory;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;
import java.util.UUID;

/** {@link CourseSectionDirectory} for an extracted service: which college a section belongs to, from its own copy ({@code <schema>.rm_sections}). */
public class ReadModelCourseSectionDirectory implements CourseSectionDirectory {

    private final JdbcClient jdbc;
    private final String sections;

    public ReadModelCourseSectionDirectory(JdbcClient jdbc, String schema) {
        this.jdbc = jdbc;
        this.sections = ReadModelSchema.table(schema, "rm_sections");
    }

    @Override
    public Optional<UUID> findTenantId(UUID sectionId) {
        return jdbc.sql("SELECT tenant_id FROM " + sections + " WHERE section_id = :id").param("id", sectionId).query(UUID.class).optional();
    }
}
