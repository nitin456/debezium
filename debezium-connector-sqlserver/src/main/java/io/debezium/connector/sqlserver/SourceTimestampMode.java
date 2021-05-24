/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.sqlserver;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.debezium.config.EnumeratedValue;
import io.debezium.util.Clock;

/**
 * Strategy for populating the source.ts_ms field in change events.
 */
public enum SourceTimestampMode implements EnumeratedValue {

    /**
     * This mode (default) will set the source timestamp field (ts_ms) of when the record was committed in the database.
     */
    COMMIT("commit") {
        @Override
        protected Instant getTimestamp(SqlServerConnection connection, Clock clock, ResultSet resultSet) throws SQLException {
            return connection.normalize(resultSet.getTimestamp(resultSet.getMetaData().getColumnCount()));
        }

        /**
         * Returns the query for obtaining the LSN-to-TIMESTAMP query. On SQL Server
         * 2016 and newer, the query will normalize the value to UTC. This means that
         * the {@link SqlServerConnection#SERVER_TIMEZONE_PROP_NAME} is not necessary to be given. The
         * returned TIMESTAMP will be adjusted by the JDBC driver using this VM's TZ (as
         * required by the JDBC spec), and that same TZ will be applied when converting
         * the TIMESTAMP value into an {@code Instant}.
         */
        @Override
        protected String lsnTimestampSelectStatement(boolean supportsAtTimeZone) {
            String result = ", " + SqlServerConnection.LSN_TIMESTAMP_SELECT_STATEMENT;
            if (supportsAtTimeZone) {
                result += " " + SqlServerConnection.AT_TIME_ZONE_UTC;
            }
            return result;
        }
    },

    /**
     * This mode will set the source timestamp field (ts_ms) of when the record was processed by Debezium.
     *
     * @deprecated Use {@link #COMMIT} instead.
     */
    @Deprecated
    PROCESSING("processing") {
        @Override
        protected Instant getTimestamp(SqlServerConnection connection, Clock clock, ResultSet resultSet) {
            return clock.currentTime();
        }

        @Override
        protected String lsnTimestampSelectStatement(boolean supportsAtTimeZone) {
            return "";
        }
    };

    private final String value;

    SourceTimestampMode(String value) {
        this.value = value;
    }

    @Override
    public String getValue() {
        return value;
    }

    /**
     * Returns the timestamp to be put in the source metadata of the event depending on the mode.
     *
     * @param connection Server connection used to fetch the result set
     * @param clock System clock to source processing time from
     * @param resultSet  Result set representing the CDC event and its commit timestamp, if required by the mode
     */
    protected abstract Instant getTimestamp(SqlServerConnection connection, Clock clock, ResultSet resultSet) throws SQLException;

    /**
     * Returns the SQL fragment to be embedded into the {@code GET_ALL_CHANGES_FOR_TABLE} query depending on the mode.
     *
     * @param supportsAtTimeZone Whether the server supports the {@code AT TIME ZONE} clause
     */
    protected abstract String lsnTimestampSelectStatement(boolean supportsAtTimeZone);

    /**
     * Returns the names of the data columns returned by the {@code GET_ALL_CHANGES_FOR_TABLE} query.
     *
     * @param rsmd Result set metadata
     * @param columnDataOffset Offset of the first data column in the result set
     */
    protected List<String> getResultColumnNames(ResultSetMetaData rsmd, int columnDataOffset) throws SQLException {
        int columnCount = rsmd.getColumnCount() - (columnDataOffset - 1);
        if (equals(COMMIT)) {
            // the last column in the {@code COMMIT} is the commit timestamp
            columnCount -= 1;
        }
        final List<String> columns = new ArrayList<>(columnCount);
        for (int i = 0; i < columnCount; ++i) {
            columns.add(rsmd.getColumnName(columnDataOffset + i));
        }
        return columns;
    }

    public static SourceTimestampMode getDefaultMode() {
        return COMMIT;
    }

    static SourceTimestampMode fromMode(String mode) {
        return Arrays.stream(SourceTimestampMode.values())
                .filter(s -> s.name().equalsIgnoreCase(mode))
                .findFirst()
                .orElseGet(SourceTimestampMode::getDefaultMode);
    }
}
