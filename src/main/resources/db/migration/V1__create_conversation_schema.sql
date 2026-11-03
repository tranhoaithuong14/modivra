-- V1__create_conversation_schema.sql
-- Production-grade ChatGPT Web stateful conversation tree schema

CREATE TABLE conversations (
    id UUID PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    title TEXT NOT NULL DEFAULT 'New chat',
    current_node UUID,
    default_model_slug VARCHAR(128) NOT NULL DEFAULT 'grok-2-latest',
    is_archived BOOLEAN NOT NULL DEFAULT FALSE,
    is_visible BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE conversation_nodes (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    parent_id UUID REFERENCES conversation_nodes(id) ON DELETE CASCADE,
    role VARCHAR(32),
    content JSONB,
    model_slug VARCHAR(128),
    status VARCHAR(64) NOT NULL DEFAULT 'in_progress',
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Invariant: Root node (parent_id IS NULL) must have NO message (role and content NULL).
    -- Non-root nodes (parent_id IS NOT NULL) MUST have a role.
    CONSTRAINT chk_conversation_nodes_root_invariants CHECK (
        (parent_id IS NULL AND role IS NULL AND content IS NULL) OR
        (parent_id IS NOT NULL AND role IS NOT NULL)
    ),

    -- Allowed message author roles
    CONSTRAINT chk_conversation_nodes_role CHECK (
        role IS NULL OR role IN ('system', 'user', 'assistant', 'tool')
    ),

    -- Node generation statuses
    CONSTRAINT chk_conversation_nodes_status CHECK (
        status IN ('in_progress', 'finished_successfully', 'interrupted', 'failed')
    )
);

-- Circular FK from conversations.current_node to conversation_nodes.id
-- Deferrable allows inserting conversation + nodes in one transaction without ordering deadlocks
ALTER TABLE conversations
    ADD CONSTRAINT fk_conversations_current_node
    FOREIGN KEY (current_node)
    REFERENCES conversation_nodes(id)
    ON DELETE SET NULL
    DEFERRABLE INITIALLY DEFERRED;

-- 1. Invariant: Exactly ONE root node per conversation
CREATE UNIQUE INDEX uq_conversation_nodes_root
    ON conversation_nodes (conversation_id)
    WHERE parent_id IS NULL;

-- 2. Fast sidebar retrieval (paged by user, filter visible & active, sort updated_at)
CREATE INDEX idx_conversations_user_updated
    ON conversations (user_id, is_visible, is_archived, updated_at DESC);

-- 3. Fast tree assembly: fetch all nodes of a conversation in chronological order
CREATE INDEX idx_conversation_nodes_conv_id
    ON conversation_nodes (conversation_id, created_at ASC, id ASC);

-- 4. Fast child branch lookups & foreign key cascade validation
CREATE INDEX idx_conversation_nodes_parent_id
    ON conversation_nodes (parent_id, conversation_id);

-- 5. Foreign key lookup on current_node
CREATE INDEX idx_conversations_current_node
    ON conversations (current_node)
    WHERE current_node IS NOT NULL;
