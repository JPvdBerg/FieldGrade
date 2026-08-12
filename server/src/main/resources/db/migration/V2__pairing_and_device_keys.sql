-- Pairing: how a tablet in a workshop acquires its credentials, once.
--
-- The owner adds a machine on the web and gets a short code. They type it into
-- the tablet, which exchanges it for a licence token and a device key and then
-- never needs the network again.

CREATE TABLE pairing_codes (
    -- Short and typed by hand, so it is the primary key directly. The alphabet
    -- excludes characters people confuse (0/O, 1/I/L) — see PairingCodes.kt.
    code         TEXT PRIMARY KEY,
    machine_id   TEXT        NOT NULL REFERENCES machines (id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Deliberately short-lived. A code is a bearer credential for a machine.
    expires_at   TIMESTAMPTZ NOT NULL,
    -- Single use. Set on redemption; a second attempt must fail even inside the
    -- expiry window, so a code read over the phone cannot be replayed.
    redeemed_at  TIMESTAMPTZ
);
CREATE INDEX pairing_codes_machine_idx ON pairing_codes (machine_id);
CREATE INDEX pairing_codes_expiry_idx ON pairing_codes (expires_at);

-- The tablet's upload credential.
--
-- Separate from the licence on purpose. A licence is a signed *claim* — public,
-- offline-verifiable, and therefore not a secret. This is the secret, so it can
-- be revoked the moment a tablet is stolen without touching whether the machine
-- is licensed to work.
--
-- Stored as a SHA-256, never the key itself: the same rule as sessions.
CREATE TABLE device_keys (
    key_hash      TEXT PRIMARY KEY,
    machine_id    TEXT        NOT NULL REFERENCES machines (id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at    TIMESTAMPTZ,
    -- Cheap fleet visibility: when did this tablet last manage to reach us.
    last_seen_at  TIMESTAMPTZ
);
CREATE INDEX device_keys_machine_idx ON device_keys (machine_id);
