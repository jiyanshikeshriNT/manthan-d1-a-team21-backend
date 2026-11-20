-- ReturnRight: 20 manual-test scenarios so every staff role has something to act on.
--
-- Prerequisite: the base demo data must exist (run the app once with profile local,seed).
-- Run:   psql -U postgres -d returnright -f scripts/test-scenarios.sql
-- Safe to re-run (does nothing if RMA-900001 already exists). Undo with the cleanup block at the end.
--
-- Label codes are signed like the app does (RR-{rma}-{first 8 hex of HMAC-SHA256}). The secret below
-- is the local default of app.label.secret; change v_secret if you override APP_LABEL_SECRET.
--
-- Scenario map (RMA-9000NN):
--   01-04  PENDING_REVIEW      flagged customer       -> MANAGER  approve / reject review
--   05-09  IN_TRANSIT          label ready            -> WAREHOUSE receive (scan / type label)
--   10-12  RECEIVED            awaiting inspection    -> WAREHOUSE inspect (grade + decision)
--   13-15  AWAITING_FINANCE    refund AWAITING_APPROVAL, high value -> FINANCE approve / reject
--   16     REFUND_IN_PROGRESS  refund FAILED          -> FINANCE retry
--   17     REFUND_IN_PROGRESS  refund UNKNOWN         -> FINANCE retry / reconciler
--   18-19  REFUNDED            ledger posted          -> FINANCE ledger, MANAGER dashboard
--   20     REJECTED            closed by manager      -> MANAGER audit / dashboard

CREATE EXTENSION IF NOT EXISTS pgcrypto;

DO $$
DECLARE
    v_secret   CONSTANT text := 'dev-only-label-secret-change-me';
    s          record;
    v_line     record;
    v_ret      uuid;
    v_rma      text;
    v_item     bigint;
    v_pct      int;
    v_amount   bigint;
    v_refund   bigint;
    v_inspected boolean;
    v_closed   boolean;
    v_created  timestamptz;
BEGIN
    IF EXISTS (SELECT 1 FROM return_request WHERE rma_number = 'RMA-900001') THEN
        RAISE NOTICE 'Test scenarios already present - nothing done.';
        RETURN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM customer_order) THEN
        RAISE EXCEPTION 'No orders found. Start the app once with --spring.profiles.active=local,seed first.';
    END IF;

    FOR s IN
        SELECT * FROM (VALUES
         --  n, status,               flagged, method,             grade, refund_status,       hi_value
         ( 1, 'PENDING_REVIEW',      true,  'ORIGINAL_PAYMENT', NULL,  NULL,                false),
         ( 2, 'PENDING_REVIEW',      true,  'STORE_CREDIT',     NULL,  NULL,                false),
         ( 3, 'PENDING_REVIEW',      true,  'ORIGINAL_PAYMENT', NULL,  NULL,                false),
         ( 4, 'PENDING_REVIEW',      true,  'ORIGINAL_PAYMENT', NULL,  NULL,                false),
         ( 5, 'IN_TRANSIT',          false, 'ORIGINAL_PAYMENT', NULL,  NULL,                false),
         ( 6, 'IN_TRANSIT',          false, 'STORE_CREDIT',     NULL,  NULL,                false),
         ( 7, 'IN_TRANSIT',          false, 'ORIGINAL_PAYMENT', NULL,  NULL,                false),
         ( 8, 'IN_TRANSIT',          false, 'ORIGINAL_PAYMENT', NULL,  NULL,                false),
         ( 9, 'IN_TRANSIT',          false, 'STORE_CREDIT',     NULL,  NULL,                false),
         (10, 'RECEIVED',            false, 'ORIGINAL_PAYMENT', NULL,  NULL,                false),
         (11, 'RECEIVED',            false, 'ORIGINAL_PAYMENT', NULL,  NULL,                false),
         (12, 'RECEIVED',            false, 'STORE_CREDIT',     NULL,  NULL,                true ),
         (13, 'AWAITING_FINANCE',    false, 'ORIGINAL_PAYMENT', 'A',   'AWAITING_APPROVAL', true ),
         (14, 'AWAITING_FINANCE',    false, 'ORIGINAL_PAYMENT', 'C',   'AWAITING_APPROVAL', true ),
         (15, 'AWAITING_FINANCE',    false, 'STORE_CREDIT',     'B',   'AWAITING_APPROVAL', true ),
         (16, 'REFUND_IN_PROGRESS',  false, 'ORIGINAL_PAYMENT', 'A',   'FAILED',            false),
         (17, 'REFUND_IN_PROGRESS',  false, 'ORIGINAL_PAYMENT', 'B',   'UNKNOWN',           false),
         (18, 'REFUNDED',            false, 'ORIGINAL_PAYMENT', 'A',   'SUCCEEDED',         false),
         (19, 'REFUNDED',            false, 'STORE_CREDIT',     'C',   'SUCCEEDED',         false),
         (20, 'REJECTED',            true,  'ORIGINAL_PAYMENT', NULL,  NULL,                false)
        ) AS t(n, status, flagged, method, grade, refund_status, hi_value)
        ORDER BY n
    LOOP
        -- First free line of a delivered order, priced above/below the Rs 10,000 approval threshold.
        SELECT ol.id AS line_id, ol.unit_price_paise, o.id AS order_id, o.customer_id
          INTO v_line
          FROM order_line ol
          JOIN customer_order o ON o.id = ol.order_id
         WHERE o.delivered_at IS NOT NULL
           AND NOT EXISTS (SELECT 1 FROM return_request r WHERE r.order_id = o.id)
           AND (CASE WHEN s.hi_value THEN ol.unit_price_paise >= 1000000
                     ELSE ol.unit_price_paise BETWEEN 10000 AND 999999 END)
         ORDER BY o.order_number, ol.id
         LIMIT 1;
        IF v_line.line_id IS NULL THEN
            RAISE EXCEPTION 'Scenario % : no suitable seeded order line found', s.n;
        END IF;

        v_ret       := gen_random_uuid();
        v_rma       := 'RMA-' || (900000 + s.n);
        v_created   := now() - make_interval(hours => 72 - s.n * 2);
        v_inspected := s.status IN ('AWAITING_FINANCE', 'REFUND_IN_PROGRESS', 'REFUNDED');
        v_closed    := s.status IN ('REFUNDED', 'REJECTED');
        v_pct       := CASE s.grade WHEN 'C' THEN 50 ELSE 100 END;
        v_amount    := CASE WHEN v_inspected THEN v_line.unit_price_paise * v_pct / 100 END;

        INSERT INTO return_request (id, rma_number, order_id, customer_id, status, refund_method, flagged,
                                    label_code, total_refund_paise, created_at, updated_at, closed_at)
        VALUES (v_ret, v_rma, v_line.order_id, v_line.customer_id, s.status, s.method, s.flagged,
                'RR-' || v_rma || '-' || left(encode(hmac(v_rma, v_secret, 'sha256'), 'hex'), 8),
                v_amount, v_created, now() - make_interval(mins => s.n * 7),
                CASE WHEN v_closed THEN now() - make_interval(mins => s.n * 7) END);

        INSERT INTO return_item (return_id, order_line_id, requested_qty, reason_code, reason_text,
                                 condition_grade, decision, accepted_qty, refund_paise, inspection_note)
        VALUES (v_ret, v_line.line_id, 1,
                (ARRAY['DAMAGED','DEFECTIVE','WRONG_ITEM','WRONG_SIZE','NOT_AS_DESCRIBED','CHANGED_MIND','OTHER'])[1 + s.n % 7],
                'Test scenario ' || s.n,
                CASE WHEN v_inspected THEN s.grade END,
                CASE WHEN v_inspected THEN 'ACCEPTED' END,
                CASE WHEN v_inspected THEN 1 END,
                v_amount,
                CASE WHEN v_inspected THEN 'Grade ' || s.grade || ' on inspection' END)
        RETURNING id INTO v_item;

        IF s.flagged THEN
            INSERT INTO customer_flag (customer_id, return_id, return_count, flagged_at)
            VALUES (v_line.customer_id, v_ret, 3, v_created);
        END IF;

        -- Audit trail: creation, then the transition into the current state.
        INSERT INTO audit_log (entity_type, entity_id, action, from_state, to_state, actor, actor_role, details, created_at)
        VALUES ('RETURN', v_ret::text, 'CREATED', NULL, 'REQUESTED',
                'customer:' || v_line.customer_id, 'CUSTOMER', 'Test scenario ' || s.n, v_created);
        INSERT INTO audit_log (entity_type, entity_id, action, from_state, to_state, actor, actor_role, details, created_at)
        VALUES ('RETURN', v_ret::text, 'STATUS_CHANGED', 'REQUESTED', s.status,
                CASE WHEN s.status IN ('RECEIVED') THEN 'staff:warehouse'
                     WHEN s.status IN ('REJECTED') THEN 'staff:manager' ELSE 'system' END,
                CASE WHEN s.status IN ('RECEIVED') THEN 'WAREHOUSE'
                     WHEN s.status IN ('REJECTED') THEN 'MANAGER' ELSE 'SYSTEM' END,
                'Test scenario ' || s.n, now() - make_interval(mins => s.n * 7));

        -- Refund row (+ ledger and gateway record when it succeeded).
        IF s.refund_status IS NOT NULL THEN
            INSERT INTO refund (return_id, amount_paise, method, status, idempotency_key, gateway_txn_id, attempts,
                                requires_approval, approved_by, approved_at, last_error, next_attempt_at,
                                created_at, updated_at)
            VALUES (v_ret, v_amount, s.method, s.refund_status, 'RR-REFUND-' || v_ret,
                    CASE WHEN s.refund_status = 'SUCCEEDED' THEN 'TXN-' || v_rma END,
                    CASE s.refund_status WHEN 'FAILED' THEN 5 WHEN 'UNKNOWN' THEN 1
                                         WHEN 'SUCCEEDED' THEN 1 ELSE 0 END,
                    s.hi_value,
                    CASE WHEN s.refund_status IN ('FAILED', 'UNKNOWN') AND s.hi_value THEN 'finance' END,
                    CASE WHEN s.refund_status IN ('FAILED', 'UNKNOWN') AND s.hi_value THEN now() END,
                    CASE s.refund_status WHEN 'FAILED' THEN 'Gateway unavailable (simulated)'
                                         WHEN 'UNKNOWN' THEN 'Gateway timeout - outcome unknown' END,
                    CASE WHEN s.refund_status = 'UNKNOWN' THEN now() + interval '1 hour' END,
                    now() - make_interval(mins => s.n * 7), now() - make_interval(mins => s.n * 7))
            RETURNING id INTO v_refund;

            INSERT INTO audit_log (entity_type, entity_id, action, from_state, to_state, actor, actor_role, details, created_at)
            VALUES ('REFUND', v_refund::text, 'CREATED', NULL, s.refund_status, 'system', 'SYSTEM',
                    'Test scenario ' || s.n, now() - make_interval(mins => s.n * 7));

            IF s.refund_status = 'SUCCEEDED' THEN
                INSERT INTO ledger_entry (refund_id, customer_id, account, direction, amount_paise, created_at)
                VALUES (v_refund, v_line.customer_id, 'REFUND_EXPENSE', 'DEBIT', v_amount, now()),
                       (v_refund, v_line.customer_id,
                        CASE s.method WHEN 'STORE_CREDIT' THEN 'STORE_CREDIT_LIABILITY' ELSE 'GATEWAY_CLEARING' END,
                        'CREDIT', v_amount, now());
                IF s.method = 'ORIGINAL_PAYMENT' THEN
                    INSERT INTO gw_payment (idempotency_key, amount_paise, reference, status, txn_id, created_at)
                    VALUES ('RR-REFUND-' || v_ret, v_amount, v_rma, 'SUCCEEDED', 'TXN-' || v_rma, now());
                END IF;
            END IF;
        END IF;
    END LOOP;

    RAISE NOTICE 'Inserted 20 test scenarios (RMA-900001 .. RMA-900020).';
END $$;

-- ---------------------------------------------------------------------------------------------
-- What to test with: RMA, label, customer login (order number + email) and expected owner role.
-- ---------------------------------------------------------------------------------------------
SELECT r.rma_number, r.status,
       CASE r.status WHEN 'PENDING_REVIEW' THEN 'MANAGER'
                     WHEN 'IN_TRANSIT' THEN 'WAREHOUSE' WHEN 'RECEIVED' THEN 'WAREHOUSE'
                     WHEN 'AWAITING_FINANCE' THEN 'FINANCE' WHEN 'REFUND_IN_PROGRESS' THEN 'FINANCE'
                     ELSE 'view only' END AS act_as,
       r.label_code, f.status AS refund_status, f.amount_paise / 100.0 AS refund_rupees,
       o.order_number, c.email
  FROM return_request r
  JOIN customer_order o ON o.id = r.order_id
  JOIN customer c ON c.id = r.customer_id
  LEFT JOIN refund f ON f.return_id = r.id
 WHERE r.rma_number BETWEEN 'RMA-900001' AND 'RMA-900020'
 ORDER BY r.rma_number;

-- ---------------------------------------------------------------------------------------------
-- Cleanup (uncomment to remove only the test scenarios):
-- ---------------------------------------------------------------------------------------------
-- DELETE FROM gw_payment   WHERE idempotency_key IN (SELECT 'RR-REFUND-' || id FROM return_request WHERE rma_number LIKE 'RMA-9000%');
-- DELETE FROM ledger_entry WHERE refund_id IN (SELECT f.id FROM refund f JOIN return_request r ON r.id = f.return_id WHERE r.rma_number LIKE 'RMA-9000%');
-- DELETE FROM audit_log    WHERE entity_id IN (SELECT id::text FROM return_request WHERE rma_number LIKE 'RMA-9000%')
--                             OR entity_id IN (SELECT f.id::text FROM refund f JOIN return_request r ON r.id = f.return_id WHERE r.rma_number LIKE 'RMA-9000%');
-- DELETE FROM refund       WHERE return_id IN (SELECT id FROM return_request WHERE rma_number LIKE 'RMA-9000%');
-- DELETE FROM customer_flag WHERE return_id IN (SELECT id FROM return_request WHERE rma_number LIKE 'RMA-9000%');
-- DELETE FROM return_item  WHERE return_id IN (SELECT id FROM return_request WHERE rma_number LIKE 'RMA-9000%');
-- DELETE FROM return_request WHERE rma_number LIKE 'RMA-9000%';
