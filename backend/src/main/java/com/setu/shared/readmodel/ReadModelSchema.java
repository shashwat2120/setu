package com.setu.shared.readmodel;

import java.util.regex.Pattern;

/** Names of the read-model tables in one service's schema. The schema comes from configuration, so it is checked before it is put in SQL. */
final class ReadModelSchema {

    private static final Pattern SCHEMA = Pattern.compile("[a-z][a-z0-9_]{0,40}");

    private ReadModelSchema() {
    }

    static String table(String schema, String table) {
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("not a valid schema name: " + schema);
        }
        return schema + "." + table;
    }
}
