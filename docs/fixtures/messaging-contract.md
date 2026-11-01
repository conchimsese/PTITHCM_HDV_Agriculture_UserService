# User messaging fixtures

Kafka values are UTF-8 JSON, keys are accountId UUID strings. No email, OTP, token, password or private contact fields belong in business payloads.

| Fixture | Topic | Consumer |
| --- | --- | --- |
| user-profile-updated.json | soa.user.profile.v1 | Trading, group trading-user-profile-v1 |
| account-locked.json / account-unlocked.json | soa.user.account.v1 | Notification, group notification-service-v1 |

Profile events have recipientIds=[] and data `{accountId,role,profileVersion,publicVisible,profile}`. An invisible profile has profile=null. A visible profile contains only displayName/avatarUrl/province/district/joinedAt; accountId is outside this nested snapshot. ADMIN has no public profile event.

Account events have recipientIds=[accountId], referenceType=ACCOUNT/referenceId=accountId and data `{accountId,reasonCode,accountVersion}`. reasonCode for the current Admin triggers is ADMIN_LOCK / ADMIN_UNLOCK. Reputation-specific causes/events are implemented in Phase6.

Relay preserves eventId/payload on retry. Consumers commit their own state/receipt before ACK, dedup by eventId and use version guards. Profile replica must only apply a strictly newer profileVersion; a delayed visible snapshot must not replace a newer tombstone. Bootstrap emits current snapshots through the same outbox/topic and can repeat a current version with a new eventId.

`src/test/java/vn/agriculture/soa/user/fixtures/ProfileReplica.java` demonstrates profile version handling. Kafka/PostgreSQL/Mailpit integration tests cover duplicates after broker ACK/SMTP acceptance followed by marker failure, broker outage/recovery, ordering and channel independence. These are User fixtures; confirmation with the actual Trading/Notification owners remains Phase7.
