-- Seeds a "Demo" project (18 services) whose services each exercise a distinct TRACEDOWN
-- PLATFORM capability against the deterministic tracedown-testbin (no
-- external dependencies). Operates directly on the DB and picks the first
-- available org + workspace.
--
-- Run via scripts/seeds/seed-demo.sh — it substitutes the placeholders:
--   __API_URL__     defaults to the public https://testbin.tracedown.dev
--   __CHAOS_URL__   defaults to an unresolvable host (fast connection error);
--                   pass a local testbin's :20782 listener for a true TCP reset
-- Scripts don't hardcode them: they read $p.baseUrl / $p.chaosUrl project
-- variables (seeded below from the placeholders), so retargeting the whole
-- demo project is a two-variable edit.
--
-- Stateful testbin endpoints (/flap, /spike, ...) get a unique key per seed
-- run (gen_random_uuid()), so reseeding or shared testbin instances never
-- collide.
--
-- Capability map (one service each):
--   healthy             steady green baseline (/get)
--   flapping            up->down transitions -> notification per transition (/flap)
--   always-down         constant failure; silentOnRepeat suppression + DOWN-for-N (/status/500)
--   status-template     custom named notification template, fires every run (/status/503)
--   slow-timeout        per-call timeout -> timeout outcome (5s endpoint, 3s cap)
--   retry-recovery      timeout action=retry: slow first attempts, then recovers (/fail-then-succeed)
--   connection-error    TCP reset -> laceNotifications "error" trigger (chaos /rst)
--   multi-agent         probe_mode=simultaneous fan-out (/get)
--   singleton-run       queue_policy=enqueue_once concurrency control (/delay/8000)
--   maintenance-window  service_window RRULE/duration (probes skipped in window)
--   scoped-variables    org->ws->proj->svc variable resolution, echoed back (/anything)
--   variable-writeback  .store() writeback persisted by result-ingestor (/uuid)
--   baseline-spike      laceBaseline rolling stats + spike after N calm runs (/spike)
--   schema-strict       body schema($var) strict validation via service variable (/json/exact)
--   multi-call-journey  4-call chain: cookie jar + token store + bearer auth
--   timing-scopes       ttfb/transfer/totalDelayMs assertions on a dripped body (/drip)
--   flip-writeback      self-flapping via writeback (/flip) + recovery notifications
--   bearer-auth         credential login -> token store -> authenticated fetch (/login, /protected)

BEGIN;

DO $SEED$
DECLARE
  v_org_id       UUID;
  v_workspace_id UUID;
  v_project_id   UUID;
  v_svc_id       UUID;
  v_tmpl_id      UUID;
  v_key          TEXT;
  v_now          TIMESTAMP := now();
BEGIN

  SELECT o.id INTO v_org_id FROM organizations o WHERE o.deleted = false LIMIT 1;
  IF v_org_id IS NULL THEN
    RAISE EXCEPTION 'No organization found. Run bootstrap first.';
  END IF;

  SELECT w.id INTO v_workspace_id
  FROM workspaces w
  WHERE w.organization_id = v_org_id AND w.deleted = false
  LIMIT 1;
  IF v_workspace_id IS NULL THEN
    RAISE EXCEPTION 'No workspace found in org %', v_org_id;
  END IF;

  -- Demo project (idempotent)
  SELECT p.id INTO v_project_id
  FROM projects p
  WHERE p.workspace_id = v_workspace_id AND p.name = 'Demo' AND p.deleted = false;

  IF v_project_id IS NULL THEN
    v_project_id := gen_random_uuid();
    INSERT INTO projects (id, workspace_id, name, is_active, deleted, created_at)
    VALUES (v_project_id, v_workspace_id, 'Demo', true, false, v_now);
    RAISE NOTICE 'Created project Demo (%)', v_project_id;
  ELSE
    RAISE NOTICE 'Project Demo already exists (%)', v_project_id;
  END IF;

  -- ================================================================
  -- Shared fixtures
  -- ================================================================

  -- Named notification template (org-scoped) + project binding, used by
  -- the status-template service below.
  IF NOT EXISTS (SELECT 1 FROM notification_templates
                 WHERE organization_id = v_org_id AND name = 'request-status' AND deleted = false) THEN
    v_tmpl_id := gen_random_uuid();
    INSERT INTO notification_templates (id, organization_id, name, text, deleted, created_at)
    VALUES (v_tmpl_id, v_org_id, 'request-status',
      '${s.name} call to ${url} returned ${actual} (expected ${expected}); outcome ${status}',
      false, v_now);
    INSERT INTO project_notification_templates (id, notification_template_id, project_id)
    VALUES (gen_random_uuid(), v_tmpl_id, v_project_id);
    RAISE NOTICE 'Created notification template request-status and bound to Demo';
  END IF;

  -- Project-scoped variable consumed by scoped-variables ($p.region).
  IF NOT EXISTS (SELECT 1 FROM project_variables
                 WHERE project_id = v_project_id AND key = 'region' AND deleted = false) THEN
    INSERT INTO project_variables (id, project_id, key, value, secret, encrypted, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_project_id, 'region', 'eu-west-1', false, false, false, v_now, v_now);
  END IF;

  -- Target URLs every demo script reads ($p.baseUrl / $p.chaosUrl).
  IF NOT EXISTS (SELECT 1 FROM project_variables
                 WHERE project_id = v_project_id AND key = 'baseUrl' AND deleted = false) THEN
    INSERT INTO project_variables (id, project_id, key, value, secret, encrypted, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_project_id, 'baseUrl', '__API_URL__', false, false, false, v_now, v_now);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM project_variables
                 WHERE project_id = v_project_id AND key = 'chaosUrl' AND deleted = false) THEN
    INSERT INTO project_variables (id, project_id, key, value, secret, encrypted, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_project_id, 'chaosUrl', '__CHAOS_URL__', false, false, false, v_now, v_now);
  END IF;

  -- ================================================================
  -- Services
  -- ================================================================

  -- 1. healthy: the steady-green reference service.
  IF NOT EXISTS (SELECT 1 FROM services WHERE project_id = v_project_id AND name = 'healthy' AND deleted = false) THEN
    v_svc_id := gen_random_uuid();
    INSERT INTO services (id, project_id, name, label, script, schedule, is_active, deleted, created_at)
    VALUES (v_svc_id, v_project_id, 'healthy', 'Green',
      'get("$p.baseUrl/get")' || chr(10) ||
      '.expect(status: 200)',
      '*/5 * * * *', true, false, v_now);
    INSERT INTO service_variables (id, service_id, key, value, secret, encrypted, system_type, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_svc_id, 'trackBaseline', 'false', false, false, 'config', false, v_now, v_now);
    RAISE NOTICE 'Created service healthy';
  END IF;

  -- 2. flapping: /flap cycles 200,200,500 per request -> the service goes
  --    up->down->up across runs. Each transition fires a notification
  --    (silentOnRepeat only suppresses REPEATED identical failures).
  IF NOT EXISTS (SELECT 1 FROM services WHERE project_id = v_project_id AND name = 'flapping' AND deleted = false) THEN
    v_svc_id := gen_random_uuid();
    v_key := gen_random_uuid()::text;
    INSERT INTO services (id, project_id, name, label, script, schedule, is_active, deleted, created_at)
    VALUES (v_svc_id, v_project_id, 'flapping', 'Transitions',
      'get("$p.baseUrl/flap/' || v_key || '?codes=200,200,500")' || chr(10) ||
      '.expect(status: 200)',
      '*/5 * * * *', true, false, v_now);
    INSERT INTO service_variables (id, service_id, key, value, secret, encrypted, system_type, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_svc_id, 'trackBaseline', 'false', false, false, 'config', false, v_now, v_now);
    RAISE NOTICE 'Created service flapping (key %)', v_key;
  END IF;

  -- 3. always-down: constant 500. One notification on the transition, then
  --    silentOnRepeat suppression; the platform still tracks
  --    last_status_consecutive ("DOWN for N runs") and last_status_since.
  IF NOT EXISTS (SELECT 1 FROM services WHERE project_id = v_project_id AND name = 'always-down' AND deleted = false) THEN
    v_svc_id := gen_random_uuid();
    INSERT INTO services (id, project_id, name, label, script, schedule, is_active, deleted, created_at)
    VALUES (v_svc_id, v_project_id, 'always-down', 'Silenced',
      'get("$p.baseUrl/status/500")' || chr(10) ||
      '.expect(status: { value: 200, options: { silentOnRepeat: true } })',
      '*/5 * * * *', true, false, v_now);
    INSERT INTO service_variables (id, service_id, key, value, secret, encrypted, system_type, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_svc_id, 'trackBaseline', 'false', false, false, 'config', false, v_now, v_now);
    RAISE NOTICE 'Created service always-down';
  END IF;

  -- 4. status-template: failure notification routed through the named
  --    template "request-status"; silentOnRepeat off so it fires every run.
  IF NOT EXISTS (SELECT 1 FROM services WHERE project_id = v_project_id AND name = 'status-template' AND deleted = false) THEN
    v_svc_id := gen_random_uuid();
    INSERT INTO services (id, project_id, name, label, script, schedule, is_active, deleted, created_at)
    VALUES (v_svc_id, v_project_id, 'status-template', 'Template',
      'get("$p.baseUrl/status/503")' || chr(10) ||
      '.expect(status: { value: 200, options: { notification: template("request-status"), silentOnRepeat: false } })',
      '*/5 * * * *', true, false, v_now);
    INSERT INTO service_variables (id, service_id, key, value, secret, encrypted, system_type, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_svc_id, 'trackBaseline', 'false', false, false, 'config', false, v_now, v_now);
    RAISE NOTICE 'Created service status-template';
  END IF;

  -- 5. slow-timeout: endpoint sleeps 5s, call capped at 3s -> timeout outcome
  --    (distinct from a failed assertion; own notification trigger).
  IF NOT EXISTS (SELECT 1 FROM services WHERE project_id = v_project_id AND name = 'slow-timeout' AND deleted = false) THEN
    v_svc_id := gen_random_uuid();
    INSERT INTO services (id, project_id, name, label, script, schedule, is_active, deleted, created_at)
    VALUES (v_svc_id, v_project_id, 'slow-timeout', 'Timeout',
      'get("$p.baseUrl/delay/5000", {' || chr(10) ||
      '  timeout: { ms: 3000 }' || chr(10) ||
      '})' || chr(10) ||
      '.expect(status: 200)',
      '*/10 * * * *', true, false, v_now);
    INSERT INTO service_variables (id, service_id, key, value, secret, encrypted, system_type, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_svc_id, 'trackBaseline', 'false', false, false, 'config', false, v_now, v_now);
    RAISE NOTICE 'Created service slow-timeout (3s per-call timeout)';
  END IF;

  -- 6. retry-recovery: the first 2 hits respond after 4s (past the 2s cap),
  --    later ones instantly. With action=retry the first run recovers within
  --    itself after retries; run history shows the retry warnings.
  IF NOT EXISTS (SELECT 1 FROM services WHERE project_id = v_project_id AND name = 'retry-recovery' AND deleted = false) THEN
    v_svc_id := gen_random_uuid();
    v_key := gen_random_uuid()::text;
    INSERT INTO services (id, project_id, name, label, script, schedule, is_active, deleted, created_at)
    VALUES (v_svc_id, v_project_id, 'retry-recovery', 'Retry',
      'get("$p.baseUrl/fail-then-succeed/' || v_key || '?fails=2&slow_ms=4000", {' || chr(10) ||
      '  timeout: { ms: 2000, action: "retry", retries: 3 }' || chr(10) ||
      '})' || chr(10) ||
      '.expect(status: 200)',
      '*/10 * * * *', true, false, v_now);
    INSERT INTO service_variables (id, service_id, key, value, secret, encrypted, system_type, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_svc_id, 'trackBaseline', 'false', false, false, 'config', false, v_now, v_now);
    RAISE NOTICE 'Created service retry-recovery (key %)', v_key;
  END IF;

  -- 7. connection-error: the target can't be reached at all. A connection
  --    that never establishes yields no response -> the executor records the
  --    call outcome as "failure" (executor.py: http_result.error), driving the
  --    service into a failing state and firing the down notification.
  --    The short timeout bounds any slow/black-holed connect well under the
  --    scheduler's agent-dispatch window, so the probe always returns a result.
  --    (A local testbin's raw-TCP chaos listener produces a true RST; the public
  --    instance doesn't expose it, so $p.chaosUrl points at an unresolvable host.)
  IF NOT EXISTS (SELECT 1 FROM services WHERE project_id = v_project_id AND name = 'connection-error' AND deleted = false) THEN
    v_svc_id := gen_random_uuid();
    INSERT INTO services (id, project_id, name, label, script, schedule, is_active, deleted, created_at)
    VALUES (v_svc_id, v_project_id, 'connection-error', 'Reset',
      'get("$p.chaosUrl", {' || chr(10) ||
      '  timeout: { ms: 5000 }' || chr(10) ||
      '})' || chr(10) ||
      '.expect(status: 200)',
      '*/5 * * * *', true, false, v_now);
    INSERT INTO service_variables (id, service_id, key, value, secret, encrypted, system_type, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_svc_id, 'trackBaseline', 'false', false, false, 'config', false, v_now, v_now);
    RAISE NOTICE 'Created service connection-error';
  END IF;

  -- 8. multi-agent: probe_mode=simultaneous dispatches to every healthy agent
  --    in parallel each tick. With a single agent it behaves like consecutive.
  IF NOT EXISTS (SELECT 1 FROM services WHERE project_id = v_project_id AND name = 'multi-agent' AND deleted = false) THEN
    v_svc_id := gen_random_uuid();
    INSERT INTO services (id, project_id, name, label, script, schedule, probe_mode, is_active, deleted, created_at)
    VALUES (v_svc_id, v_project_id, 'multi-agent', 'Fan-out',
      'get("$p.baseUrl/get")' || chr(10) ||
      '.expect(status: 200)',
      '*/5 * * * *', 'simultaneous', true, false, v_now);
    INSERT INTO service_variables (id, service_id, key, value, secret, encrypted, system_type, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_svc_id, 'trackBaseline', 'false', false, false, 'config', false, v_now, v_now);
    RAISE NOTICE 'Created service multi-agent (probe_mode=simultaneous)';
  END IF;

  -- 9. singleton-run: queue_policy=enqueue_once. The 8s response keeps a run
  --    in flight into the next tick; the scheduler queues exactly one rerun
  --    (Redis SET NX lock per service) instead of dispatching concurrently.
  IF NOT EXISTS (SELECT 1 FROM services WHERE project_id = v_project_id AND name = 'singleton-run' AND deleted = false) THEN
    v_svc_id := gen_random_uuid();
    INSERT INTO services (id, project_id, name, label, script, schedule, queue_policy, is_active, deleted, created_at)
    VALUES (v_svc_id, v_project_id, 'singleton-run', 'Single-run',
      'get("$p.baseUrl/delay/8000")' || chr(10) ||
      '.expect(status: 200)',
      '*/5 * * * *', 'enqueue_once', true, false, v_now);
    INSERT INTO service_variables (id, service_id, key, value, secret, encrypted, system_type, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_svc_id, 'trackBaseline', 'false', false, false, 'config', false, v_now, v_now);
    RAISE NOTICE 'Created service singleton-run (queue_policy=enqueue_once)';
  END IF;

  -- 10. maintenance-window: RRULE/duration window — daily 02:00-03:00 UTC.
  --     While active the scheduler skips dispatch entirely.
  IF NOT EXISTS (SELECT 1 FROM services WHERE project_id = v_project_id AND name = 'maintenance-window' AND deleted = false) THEN
    v_svc_id := gen_random_uuid();
    INSERT INTO services (id, project_id, name, label, script, schedule, service_window, is_active, deleted, created_at)
    VALUES (v_svc_id, v_project_id, 'maintenance-window', 'Window',
      'get("$p.baseUrl/get")' || chr(10) ||
      '.expect(status: 200)',
      '*/5 * * * *', 'FREQ=DAILY;BYHOUR=2;BYMINUTE=0/60', true, false, v_now);
    INSERT INTO service_variables (id, service_id, key, value, secret, encrypted, system_type, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_svc_id, 'trackBaseline', 'false', false, false, 'config', false, v_now, v_now);
    RAISE NOTICE 'Created service maintenance-window (daily 02:00-03:00 UTC)';
  END IF;

  -- 11. scoped-variables: the scheduler resolves $p.region (project scope) and
  --     $s.tier (service scope) plus computed $s.name/$p.name/$w.name; the
  --     testbin echoes the headers back so resolution is verifiable in the
  --     run detail.
  IF NOT EXISTS (SELECT 1 FROM services WHERE project_id = v_project_id AND name = 'scoped-variables' AND deleted = false) THEN
    v_svc_id := gen_random_uuid();
    INSERT INTO services (id, project_id, name, label, script, schedule, is_active, deleted, created_at)
    VALUES (v_svc_id, v_project_id, 'scoped-variables', 'Variables',
      'get("$p.baseUrl/anything", {' || chr(10) ||
      '  headers: {' || chr(10) ||
      '    "X-Workspace": "$w.name",' || chr(10) ||
      '    "X-Project": "$p.name",' || chr(10) ||
      '    "X-Service": "$s.name",' || chr(10) ||
      '    "X-Region": "$p.region",' || chr(10) ||
      '    "X-Tier": "$s.tier"' || chr(10) ||
      '  }' || chr(10) ||
      '})' || chr(10) ||
      '.expect(status: 200)',
      '*/5 * * * *', true, false, v_now);
    INSERT INTO service_variables (id, service_id, key, value, secret, encrypted, system_type, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_svc_id, 'trackBaseline', 'false', false, false, 'config', false, v_now, v_now);
    INSERT INTO service_variables (id, service_id, key, value, secret, encrypted, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_svc_id, 'tier', 'gold', false, false, false, v_now, v_now);
    RAISE NOTICE 'Created service scoped-variables';
  END IF;

  -- 12. variable-writeback: /uuid returns a fresh value each run; .store()
  --     with a $-prefixed key persists it via the result-ingestor, visible
  --     updating in the Variables tab.
  IF NOT EXISTS (SELECT 1 FROM services WHERE project_id = v_project_id AND name = 'variable-writeback' AND deleted = false) THEN
    v_svc_id := gen_random_uuid();
    INSERT INTO services (id, project_id, name, label, script, schedule, is_active, deleted, created_at)
    VALUES (v_svc_id, v_project_id, 'variable-writeback', 'Writeback',
      'get("$p.baseUrl/uuid")' || chr(10) ||
      '.expect(status: 200)' || chr(10) ||
      '.store({ "$lastUuid": this.body.uuid })',
      '*/5 * * * *', true, false, v_now);
    INSERT INTO service_variables (id, service_id, key, value, secret, encrypted, system_type, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_svc_id, 'trackBaseline', 'false', false, false, 'config', false, v_now, v_now);
    RAISE NOTICE 'Created service variable-writeback';
  END IF;

  -- 13. baseline-spike: /spike is fast for the first 12 hits then jumps to
  --     1.5s. laceBaseline (trackBaseline=true) accumulates rolling stats via
  --     prev and emits a baseline_spike notification when the jump lands.
  IF NOT EXISTS (SELECT 1 FROM services WHERE project_id = v_project_id AND name = 'baseline-spike' AND deleted = false) THEN
    v_svc_id := gen_random_uuid();
    v_key := gen_random_uuid()::text;
    INSERT INTO services (id, project_id, name, label, script, schedule, is_active, deleted, created_at)
    VALUES (v_svc_id, v_project_id, 'baseline-spike', 'Baseline',
      'get("$p.baseUrl/spike/' || v_key || '?after=12&fast_ms=50&slow_ms=1500")' || chr(10) ||
      '.expect(status: 200)',
      '*/5 * * * *', true, false, v_now);
    INSERT INTO service_variables (id, service_id, key, value, secret, encrypted, system_type, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_svc_id, 'trackBaseline', 'true', false, false, 'config', false, v_now, v_now);
    RAISE NOTICE 'Created service baseline-spike (key %, spikes after 12 runs)', v_key;
  END IF;

  -- 14. schema-strict: body validated against a JSON schema held in a service
  --     variable; /json/exact matches it strictly (swap the URL to
  --     /json/extra or /json/wrong to watch strict validation fail).
  IF NOT EXISTS (SELECT 1 FROM services WHERE project_id = v_project_id AND name = 'schema-strict' AND deleted = false) THEN
    v_svc_id := gen_random_uuid();
    INSERT INTO services (id, project_id, name, label, script, schedule, is_active, deleted, created_at)
    VALUES (v_svc_id, v_project_id, 'schema-strict', 'Schema',
      'get("$p.baseUrl/json/exact")' || chr(10) ||
      '.expect(status: 200, body: { value: schema($s.responseSchema), mode: "strict" })',
      '*/5 * * * *', true, false, v_now);
    INSERT INTO service_variables (id, service_id, key, value, secret, encrypted, system_type, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_svc_id, 'trackBaseline', 'false', false, false, 'config', false, v_now, v_now);
    INSERT INTO service_variables (id, service_id, key, value, secret, encrypted, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_svc_id, 'responseSchema',
      '{"type":"object","properties":{"service":{"type":"object","properties":{"name":{"type":"string"},"healthy":{"type":"boolean"},"checks":{"type":"integer"}},"required":["name","healthy","checks"]},"tags":{"type":"array","items":{"type":"string"}}},"required":["service","tags"]}',
      false, false, false, v_now, v_now);
    RAISE NOTICE 'Created service schema-strict';
  END IF;

  -- 15. multi-call-journey: a 4-call chain — set a cookie, verify the jar
  --     carried it, fetch a token, use it as a bearer. Exercises cookie jars,
  --     .store() run-vars and header interpolation in one probe.
  IF NOT EXISTS (SELECT 1 FROM services WHERE project_id = v_project_id AND name = 'multi-call-journey' AND deleted = false) THEN
    v_svc_id := gen_random_uuid();
    INSERT INTO services (id, project_id, name, label, script, schedule, is_active, deleted, created_at)
    VALUES (v_svc_id, v_project_id, 'multi-call-journey', 'Chain',
      'get("$p.baseUrl/cookies/set?sid=demo")' || chr(10) ||
      '.expect(status: 200)' || chr(10) ||
      'get("$p.baseUrl/cookies")' || chr(10) ||
      '.expect(status: 200)' || chr(10) ||
      'get("$p.baseUrl/token")' || chr(10) ||
      '.expect(status: 200)' || chr(10) ||
      '.store({ "$$authToken": this.body.token })' || chr(10) ||
      'get("$p.baseUrl/protected", {' || chr(10) ||
      '  headers: { "Authorization": "Bearer $$authToken" }' || chr(10) ||
      '})' || chr(10) ||
      '.expect(status: 200)',
      '*/5 * * * *', true, false, v_now);
    INSERT INTO service_variables (id, service_id, key, value, secret, encrypted, system_type, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_svc_id, 'trackBaseline', 'false', false, false, 'config', false, v_now, v_now);
    RAISE NOTICE 'Created service multi-call-journey';
  END IF;

  -- 16. timing-scopes: the body is dripped over ~600ms after instant headers,
  --     so ttfb stays low while transfer dominates — asserted per phase.
  IF NOT EXISTS (SELECT 1 FROM services WHERE project_id = v_project_id AND name = 'timing-scopes' AND deleted = false) THEN
    v_svc_id := gen_random_uuid();
    INSERT INTO services (id, project_id, name, label, script, schedule, is_active, deleted, created_at)
    VALUES (v_svc_id, v_project_id, 'timing-scopes', 'Timings',
      'get("$p.baseUrl/drip?duration_ms=600&bytes=2048&chunks=6")' || chr(10) ||
      '.expect(status: 200, ttfb: { value: 500, op: "lt" }, transfer: { value: 100, op: "gt" }, totalDelayMs: { value: 5000, op: "lt" })',
      '*/5 * * * *', true, false, v_now);
    INSERT INTO service_variables (id, service_id, key, value, secret, encrypted, system_type, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_svc_id, 'trackBaseline', 'false', false, false, 'config', false, v_now, v_now);
    RAISE NOTICE 'Created service timing-scopes';
  END IF;

  -- 17. flip-writeback: self-flapping via its own written-back variable.
  --     Two calls: the first POSTs success=$s.n softly (.check) and stores
  --     back `next` (the negation) — a hard .expect would abort the .store
  --     and freeze the loop. The second call re-asserts the same value with
  --     a hard .expect so the run outcome genuinely alternates up/down.
  IF NOT EXISTS (SELECT 1 FROM services WHERE project_id = v_project_id AND name = 'flip-writeback' AND deleted = false) THEN
    v_svc_id := gen_random_uuid();
    INSERT INTO services (id, project_id, name, label, script, schedule, is_active, deleted, created_at)
    VALUES (v_svc_id, v_project_id, 'flip-writeback', 'Self-flap',
      'post("$p.baseUrl/flip", {' || chr(10) ||
      '  body: json({ success: $s.n })' || chr(10) ||
      '})' || chr(10) ||
      '.check(status: 200)' || chr(10) ||
      '.store({ "$n": this.body.next })' || chr(10) ||
      chr(10) ||
      'get("$p.baseUrl/flip?success=$s.n")' || chr(10) ||
      '.expect(status: 200)',
      '*/5 * * * *', true, false, v_now);
    INSERT INTO service_variables (id, service_id, key, value, secret, encrypted, system_type, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_svc_id, 'trackBaseline', 'false', false, false, 'config', false, v_now, v_now);
    -- Recovery notifications: laceEmitRecovery fires on failure -> success.
    INSERT INTO service_variables (id, service_id, key, value, secret, encrypted, system_type, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_svc_id, 'notifyRecovery', 'true', false, false, 'config', false, v_now, v_now);
    INSERT INTO service_variables (id, service_id, key, value, secret, encrypted, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_svc_id, 'n', 'true', false, false, false, v_now, v_now);
    RAISE NOTICE 'Created service flip-writeback (self-flapping via writeback)';
  END IF;

  -- 18. bearer-auth: the full bearer chain — credential login, token
  --     stored as a run variable, authenticated fetch. Set probePassword to
  --     'invalid' in the service variables to watch the chain fail at login.
  IF NOT EXISTS (SELECT 1 FROM services WHERE project_id = v_project_id AND name = 'bearer-auth' AND deleted = false) THEN
    v_svc_id := gen_random_uuid();
    INSERT INTO services (id, project_id, name, label, script, schedule, is_active, deleted, created_at)
    VALUES (v_svc_id, v_project_id, 'bearer-auth', 'Bearer',
      'post("$p.baseUrl/login", {' || chr(10) ||
      '  body: json({ username: "$s.probeUser", password: "$s.probePassword" })' || chr(10) ||
      '})' || chr(10) ||
      '.expect(status: 200)' || chr(10) ||
      '.store({ "$$token": this.body.token })' || chr(10) ||
      chr(10) ||
      'get("$p.baseUrl/protected", {' || chr(10) ||
      '  headers: { "Authorization": "Bearer $$token" }' || chr(10) ||
      '})' || chr(10) ||
      '.expect(status: 200)',
      '*/5 * * * *', true, false, v_now);
    INSERT INTO service_variables (id, service_id, key, value, secret, encrypted, system_type, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_svc_id, 'trackBaseline', 'false', false, false, 'config', false, v_now, v_now);
    INSERT INTO service_variables (id, service_id, key, value, secret, encrypted, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_svc_id, 'probeUser', 'demo', false, false, false, v_now, v_now);
    INSERT INTO service_variables (id, service_id, key, value, secret, encrypted, deleted, created_at, updated_at)
    VALUES (gen_random_uuid(), v_svc_id, 'probePassword', 'demo-pass', false, false, false, v_now, v_now);
    RAISE NOTICE 'Created service bearer-auth';
  END IF;

  RAISE NOTICE '=== Done. Demo project seeded with 18 platform-capability services in workspace % ===', v_workspace_id;

END $SEED$;

COMMIT;
