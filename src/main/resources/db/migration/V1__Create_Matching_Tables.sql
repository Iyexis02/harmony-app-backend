-- Flyway Migration V1.0
-- Creates matching algorithm tables
--
-- NOTE: This file is OPTIONAL and for future use when switching from ddl-auto to Flyway.
-- Currently, Hibernate auto-generates these tables via ddl-auto: update
--
-- To use Flyway:
-- 1. Add Flyway dependency to pom.xml
-- 2. Change spring.jpa.hibernate.ddl-auto from "update" to "validate"
-- 3. Run: mvn flyway:migrate

-- ============================================================
-- Table: canonical_genres
-- Purpose: Master list of normalized music genres
-- ============================================================
CREATE TABLE IF NOT EXISTS canonical_genres (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    parent_genre_id VARCHAR(36),
    spotify_aliases TEXT,
    description TEXT,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    display_order INTEGER,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_parent_genre FOREIGN KEY (parent_genre_id) REFERENCES canonical_genres(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_genre_name ON canonical_genres(name);
CREATE INDEX IF NOT EXISTS idx_genre_parent ON canonical_genres(parent_genre_id);

-- ============================================================
-- Table: user_genre_preferences
-- Purpose: Links users to genres with preference weights
-- ============================================================
CREATE TABLE IF NOT EXISTS user_genre_preferences (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    genre_id VARCHAR(36) NOT NULL,
    weight DOUBLE PRECISION NOT NULL,
    source VARCHAR(50) NOT NULL,
    confidence DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    rank INTEGER,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_genre_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_genre_genre FOREIGN KEY (genre_id) REFERENCES canonical_genres(id) ON DELETE CASCADE,
    CONSTRAINT uk_user_genre UNIQUE (user_id, genre_id)
);

CREATE INDEX IF NOT EXISTS idx_user_genre_user ON user_genre_preferences(user_id);
CREATE INDEX IF NOT EXISTS idx_user_genre_genre ON user_genre_preferences(genre_id);
CREATE INDEX IF NOT EXISTS idx_user_genre_weight ON user_genre_preferences(weight);

-- ============================================================
-- Table: user_match_scores
-- Purpose: Pre-computed match scores between users
-- ============================================================
CREATE TABLE IF NOT EXISTS user_match_scores (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    matched_user_id VARCHAR(36) NOT NULL,
    music_score DOUBLE PRECISION,
    personality_score DOUBLE PRECISION,
    lifestyle_score DOUBLE PRECISION,
    location_score DOUBLE PRECISION,
    overall_score DOUBLE PRECISION NOT NULL,
    match_explanation TEXT,
    computed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    algorithm_version VARCHAR(20),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_match_score_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_match_score_matched_user FOREIGN KEY (matched_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uk_user_match UNIQUE (user_id, matched_user_id)
);

CREATE INDEX IF NOT EXISTS idx_match_score_user ON user_match_scores(user_id);
CREATE INDEX IF NOT EXISTS idx_match_score_overall ON user_match_scores(user_id, overall_score);
CREATE INDEX IF NOT EXISTS idx_match_score_computed ON user_match_scores(computed_at);

-- ============================================================
-- Table: matches
-- Purpose: Mutual matches between users (both swiped right)
-- ============================================================
CREATE TABLE IF NOT EXISTS matches (
    id VARCHAR(36) PRIMARY KEY,
    user_a_id VARCHAR(36) NOT NULL,
    user_b_id VARCHAR(36) NOT NULL,
    matched_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    match_score DOUBLE PRECISION,
    status VARCHAR(50) NOT NULL DEFAULT 'active',
    conversation_started BOOLEAN NOT NULL DEFAULT FALSE,
    first_message_at TIMESTAMP,
    last_message_at TIMESTAMP,
    message_count INTEGER DEFAULT 0,
    match_source VARCHAR(50) DEFAULT 'mutual_swipe',
    unmatched_at TIMESTAMP,
    unmatched_by VARCHAR(10),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_match_user_a FOREIGN KEY (user_a_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_match_user_b FOREIGN KEY (user_b_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_different_users CHECK (user_a_id != user_b_id)
);

CREATE INDEX IF NOT EXISTS idx_match_user_a ON matches(user_a_id);
CREATE INDEX IF NOT EXISTS idx_match_user_b ON matches(user_b_id);
CREATE INDEX IF NOT EXISTS idx_match_status ON matches(status);
CREATE INDEX IF NOT EXISTS idx_match_created ON matches(matched_at);

-- ============================================================
-- Table: user_swipes
-- Purpose: Tracks all swipe actions for learning and filtering
-- ============================================================
CREATE TABLE IF NOT EXISTS user_swipes (
    id VARCHAR(36) PRIMARY KEY,
    swiper_user_id VARCHAR(36) NOT NULL,
    swiped_user_id VARCHAR(36) NOT NULL,
    action VARCHAR(20) NOT NULL,
    swiped_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    match_score_at_swipe DOUBLE PRECISION,
    dimension_scores TEXT,
    visible_attributes TEXT,
    resulted_in_match BOOLEAN NOT NULL DEFAULT FALSE,
    match_id VARCHAR(36),
    algorithm_version VARCHAR(20),
    platform VARCHAR(20),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_swipe_swiper FOREIGN KEY (swiper_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_swipe_swiped FOREIGN KEY (swiped_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_swipe_match FOREIGN KEY (match_id) REFERENCES matches(id) ON DELETE SET NULL,
    CONSTRAINT uk_swiper_swiped UNIQUE (swiper_user_id, swiped_user_id),
    CONSTRAINT chk_swipe_different_users CHECK (swiper_user_id != swiped_user_id),
    CONSTRAINT chk_swipe_action CHECK (action IN ('like', 'pass', 'super_like', 'block'))
);

CREATE INDEX IF NOT EXISTS idx_swipe_swiper ON user_swipes(swiper_user_id);
CREATE INDEX IF NOT EXISTS idx_swipe_swiped ON user_swipes(swiped_user_id);
CREATE INDEX IF NOT EXISTS idx_swipe_action ON user_swipes(action);
CREATE INDEX IF NOT EXISTS idx_swipe_timestamp ON user_swipes(swiped_at);

-- ============================================================
-- Comments for documentation
-- ============================================================
COMMENT ON TABLE canonical_genres IS 'Master list of normalized music genres from Spotify';
COMMENT ON TABLE user_genre_preferences IS 'User music genre preferences with weights (0-1)';
COMMENT ON TABLE user_match_scores IS 'Pre-computed compatibility scores between users';
COMMENT ON TABLE matches IS 'Mutual matches between users';
COMMENT ON TABLE user_swipes IS 'All swipe actions for learning and filtering';

COMMENT ON COLUMN user_genre_preferences.weight IS 'Preference strength from 0.0 to 1.0';
COMMENT ON COLUMN user_genre_preferences.source IS 'Values: spotify_derived, manual_selection, inferred, hybrid';
COMMENT ON COLUMN user_match_scores.overall_score IS 'Weighted combination of all dimension scores (0-100)';
COMMENT ON COLUMN matches.status IS 'Values: active, unmatched_by_a, unmatched_by_b, deleted, blocked';
COMMENT ON COLUMN user_swipes.action IS 'Values: like, pass, super_like, block';
