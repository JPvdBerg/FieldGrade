-- FieldGrade control plane, initial schema.
--
-- Shape follows the commercial model: an organisation owns machines, and each
-- machine carries its own subscription. Billing is per active machine.
--
--   org ─┬─ users        (who can log in)
--        ├─ machines     (a tablet + ESP32 pair, serial-bound)
--        │   └─ subscription  (one per machine)
--        │   └─ licences      (issued tokens, newest wins)
--        └─ payment_methods   (provider tokens; never card data)

CREATE TABLE orgs (
    id          TEXT PRIMARY KEY,
    name        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id             TEXT PRIMARY KEY,
    org_id         TEXT        NOT NULL REFERENCES orgs (id) ON DELETE CASCADE,
    -- Stored lower-cased; the app normalises before writing. Citext would be
    -- nicer but needs an extension, and this is one call site.
    email          TEXT        NOT NULL UNIQUE,
    password_hash  TEXT        NOT NULL,
    role           TEXT        NOT NULL CHECK (role IN ('owner', 'operator')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX users_org_idx ON users (org_id);

CREATE TABLE machines (
    id          TEXT PRIMARY KEY,
    org_id      TEXT        NOT NULL REFERENCES orgs (id) ON DELETE CASCADE,
    -- The physical identifier stamped on the controller. Globally unique so a
    -- machine cannot be claimed by two organisations at once.
    serial      TEXT        NOT NULL UNIQUE,
    name        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX machines_org_idx ON machines (org_id);

CREATE TABLE subscriptions (
    id                  TEXT PRIMARY KEY,
    org_id              TEXT        NOT NULL REFERENCES orgs (id) ON DELETE CASCADE,
    -- One subscription per machine: that is the billing unit.
    machine_id          TEXT        NOT NULL UNIQUE REFERENCES machines (id) ON DELETE CASCADE,
    plan                TEXT        NOT NULL,
    status              TEXT        NOT NULL
                                    CHECK (status IN ('trialing', 'active', 'past_due', 'cancelled')),
    -- Paid through this instant. The licence token's expiry is derived from it,
    -- so this column is what actually decides whether a machine keeps working.
    current_period_end  TIMESTAMPTZ NOT NULL,
    -- Which gateway holds the mandate, and its reference there. Nullable so a
    -- machine can be trialing or manually credited before any card exists.
    provider            TEXT,
    provider_ref        TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX subscriptions_org_idx ON subscriptions (org_id);
CREATE INDEX subscriptions_period_end_idx ON subscriptions (current_period_end);

-- Every licence token ever issued, newest first. Kept rather than overwritten so
-- a support call ("my tablet says expired") can be answered from the record, and
-- so a mis-issue can be traced.
CREATE TABLE licences (
    id          TEXT PRIMARY KEY,
    machine_id  TEXT        NOT NULL REFERENCES machines (id) ON DELETE CASCADE,
    token       TEXT        NOT NULL,
    issued_at   TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    grace_days  INTEGER     NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX licences_machine_issued_idx ON licences (machine_id, issued_at DESC);

-- Sessions store a HASH of the bearer token, never the token itself: a dump of
-- this table must not let anyone log in as a customer.
CREATE TABLE sessions (
    token_hash  TEXT PRIMARY KEY,
    user_id     TEXT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX sessions_user_idx ON sessions (user_id);
CREATE INDEX sessions_expiry_idx ON sessions (expires_at);

-- A card mandate held at the gateway. We store the gateway's token and the last
-- four digits for display, and nothing else — no PAN, no CVV, no expiry date.
-- Card data never touches this server, which is what keeps us out of the heavy
-- PCI scope. See docs/BILLING.md.
CREATE TABLE payment_methods (
    id            TEXT PRIMARY KEY,
    org_id        TEXT        NOT NULL REFERENCES orgs (id) ON DELETE CASCADE,
    provider      TEXT        NOT NULL,
    provider_ref  TEXT        NOT NULL,
    brand         TEXT,
    last4         TEXT,
    is_default    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (provider, provider_ref)
);
CREATE INDEX payment_methods_org_idx ON payment_methods (org_id);

-- Gateways retry webhooks, sometimes for days, sometimes out of order. The
-- unique constraint on the provider's own event id is what makes replaying one
-- harmless: a duplicate delivery cannot extend a subscription twice.
CREATE TABLE webhook_events (
    id                 TEXT PRIMARY KEY,
    provider           TEXT        NOT NULL,
    provider_event_id  TEXT        NOT NULL,
    payload            TEXT        NOT NULL,
    received_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at       TIMESTAMPTZ,
    error              TEXT,
    UNIQUE (provider, provider_event_id)
);
CREATE INDEX webhook_events_unprocessed_idx ON webhook_events (received_at)
    WHERE processed_at IS NULL;
