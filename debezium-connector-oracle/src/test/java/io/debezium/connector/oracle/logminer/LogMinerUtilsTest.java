/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.oracle.logminer;

import static org.fest.assertions.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

import io.debezium.connector.oracle.OracleConnectorConfig;
import io.debezium.connector.oracle.Scn;
import io.debezium.connector.oracle.junit.SkipTestDependingOnAdapterNameRule;
import io.debezium.connector.oracle.junit.SkipWhenAdapterNameIsNot;
import io.debezium.connector.oracle.junit.SkipWhenAdapterNameIsNot.AdapterName;

@SkipWhenAdapterNameIsNot(value = AdapterName.LOGMINER)
public class LogMinerUtilsTest {

    private static final Scn SCN = new Scn(BigInteger.ONE);
    private static final Scn OTHER_SCN = new Scn(BigInteger.TEN);

    @Rule
    public TestRule skipRule = new SkipTestDependingOnAdapterNameRule();

    @Test
    public void testStartLogMinerStatement() {
        String statement = SqlUtils.startLogMinerStatement(SCN, OTHER_SCN, OracleConnectorConfig.LogMiningStrategy.CATALOG_IN_REDO, false);
        assertThat(statement.contains("DBMS_LOGMNR.DICT_FROM_REDO_LOGS")).isTrue();
        assertThat(statement.contains("DBMS_LOGMNR.DDL_DICT_TRACKING")).isTrue();
        assertThat(statement.contains("DBMS_LOGMNR.DICT_FROM_ONLINE_CATALOG")).isFalse();
        assertThat(statement.contains("DBMS_LOGMNR.CONTINUOUS_MINE")).isFalse();
        statement = SqlUtils.startLogMinerStatement(SCN, OTHER_SCN, OracleConnectorConfig.LogMiningStrategy.ONLINE_CATALOG, false);
        assertThat(statement.contains("DBMS_LOGMNR.DICT_FROM_REDO_LOGS")).isFalse();
        assertThat(statement.contains("DBMS_LOGMNR.DDL_DICT_TRACKING")).isFalse();
        assertThat(statement.contains("DBMS_LOGMNR.DICT_FROM_ONLINE_CATALOG")).isTrue();
        assertThat(statement.contains("DBMS_LOGMNR.CONTINUOUS_MINE")).isFalse();
        statement = SqlUtils.startLogMinerStatement(SCN, OTHER_SCN, OracleConnectorConfig.LogMiningStrategy.CATALOG_IN_REDO, true);
        assertThat(statement.contains("DBMS_LOGMNR.DICT_FROM_REDO_LOGS")).isTrue();
        assertThat(statement.contains("DBMS_LOGMNR.DDL_DICT_TRACKING")).isTrue();
        assertThat(statement.contains("DBMS_LOGMNR.DICT_FROM_ONLINE_CATALOG")).isFalse();
        assertThat(statement.contains("DBMS_LOGMNR.CONTINUOUS_MINE")).isTrue();
        statement = SqlUtils.startLogMinerStatement(SCN, OTHER_SCN, OracleConnectorConfig.LogMiningStrategy.ONLINE_CATALOG, true);
        assertThat(statement.contains("DBMS_LOGMNR.DICT_FROM_REDO_LOGS")).isFalse();
        assertThat(statement.contains("DBMS_LOGMNR.DDL_DICT_TRACKING")).isFalse();
        assertThat(statement.contains("DBMS_LOGMNR.DICT_FROM_ONLINE_CATALOG")).isTrue();
        assertThat(statement.contains("DBMS_LOGMNR.CONTINUOUS_MINE")).isTrue();
    }

    // todo delete after replacement == -1 in the code
    @Test
    public void testConversion() {
        Map<String, String> map = new HashMap<>();
        map.put("one", "1001");
        map.put("two", "1002");
        map.put("three", "1007");
        map.put("four", "18446744073709551615");
        Map<String, Long> res = map.entrySet().stream()
                .filter(entry -> new BigDecimal(entry.getValue()).longValue() > 1003 || new BigDecimal(entry.getValue()).longValue() == -1).collect(Collectors
                        .toMap(Map.Entry::getKey, e -> new BigDecimal(e.getValue()).longValue() == -1 ? Long.MAX_VALUE : new BigInteger(e.getValue()).longValue()));

        assertThat(res).isNotEmpty();
    }

    @Test
    public void shouldDetectLogFilesCoveringMiningRange() {
        List<LogFile> logs = Arrays.asList(
                archivedLog("log1", 100, 200, 1, 1),
                archivedLog("log2", 200, 300, 2, 1));

        assertThat(LogMinerHelper.hasLogFilesCoveringScnRange(logs, Scn.valueOf(101), Scn.valueOf(250))).isTrue();
    }

    @Test
    public void shouldDetectMissingCoverageGapBeforeEndScn() {
        List<LogFile> logs = Arrays.asList(
                archivedLog("log1", 100, 150, 1, 1),
                archivedLog("log2", 200, 300, 2, 1));

        assertThat(LogMinerHelper.hasLogFilesCoveringScnRange(logs, Scn.valueOf(101), Scn.valueOf(250))).isFalse();
    }

    @Test
    public void shouldRequireAtLeastOneThreadToCoverMiningStartScn() {
        List<LogFile> logs = Collections.singletonList(archivedLog("log1", 200, 300, 1, 1));

        assertThat(LogMinerHelper.hasLogFilesCoveringScnRange(logs, Scn.valueOf(101), Scn.valueOf(250))).isFalse();
    }

    @Test
    public void shouldIgnoreEndCoverageWhenEndScnIsNull() {
        List<LogFile> logs = Collections.singletonList(archivedLog("log1", 100, 150, 1, 1));

        assertThat(LogMinerHelper.hasLogFilesCoveringScnRange(logs, Scn.valueOf(101), null)).isTrue();
    }

    @Test
    public void shouldValidateEachRacThreadThatCoversMiningStartScn() {
        List<LogFile> logs = Arrays.asList(
                archivedLog("thread1-log1", 100, 200, 1, 1),
                archivedLog("thread1-log2", 200, 300, 2, 1),
                archivedLog("thread2-log1", 100, 150, 1, 2),
                archivedLog("thread2-log2", 200, 300, 2, 2));

        assertThat(LogMinerHelper.hasLogFilesCoveringScnRange(logs, Scn.valueOf(101), Scn.valueOf(250))).isFalse();
    }

    @Test
    public void shouldAcceptRacThreadThatStartsAfterMiningStartScn() {
        List<LogFile> logs = Arrays.asList(
                archivedLog("thread1-log1", 100, 200, 1, 1),
                archivedLog("thread1-log2", 200, 300, 2, 1),
                archivedLog("thread2-log1", 180, 300, 1, 2));

        assertThat(LogMinerHelper.hasLogFilesCoveringScnRange(logs, Scn.valueOf(101), Scn.valueOf(250))).isTrue();
    }

    @Test
    public void shouldTreatCurrentOnlineRedoLogAsCoveringEndScn() {
        List<LogFile> logs = Collections.singletonList(
                new LogFile("redo-current", Scn.valueOf(100), Scn.valueOf(150), BigInteger.ONE, LogFile.Type.REDO, true, 1));

        assertThat(LogMinerHelper.hasLogFilesCoveringScnRange(logs, Scn.valueOf(101), Scn.valueOf(250))).isTrue();
    }

    private static LogFile archivedLog(String fileName, int firstScn, int nextScn, int sequence, int thread) {
        return new LogFile(fileName, Scn.valueOf(firstScn), Scn.valueOf(nextScn), BigInteger.valueOf(sequence), LogFile.Type.ARCHIVE, thread);
    }
}
