-- Credits, perks & Stripe payments (realizes the V2__payment_stub.sql sketch)

CREATE TABLE wallets
(
    user_id    VARCHAR(128) PRIMARY KEY,
    balance    INT       NOT NULL DEFAULT 0 CHECK (balance >= 0),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_wallet_user FOREIGN KEY (user_id) REFERENCES users (user_id)
);

CREATE TABLE perks
(
    perk_code     VARCHAR(40) PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    description   VARCHAR(500) NOT NULL,
    price_credits INT          NOT NULL CHECK (price_credits > 0),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    max_per_game  INT          NOT NULL DEFAULT 1,
    -- Free-form parameters for future perks (e.g. wolf-avoid probability)
    params        JSONB
);

INSERT INTO perks (perk_code, name, description, price_credits)
VALUES ('NIGHT1_IMMUNITY',
        '首夜免死',
        '若狼人在第一夜袭击你且你不是狼人，袭击失败（与守卫/女巫救人无法区分）。若你被分配到狼人角色，该道具作废且不退款。',
        30);

CREATE TABLE perk_activations
(
    id         SERIAL PRIMARY KEY,
    room_id    INT          NOT NULL,
    -- NULL until the game starts; bound in GameService.startGame
    game_id    INT,
    user_id    VARCHAR(128) NOT NULL,
    perk_code  VARCHAR(40)  NOT NULL,
    status     VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'CONSUMED', 'VOID', 'REFUNDED')),
    price_paid INT          NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_pa_room FOREIGN KEY (room_id) REFERENCES rooms (room_id),
    CONSTRAINT fk_pa_game FOREIGN KEY (game_id) REFERENCES games (game_id),
    CONSTRAINT fk_pa_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT fk_pa_perk FOREIGN KEY (perk_code) REFERENCES perks (perk_code)
);

-- FCFS hard limit: at most one live activation of a perk per room. Postgres
-- backstop only — H2 tests rely on the pessimistic room lock in PerkService.
CREATE UNIQUE INDEX ux_one_active_perk_per_room ON perk_activations (room_id, perk_code)
    WHERE status = 'ACTIVE';
CREATE INDEX idx_pa_room ON perk_activations (room_id);

CREATE TABLE products
(
    id            SERIAL PRIMARY KEY,
    product_key   VARCHAR(40)  NOT NULL UNIQUE,
    name          VARCHAR(100) NOT NULL,
    credits       INT          NOT NULL,
    bonus_credits INT          NOT NULL DEFAULT 0,
    price_cents   INT          NOT NULL,
    currency      VARCHAR(3)   NOT NULL DEFAULT 'usd',
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order    INT          NOT NULL DEFAULT 0
);

INSERT INTO products (product_key, name, credits, bonus_credits, price_cents, sort_order)
VALUES ('starter', '入门包', 100, 0, 199, 1),
       ('value', '超值包', 300, 30, 499, 2),
       ('big', '豪华包', 700, 100, 999, 3);

CREATE TABLE payment_orders
(
    id                       SERIAL PRIMARY KEY,
    order_no                 VARCHAR(40)  NOT NULL UNIQUE,
    user_id                  VARCHAR(128) NOT NULL,
    product_id               INT          NOT NULL,
    -- credits + bonus_credits snapshot at order time
    credits                  INT          NOT NULL,
    amount_cents             INT          NOT NULL,
    currency                 VARCHAR(3)   NOT NULL,
    status                   VARCHAR(10)  NOT NULL DEFAULT 'CREATED'
        CHECK (status IN ('CREATED', 'COMPLETED', 'EXPIRED', 'FAILED')),
    stripe_session_id        VARCHAR(255) UNIQUE,
    stripe_payment_intent_id VARCHAR(255),
    created_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at             TIMESTAMP,
    CONSTRAINT fk_po_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT fk_po_product FOREIGN KEY (product_id) REFERENCES products (id)
);

-- Stripe webhook event dedup: insert-first, duplicate key means already handled
CREATE TABLE payment_events
(
    stripe_event_id VARCHAR(255) PRIMARY KEY,
    event_type      VARCHAR(100) NOT NULL,
    received_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- One settlement per game: insert-first idempotency guard for game rewards
CREATE TABLE game_settlements
(
    game_id    INT PRIMARY KEY,
    settled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_gs_game FOREIGN KEY (game_id) REFERENCES games (game_id)
);

CREATE TABLE credit_transactions
(
    id                 SERIAL PRIMARY KEY,
    user_id            VARCHAR(128) NOT NULL,
    type               VARCHAR(20)  NOT NULL
        CHECK (type IN ('PURCHASE', 'GAME_REWARD', 'PERK_SPEND', 'REFUND')),
    -- signed: positive = credit, negative = debit
    amount             INT          NOT NULL,
    balance_after      INT          NOT NULL,
    game_id            INT,
    perk_activation_id INT,
    payment_order_id   INT,
    note               VARCHAR(200),
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ct_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT fk_ct_game FOREIGN KEY (game_id) REFERENCES games (game_id),
    CONSTRAINT fk_ct_pa FOREIGN KEY (perk_activation_id) REFERENCES perk_activations (id),
    CONSTRAINT fk_ct_po FOREIGN KEY (payment_order_id) REFERENCES payment_orders (id)
);

-- Settlement idempotency backstop: one reward row per user per game
CREATE UNIQUE INDEX ux_ct_game_reward ON credit_transactions (game_id, user_id)
    WHERE type = 'GAME_REWARD';
CREATE INDEX idx_ct_user ON credit_transactions (user_id, created_at DESC);

-- For wolf survival-scaled rewards: day the player died (NULL = survived to the end)
ALTER TABLE game_players
    ADD COLUMN died_day INT;
