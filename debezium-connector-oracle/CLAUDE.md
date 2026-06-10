# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

The Debezium Oracle connector module inside the Debezium monorepo (repo root is one level up). This checkout is a **fork based on the `v1.9.8.Final` tag (detached HEAD, not `main`)** carrying a custom commit that adds a `plsql_output` LogMiner strategy (see "Local customization" below). Do not assume APIs from newer Debezium versions exist here.

## Build & test commands

All Maven commands run from the **repo root** (`..`), selecting this module with `-pl`:

```bash
# Build LogMiner variant (default), skip tests
mvn clean install -pl debezium-connector-oracle -am -DskipITs -DskipTests

# Build with the XStream adapter (requires xstreams.jar from Oracle Instant Client
# installed into the local Maven repo first — see module README.md)
mvn clean install -pl debezium-connector-oracle -am -Poracle-xstream -Dinstantclient.dir=/path/to/instant-client

# Run a single unit test (*Test.java — no external services needed)
mvn test -pl debezium-connector-oracle -Dtest=SqlUtilsTest

# Run a single integration test (*IT.java — requires a running, CDC-configured Oracle docker container)
mvn install -pl debezium-connector-oracle -Dit.test=OracleConnectorIT

# Integration tests against non-CDB Oracle
mvn clean install -pl debezium-connector-oracle -am -Poracle-tests -Ddatabase.pdb.name=
```

Test naming convention: `*Test.java` = unit test (surefire), `*IT.java` = integration test (failsafe, needs Oracle DB).

### Code formatting

Formatting (Eclipse formatter + import sorting + checkstyle) is **applied automatically by the build**; CI fails on violations. If style is off, just run a build and commit the auto-fixed files. To validate without modifying:

```bash
mvn clean install -Dformat.formatter.goal=validate -Dformat.imports.goal=check
```

## Architecture

Package root: `src/main/java/io/debezium/connector/oracle/`

Two streaming adapters implement the `StreamingAdapter` abstraction, selected by the `database.connection.adapter` config:

- **LogMiner** (`logminer/`, default, no Oracle license needed) — `LogMinerStreamingChangeEventSource` runs the mining loop: registers redo/archive logs into a LogMiner session, queries `V$LOGMNR_CONTENTS` (SQL built by `LogMinerQueryBuilder`), and feeds rows to a `LogMinerEventProcessor`.
- **XStream** (`xstream/`) — only compiled under the `-Poracle-xstream` profile; requires GoldenGate license + `xstreams.jar`.

LogMiner event processing (`logminer/processor/`):

- `AbstractLogMinerEventProcessor` is the core: buffers events per transaction (START → DML/LOB → COMMIT/ROLLBACK), only emitting downstream on COMMIT via `TransactionCommitConsumer` → `LogMinerChangeRecordEmitter`.
- Two buffer implementations, selected by `log.mining.buffer.type`: `memory/` (JVM heap) and `infinispan/` (embedded or remote cache, for transactions larger than heap).
- Redo SQL is parsed back into column values by `logminer/parser/` (`LogMinerDmlParser`); DDL is parsed by the ANTLR-based parser in `antlr/` (grammar lives in the sibling `debezium-ddl-parser` module).

Other key classes: `OracleConnectorConfig` (all config options + enums like `LogMiningStrategy`), `Scn`/`CommitScn` (SCN value types used for offsets), `OracleOffsetContext` (offset bookkeeping), `OracleDatabaseSchema` + schema history for DDL evolution, `OracleValueConverters` (Oracle type → Kafka Connect type).

## Local customization: `plsql_output` strategy

The custom commit (`f38a08b1ad`) adds `LogMiningStrategy.PLSQL_OUTPUT` (`log.mining.strategy=plsql_output`): instead of fetching `V$LOGMNR_CONTENTS` through a JDBC ResultSet, it runs an anonymous PL/SQL block (built by `LogMinerQueryBuilder.buildPlSqlOutputBlock`) that prints rows line-by-line via `DBMS_OUTPUT` using a `@ROW|...`-delimited protocol, parsed back in `AbstractLogMinerEventProcessor`. It exists for environments where direct JDBC fetching from `V$LOGMNR_CONTENTS` is unreliable. When touching `LogMinerQueryBuilder` or `AbstractLogMinerEventProcessor`, keep the JDBC path and the PL/SQL path behaviorally equivalent (filter predicates, operation codes, multi-row CSF continuation handling).

`../oracle-uat-t-bank-cdc-doris.conf` at the repo root is a local connector config used for testing this fork; it is not part of upstream.
