-- Internal read-only operations only; no raw producer payload or email address is printed.
SELECT action, count(*) AS decisions, sum(requested_delta) AS requested_total,
       sum(actual_delta) AS applied_total
FROM reputation_history GROUP BY action;

SELECT account_id, reputation_score, status, admin_lock, reputation_lock
FROM accounts WHERE reputation_lock ORDER BY account_id;

SELECT event_id, account_id, decision_id, processed_at
FROM reputation_event_receipts ORDER BY processed_at DESC, event_id DESC LIMIT 100;

SELECT account_id, decision_id, action, decision_version, original_decision_id,
       requested_delta, actual_delta, before_score, after_score, effective_at, created_at
FROM reputation_history ORDER BY created_at DESC, history_id DESC LIMIT 100;
