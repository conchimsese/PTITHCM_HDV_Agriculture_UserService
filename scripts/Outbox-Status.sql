-- Read-only operational checks; does not expose email payloads or change delivery state.
SELECT channel, count(*) AS pending, max(attempt_count) AS max_attempts,
       min(created_at) AS oldest_pending, min(available_at) AS next_available
FROM outbox WHERE published_at IS NULL GROUP BY channel;

SELECT event_id, aggregate_id, channel, event_type, topic, delivery_sequence,
       attempt_count, available_at, created_at
FROM outbox WHERE published_at IS NULL ORDER BY delivery_sequence LIMIT 100;
