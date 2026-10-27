# Showing Kafka in the review

Every command below was run against the live stack on 2026-10-25. Run them in PowerShell (Git Bash rewrites `/opt/...` paths; if you use it, set `MSYS_NO_PATHCONV=1`).

## What to say first (30 seconds)

* One Kafka broker (`setu-kafka-1`, `apache/kafka:3.7.0`, host port 9093). One partition per topic, replication factor 1: a demo setup, stated plainly.
* A service never publishes straight to Kafka. It writes an **outbox row in the same database transaction** as the business change; a publisher then sends the row to the topic `setu.<aggregate>` and marks it `PUBLISHED`. If Kafka is down nothing is lost, the rows wait as `NEW`. Delivery is **at-least-once**, so every consumer keeps a processed-events table and ignores a repeat.
* Each message carries versioned headers: `event_id`, `event_type`, `event_version`, `schema_version`, `producer`, `occurred_at`, `tenant_id`, `aggregate_id`, `correlation_id`, `traceparent`.
* A message a consumer cannot handle is retried, then moved to `<topic>.DLQ` with the failure reason in its headers.

## 1. The container and the topics

```powershell
docker ps --filter name=kafka
docker exec setu-kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --list
```

Live: 24 `setu.*` topics (messages older than 7 days are deleted, so topics seeded on 19 October, such as `setu.persons` and `setu.user_roles`, are now empty although their offsets remain) (`setu.grades`, `setu.attendance_records`, `setu.enrollments`, `setu.risk_scores`, `setu.payments`, ...) plus `setu.grades.DLQ`.

## 2. Outbox and messages per topic

```powershell
.\infra\scripts\kafka-demo.ps1 status
```

Live: outbox 322 rows, all `PUBLISHED`, none `NEW`; e.g. `setu.grades` 39, `setu.attendance_sessions` 66, `setu.sessions` 60. The Attendance and Assessment services have their own outbox in their own databases (16 and 14 rows, all published), so topic totals are higher than the core outbox alone.

## 3. Consumers and lag

```powershell
docker exec setu-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092 --describe --group setu-risk
docker exec setu-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092 --list
```

Live groups: `setu-risk`, `setu-notification`, `setu-notification-readmodel`, `setu-academic`, `setu-enrollment`, `setu-attendance`, `setu-assessment`, `setu-learning`. Total lag was 0 in every group (everything published has been consumed).

## 4. A live event, end to end (the best moment of the demo)

Window 1:

```powershell
.\infra\scripts\kafka-demo.ps1 follow
```

Window 2 (or the app): do something real, for example faculty opens/closes an attendance session or publishes a grade, a student scans the QR, or just log in and out as a demo user. Tested live: a logout produced one `SessionRevoked` message on `setu.sessions` with `producer:identity`, a `correlation_id` and a `traceparent`, and payload `reason: LOGOUT`.

Single message with headers:

```powershell
.\infra\scripts\kafka-demo.ps1 tail grades
```

Point at `event_type:GradePublished`, `producer:assessment`, `event_version:1`, and the same `correlation_id` that appears in the service logs and the trace. Grade payloads also carry `version` and `supersedesId` (grades are append-only, a correction is a new version).

Follow-the-chain talking point: **grade published (Assessment) → `setu.grades` → three independent consumer groups: `setu-risk` recalculates the risk score (written to `setu.risk_scores` and the intervention/recommendation topics, which `setu-notification` turns into bell notifications), `setu-enrollment` completes the enrollment on a final grade, and `setu-notification` notifies the student.**

## 5. Reliability: duplicates and the DLQ

Idempotency, per consumer:

```powershell
docker exec setu-postgres-1 psql -U setu -d setu_demo -c "select count(*) from risk.processed_events"
```

(also `enrollment.processed_events`, `notification.processed_events`, `institution.rm_processed_events`). A redelivered event id is skipped.

Dead letters:

```powershell
docker exec setu-kafka-1 /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic setu.grades.DLQ --from-beginning --max-messages 2 --timeout-ms 8000 --property print.headers=true
```

**Retention matters here.** Kafka keeps messages for 7 days (`retention.ms=604800000`, set per topic), then deletes them. The two test messages that were in `setu.grades.DLQ` (the text `this is not json`, dead-lettered by `setu-risk` and `setu-notification`, each with `kafka_dlt-exception-message: event payload is not valid JSON`) were created on 19 October and had expired by 26 October, so on the live stack the DLQ is empty at the moment: the UI shows "No messages found" and both its earliest and latest offsets are 2. To show the DLQ in a review, send one fresh bad message first (it is dead-lettered within seconds, with its reason in the headers), and say it is a test message, not a production failure. Use `cut -c1-600` to keep the long stack trace in the header readable.

To send the test message (Git Bash; it is a text line with a tab between the headers and the body, sent into the real `setu.grades` topic):

```bash
MSYS_NO_PATHCONV=1; ID=$(node -e "console.log(require('crypto').randomUUID())")
printf 'event_type:GradePublished,event_id:%s,tenant_id:00000000-0000-4000-8000-000000000000\tthis is not json\n' "$ID" | docker exec -i setu-kafka-1 /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:29092 --topic setu.grades --property parse.headers=true
```

Checked on 26 October: within about 7 seconds `setu.grades.DLQ` held the message, rejected by `setu-risk` (`JsonParseException`). Unlike the first test, `setu-notification` read it without dead-lettering it, so expect one message in the DLQ, not two.

## Be honest about

* One broker, one partition per topic, RF 1: no broker failover, and ordering is only guaranteed per topic because of the single partition.
* At-least-once, not exactly-once; correctness comes from the processed-events tables.
* Some topics have no consumer group yet and are published for future or other-service use (`setu.risk_scores`, `setu.sessions`, `setu.attendance_records`, `setu.faculty_attendance`, `setu.academic_periods`; checked against every group on 2026-10-25); the groups above are the ones that were live.
* The Docker Desktop host stops after a reboot or sleep; Kafka and the services return by themselves (restart policy), the first minute may show connection errors.

More detail: `docs/contracts/kafka-contracts.md`, `docs/kafka-event-catalog.md`, `docs/demo-script.md` step 9.
