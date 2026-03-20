-- Werewolf game core schema (PostgreSQL)

CREATE TABLE users
(
    user_id      VARCHAR(128) PRIMARY KEY,
    nickname     VARCHAR(50) NOT NULL,
    avatar_url   VARCHAR(500),
    created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- updated by Hibernate @UpdateTimestamp; no ON UPDATE trigger needed
    last_seen_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE rooms
(
    room_id       SERIAL PRIMARY KEY,
    room_code     CHAR(4)      NOT NULL UNIQUE,
    host_user_id  VARCHAR(128) NOT NULL,
    status        VARCHAR(10)  NOT NULL DEFAULT 'WAITING'
        CHECK (status IN ('WAITING', 'IN_GAME', 'CLOSED')),
    total_players INT          NOT NULL,
    has_seer      BOOLEAN      NOT NULL DEFAULT FALSE,
    has_witch     BOOLEAN      NOT NULL DEFAULT FALSE,
    has_hunter    BOOLEAN      NOT NULL DEFAULT FALSE,
    has_guard     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at     TIMESTAMP,
    CONSTRAINT fk_room_host FOREIGN KEY (host_user_id) REFERENCES users (user_id)
);

CREATE TABLE room_players
(
    id         SERIAL PRIMARY KEY,
    room_id    INT          NOT NULL,
    user_id    VARCHAR(128) NOT NULL,
    seat_index INT,
    status     VARCHAR(10)  NOT NULL DEFAULT 'NOT_READY'
        CHECK (status IN ('NOT_READY', 'READY')),
    is_host    BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_rp_room FOREIGN KEY (room_id) REFERENCES rooms (room_id),
    CONSTRAINT fk_rp_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT uq_room_user UNIQUE (room_id, user_id),
    CONSTRAINT uq_room_seat UNIQUE (room_id, seat_index)
);

CREATE TABLE games
(
    game_id         SERIAL PRIMARY KEY,
    room_id         INT          NOT NULL,
    host_user_id    VARCHAR(128) NOT NULL,
    phase           VARCHAR(20)  NOT NULL DEFAULT 'ROLE_REVEAL'
        CHECK (phase IN ('ROLE_REVEAL', 'SHERIFF_ELECTION', 'DAY', 'VOTING', 'NIGHT', 'GAME_OVER')),
    day_number      INT          NOT NULL DEFAULT 1,
    sheriff_user_id VARCHAR(128),
    winner          VARCHAR(10) CHECK (winner IN ('WEREWOLF', 'VILLAGER')),
    started_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at        TIMESTAMP,
    CONSTRAINT fk_game_room FOREIGN KEY (room_id) REFERENCES rooms (room_id),
    CONSTRAINT fk_game_host FOREIGN KEY (host_user_id) REFERENCES users (user_id),
    CONSTRAINT fk_game_sheriff FOREIGN KEY (sheriff_user_id) REFERENCES users (user_id)
);

CREATE TABLE game_players
(
    id             SERIAL PRIMARY KEY,
    game_id        INT          NOT NULL,
    user_id        VARCHAR(128) NOT NULL,
    seat_index     INT          NOT NULL,
    role           VARCHAR(10)  NOT NULL
        CHECK (role IN ('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD')),
    is_alive       BOOLEAN      NOT NULL DEFAULT TRUE,
    is_sheriff     BOOLEAN      NOT NULL DEFAULT FALSE,
    confirmed_role BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_gp_game FOREIGN KEY (game_id) REFERENCES games (game_id),
    CONSTRAINT fk_gp_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT uq_game_user UNIQUE (game_id, user_id),
    CONSTRAINT uq_game_seat UNIQUE (game_id, seat_index)
);

CREATE TABLE night_phases
(
    id                          SERIAL PRIMARY KEY,
    game_id                     INT         NOT NULL,
    day_number                  INT         NOT NULL,
    sub_phase                   VARCHAR(20) NOT NULL DEFAULT 'WEREWOLF_PICK'
        CHECK (sub_phase IN ('WEREWOLF_PICK', 'SEER_PICK', 'SEER_RESULT', 'WITCH_ACT', 'GUARD_PICK', 'COMPLETE')),
    wolf_target_user_id         VARCHAR(128),
    seer_checked_user_id        VARCHAR(128),
    seer_result_is_werewolf     BOOLEAN,
    witch_antidote_used         BOOLEAN     NOT NULL DEFAULT FALSE,
    witch_poison_target_user_id VARCHAR(128),
    guard_target_user_id        VARCHAR(128),
    prev_guard_target_user_id   VARCHAR(128),
    CONSTRAINT fk_np_game FOREIGN KEY (game_id) REFERENCES games (game_id),
    CONSTRAINT uq_game_night UNIQUE (game_id, day_number)
);

CREATE TABLE sheriff_elections
(
    id                      SERIAL PRIMARY KEY,
    game_id                 INT         NOT NULL,
    sub_phase               VARCHAR(10) NOT NULL DEFAULT 'SIGNUP'
        CHECK (sub_phase IN ('SIGNUP', 'SPEECH', 'VOTING', 'RESULT')),
    speaking_order          TEXT,
    current_speaker_idx     INT         NOT NULL DEFAULT 0,
    elected_sheriff_user_id VARCHAR(128),
    started_at              TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at            TIMESTAMP,
    CONSTRAINT fk_se_game FOREIGN KEY (game_id) REFERENCES games (game_id),
    CONSTRAINT uq_se_game UNIQUE (game_id)
);

CREATE TABLE sheriff_candidates
(
    id          SERIAL PRIMARY KEY,
    election_id INT          NOT NULL,
    user_id     VARCHAR(128) NOT NULL,
    status      VARCHAR(10)  NOT NULL DEFAULT 'RUNNING'
        CHECK (status IN ('RUNNING', 'QUIT')),
    CONSTRAINT fk_sc_election FOREIGN KEY (election_id) REFERENCES sheriff_elections (id),
    CONSTRAINT uq_election_user UNIQUE (election_id, user_id)
);

CREATE TABLE votes
(
    id             SERIAL PRIMARY KEY,
    game_id        INT          NOT NULL,
    vote_context   VARCHAR(20)  NOT NULL
        CHECK (vote_context IN ('SHERIFF_ELECTION', 'ELIMINATION')),
    day_number     INT          NOT NULL,
    voter_user_id  VARCHAR(128) NOT NULL,
    target_user_id VARCHAR(128),
    voted_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_v_game FOREIGN KEY (game_id) REFERENCES games (game_id),
    CONSTRAINT uq_vote UNIQUE (game_id, vote_context, day_number, voter_user_id)
);

CREATE TABLE elimination_history
(
    id                  SERIAL PRIMARY KEY,
    game_id             INT       NOT NULL,
    day_number          INT       NOT NULL,
    eliminated_user_id  VARCHAR(128),
    eliminated_role     VARCHAR(10) CHECK (eliminated_role IN
                                           ('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD')),
    hunter_shot_user_id VARCHAR(128),
    hunter_shot_role    VARCHAR(10) CHECK (hunter_shot_role IN
                                           ('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD')),
    recorded_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_eh_game FOREIGN KEY (game_id) REFERENCES games (game_id),
    CONSTRAINT uq_game_day UNIQUE (game_id, day_number)
);

CREATE TABLE game_events
(
    id             SERIAL PRIMARY KEY,
    game_id        INT         NOT NULL,
    event_type     VARCHAR(50) NOT NULL,
    message        TEXT        NOT NULL,
    target_user_id VARCHAR(128),
    created_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ge_game FOREIGN KEY (game_id) REFERENCES games (game_id)
);

-- Separate CREATE INDEX statement (MySQL INDEX inside CREATE TABLE is not valid PostgreSQL)
CREATE INDEX idx_game_events ON game_events (game_id);
