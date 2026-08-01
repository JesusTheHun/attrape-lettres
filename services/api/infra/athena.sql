-- ---------------------------------------------------------------------------
-- Reading the telemetry.
--
-- The tables themselves are created by `infra/template.yaml` — do not run a
-- CREATE TABLE here, or the next deploy will fight you for it. This file is the
-- questions, not the schema.
--
-- Run these in the workgroup the stack creates (`<stack>-telemetry`), which
-- pins the output location and caps a single query at one gigabyte scanned.
--
-- EVERY QUERY FILTERS ON dt. Athena bills for bytes scanned and partition
-- projection only helps if the query asks for a range; without the filter these
-- read every day ever written, which is both the slow way and the expensive
-- one. The habit is the whole cost control.
--
-- WHAT IS NOT IN HERE, and cannot be. There is no device id, no household id
-- and no session id anywhere in this data, on purpose — see the note in
-- `src/telemetry/s3.ts`. So there is no such thing as "users", "retention",
-- "this child's journey" or any per-person cohort. Two rows from one phone are
-- indistinguishable from two rows from two phones. Everything below counts
-- EVENTS, and reading them as people will be wrong by an unknown factor. That
-- is the trade the Kids Category asked for and it was worth making.
-- ---------------------------------------------------------------------------


-- What is happening at all, by day. The first query to run when something
-- feels wrong, and the one that shows a release landing.
SELECT dt,
       event,
       count(*) AS n
FROM events
WHERE dt >= date_format(current_date - interval '14' day, '%Y-%m-%d')
GROUP BY dt, event
ORDER BY dt DESC, n DESC;


-- Which exercises get started, and which get finished. A row where completions
-- sit far below starts is an exercise children are walking away from — the
-- closest thing this data has to a difficulty complaint, since the game has no
-- fail state to record.
SELECT props.exercise,
       count_if(event = 'exercise_started')  AS started,
       count_if(event = 'session_completed') AS completed,
       round(100.0 * count_if(event = 'session_completed')
                   / nullif(count_if(event = 'exercise_started'), 0), 1) AS pct
FROM events
WHERE dt >= date_format(current_date - interval '30' day, '%Y-%m-%d')
  AND props.exercise IS NOT NULL
GROUP BY props.exercise
ORDER BY started DESC;


-- Accuracy by exercise and level: how often a completed run was perfect.
-- Low `perfect` on an early level is a level that is too hard for where it sits
-- in the ladder; near-100% everywhere is a ladder that has stopped teaching.
SELECT props.exercise,
       props.level,
       count(*) AS runs,
       round(avg(1.0 * props.perfect / nullif(props.rounds, 0)), 3) AS mean_accuracy
FROM events
WHERE dt >= date_format(current_date - interval '30' day, '%Y-%m-%d')
  AND event = 'session_completed'
  AND props.rounds > 0
GROUP BY props.exercise, props.level
ORDER BY props.exercise, props.level;


-- The money funnel, by day. `paywall_shown` only ever fires behind the parental
-- gate, so these counts are adults, not children.
SELECT dt,
       count_if(event = 'trial_started')       AS trials,
       count_if(event = 'trial_expired')       AS expired,
       count_if(event = 'paywall_shown')       AS paywalls,
       count_if(event = 'purchase_completed')  AS purchases,
       count_if(event = 'purchase_failed')     AS failures,
       count_if(event = 'purchase_restored')   AS restores
FROM events
WHERE dt >= date_format(current_date - interval '60' day, '%Y-%m-%d')
GROUP BY dt
ORDER BY dt DESC;


-- A purchase-failure rate that climbs is a store problem, and nobody will
-- report it: `entitlementOf` fails open, so a family whose receipt check breaks
-- keeps playing and never contacts anyone.
SELECT dt,
       count_if(event = 'purchase_failed') AS failures,
       count_if(event IN ('purchase_completed', 'purchase_failed')) AS attempts,
       round(100.0 * count_if(event = 'purchase_failed')
                   / nullif(count_if(event IN ('purchase_completed', 'purchase_failed')), 0), 1) AS pct
FROM events
WHERE dt >= date_format(current_date - interval '30' day, '%Y-%m-%d')
GROUP BY dt
HAVING count_if(event IN ('purchase_completed', 'purchase_failed')) > 0
ORDER BY dt DESC;


-- What children spend stars on, and what they never buy. A never-bought item is
-- either too expensive or invisible in the shop, and both are fixable.
SELECT props.cost,
       count(*) AS purchases
FROM events
WHERE dt >= date_format(current_date - interval '90' day, '%Y-%m-%d')
  AND event = 'item_bought'
GROUP BY props.cost
ORDER BY props.cost;


-- How far mascots actually grow. `mascot_grown` at stage 9 is the top of the
-- ladder; if nothing reaches it, the economy is slower than a child's patience.
SELECT props.stage,
       count(*) AS reached
FROM events
WHERE dt >= date_format(current_date - interval '90' day, '%Y-%m-%d')
  AND event = 'mascot_grown'
GROUP BY props.stage
ORDER BY props.stage;


-- Errors, grouped. `message` and `stack` are the only free-form strings this
-- service accepts anywhere; the client never attaches app state, because the
-- roster holds children's first names and one careless context dump would ship
-- them here. Error reports are NOT consent-gated, deliberately, so this is the
-- one signal that still arrives from the majority of parents who decline
-- analytics.
SELECT origin,
       message,
       count(*)     AS n,
       max(dt)      AS last_seen,
       min(dt)      AS first_seen,
       array_agg(DISTINCT v) AS versions
FROM errors
WHERE dt >= date_format(current_date - interval '14' day, '%Y-%m-%d')
GROUP BY origin, message
ORDER BY n DESC
LIMIT 50;


-- One error's stacks, once the query above has named it.
SELECT dt, v, stack
FROM errors
WHERE dt >= date_format(current_date - interval '14' day, '%Y-%m-%d')
  AND message = 'REPLACE ME'
ORDER BY dt DESC
LIMIT 20;


-- Which app versions are still out there. Native binaries update through store
-- review, so an old version can persist for months, and a fix that only exists
-- in the newest build is not a fix for the people still on the old one.
SELECT v,
       count(*) AS events,
       max(dt)  AS last_seen
FROM events
WHERE dt >= date_format(current_date - interval '30' day, '%Y-%m-%d')
GROUP BY v
ORDER BY events DESC;
