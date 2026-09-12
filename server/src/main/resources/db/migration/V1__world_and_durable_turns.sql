CREATE TABLE saves (id VARCHAR(36) PRIMARY KEY, revision BIGINT NOT NULL, document CLOB NOT NULL);
CREATE TABLE turns (
    id VARCHAR(36) PRIMARY KEY, save_id VARCHAR(36) NOT NULL REFERENCES saves(id),
    request_id VARCHAR(100) NOT NULL, command CLOB NOT NULL, status VARCHAR(20) NOT NULL,
    entry VARCHAR(10) NOT NULL, outcome CLOB, result CLOB, plans CLOB, error VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    UNIQUE(save_id, request_id)
);
CREATE INDEX pending_turns ON turns(status);
CREATE TABLE world_changes (turn_id VARCHAR(36) PRIMARY KEY REFERENCES turns(id), save_id VARCHAR(36) NOT NULL,
    old_revision BIGINT NOT NULL, new_revision BIGINT NOT NULL, before_state CLOB NOT NULL, after_state CLOB NOT NULL);
CREATE TABLE connection_tokens (id VARCHAR(36) PRIMARY KEY, save_id VARCHAR(36) NOT NULL REFERENCES saves(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE, revoked BOOLEAN NOT NULL DEFAULT FALSE);
