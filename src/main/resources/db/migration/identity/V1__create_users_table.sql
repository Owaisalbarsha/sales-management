CREATE TABLE users (
                       id              BIGSERIAL PRIMARY KEY,
                       name            VARCHAR(255)        NOT NULL,
                       email           VARCHAR(255)        NOT NULL UNIQUE,
                       password_hash   VARCHAR(255)        NOT NULL,
                       role            VARCHAR(50)         NOT NULL,
                       status          VARCHAR(50)         NOT NULL DEFAULT 'ACTIVE',
                       created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
                       updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_role  ON users(role);