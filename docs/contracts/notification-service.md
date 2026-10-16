# Notification / Realtime service (Sprint 6B.3)

The `notification` module (in-app notifications built from business events, and their live delivery) runs as its own service:
`com.setu.notification.NotificationServiceApplication`, `setu.service=notification`, port 8087, gateway route `/notifications/**`. It follows
the same pattern as Learning, Assessment and Attendance ([extracted-services.md](extracted-services.md)): the module plus the shared kernel,
tokens verified against Identity's published keys and the revocation list, authorization from a local copy fed by events.

## 1. What it consumes

| Consumer | Topics | Purpose |
|---|---|---|
| Notification listener (group `setu-notification`) | `grades, payments, placement_records, recommendations, interventions, student_profiles, enrollments, waitlist_entries` | turns an event into at most one notification, exactly once per event id (`notification.processed_events`) |
| Read-model listener (group `setu-notification-readmodel`) | Identity's role events, Academic's facts | keeps `notification.rm_*` (V85) current; policy as in [kafka-contracts.md](kafka-contracts.md) |

Both follow the retry and dead-letter policy of kafka-contracts.md section 5.

## 2. Who gets told, without calling Academic

The processor used to look up the student's account and a grade's student through Directory's and Enrollment's services. It now asks the
`AcademicDirectory` port (`studentOwner`, `enrollment`), answered in the service from `notification.rm_students` and `rm_enrollments`. No
request leaves the process, and the module no longer depends on `directory` or `enrollment` (`module-contracts.txt` lost both edges).

The consequence of an eventually consistent copy, handled rather than hidden:

* An event may mention a student or enrollment before the event that created it has been applied. That is `RecipientNotKnownException`,
  **not** poison: it is retried with backoff, is not recorded as processed (so it is not lost), and if it still cannot be resolved it lands in
  `{topic}.DLQ` and can be replayed once the copy has caught up. Poison is reserved for what can never succeed: malformed payload, a tenant that
  contradicts the student's.
* The tenant named by the event must still match the tenant of the person it resolves to.

## 3. Live delivery

When a notification is stored, a `LiveBroadcastRequest` for `/topic/notifications/<recipient user id>` is published in the same transaction and
delivered after commit (`RealtimeBroadcastService`), so a rolled-back notification is never announced. The payload is the notification the
recipient may already read over REST (`id, type, title, message, link, createdAt`).

* **Subscription is identity, not permission.** `NotificationSubscriptionAuthorizer` lets a user subscribe to their own topic and nobody
  else's: not an administrator, not a guardian. The destination is parsed, never trusted: a wildcard, an extra segment, a non-canonical id
  (`1-1-1-1-1`) or another user's id is refused (`NotificationSubscriptionAuthorizerTests`). The STOMP CONNECT frame is authenticated with the
  same bearer token and revocation list as HTTP (`RemoteStompTokenAuthenticator`).
* **A push is a convenience, never the record.** The notification is in the table and on `GET /notifications` whether or not anyone was
  listening; a client that reconnects reads the list.
* **The gateway does not proxy WebSockets** (it answers an upgrade with 501), so live clients connect to this service directly, as they do to
  Attendance for the live board.

## 4. Data

`notification.*` is the service's own schema: notifications, delivery attempts, preferences, processed events, and the read model `rm_*`
(V85, same tables as V81 gives the other services). `V86` drops the three foreign keys from notification tables to Academic's `institution.colleges`
and Identity's `identity.users`; tenant and user are plain ids. `infra/db/split/split-service.sh Notification <db>` copies the 16 tables
and verifies them ([data-ownership.md](data-ownership.md)).

## 5. Tests

`NotificationServiceWiringTests`: only the notification module and the kernel are in the context; recipients come from the copy; an admission
and a grade reach the right user and are announced on that user's topic only; a redelivery notifies and announces once; an unknown student is
retried, not poisoned, and leaves nothing behind; a tenant mismatch is poison; a user sees only their own notifications and needs a valid token;
someone else's notification cannot be marked read. `NotificationSubscriptionAuthorizerTests`, `NotificationEventProcessorTests` (unchanged
rules, now through the port), `GatewayRouteCoverageTests` (the route table sends `/notifications/**` here).

## 6. Not done

* The live push is not exercised over a real WebSocket on this machine (embedded servers do not start on the Windows development host); the
  authorizer and the publish-after-commit rule are tested directly, the same compromise as the Attendance live board.
* Only a student's own notification is pushed. Notifications to advisors (interventions) use the same topic by their user id.
