-- ============================================================================
-- V2__create_model_registry_schema.sql
-- Modivra Slice 2A: Multi-Provider Model Registry, Discovery & Dynamic Routing
-- ============================================================================

-- 0. Column Widening on Existing Tables for Canonical Model Identifiers (e.g. openrouter/anthropic/claude-3.5-sonnet)
ALTER TABLE conversation_nodes ALTER COLUMN model_slug TYPE VARCHAR(192);
ALTER TABLE conversations ALTER COLUMN default_model_slug TYPE VARCHAR(192);

-- 1. Model Providers Table
CREATE TABLE model_providers (
    id VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(128) NOT NULL,
    api_mode VARCHAR(64) NOT NULL DEFAULT 'openai_compatible',
    base_url VARCHAR(512) NOT NULL,
    api_key_env VARCHAR(128),
    supports_discovery BOOLEAN NOT NULL DEFAULT TRUE,
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    display_order INTEGER NOT NULL DEFAULT 100,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_model_providers_api_mode CHECK (
        api_mode IN ('openai_compatible', 'openai_responses', 'anthropic_messages')
    )
);

-- 2. Models Table
CREATE TABLE models (
    id VARCHAR(192) PRIMARY KEY, -- Canonical ID: provider_id/model_id (e.g. xai/grok-4.7, openai/gpt-4o)
    provider_id VARCHAR(64) NOT NULL REFERENCES model_providers(id) ON DELETE CASCADE,
    model_id VARCHAR(128) NOT NULL, -- Raw model string sent to upstream wire API (e.g. grok-4.7, gpt-4o)
    slug VARCHAR(192) NOT NULL,     -- Short identifier (e.g. grok-4.7, gpt-4o)
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    context_window INTEGER NOT NULL DEFAULT 131072,
    max_output_tokens INTEGER NOT NULL DEFAULT 16384,
    max_tokens INTEGER NOT NULL DEFAULT 131072,
    category VARCHAR(64) NOT NULL DEFAULT 'general',
    owned_by VARCHAR(64) NOT NULL DEFAULT 'system',
    model_version VARCHAR(64),
    fingerprint VARCHAR(128),
    input_modalities JSONB NOT NULL DEFAULT '["text"]'::jsonb,
    output_modalities JSONB NOT NULL DEFAULT '["text"]'::jsonb,
    aliases JSONB NOT NULL DEFAULT '[]'::jsonb,
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    capabilities JSONB NOT NULL DEFAULT '{}'::jsonb,
    product_features JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled_tools JSONB NOT NULL DEFAULT '[]'::jsonb,
    pricing JSONB NOT NULL DEFAULT '{}'::jsonb,
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    display_order INTEGER NOT NULL DEFAULT 100,
    version BIGINT NOT NULL DEFAULT 0,
    upstream_created_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_models_provider_model_id UNIQUE (provider_id, model_id),
    CONSTRAINT uq_models_provider_slug UNIQUE (provider_id, slug),
    CONSTRAINT chk_models_limits CHECK (context_window > 0 AND max_output_tokens > 0 AND max_tokens > 0)
);

-- Indexes for performance & invariants
CREATE INDEX idx_models_enabled_order ON models (is_enabled, display_order ASC, id ASC);
CREATE INDEX idx_models_provider_id ON models (provider_id, is_enabled);
CREATE INDEX idx_models_slug ON models (slug);
CREATE INDEX idx_models_aliases_gin ON models USING gin (aliases);
CREATE UNIQUE INDEX uq_models_single_default ON models (is_default) WHERE is_default = TRUE;

-- 3. Seed Baseline Model Providers (xai, openrouter, openai, ollama)
INSERT INTO model_providers (
    id, display_name, api_mode, base_url, api_key_env,
    supports_discovery, is_enabled, display_order, metadata
) VALUES
    (
        'xai',
        'xAI (Grok)',
        'openai_compatible',
        'https://api.x.ai/v1',
        'XAI_API_KEY',
        TRUE,
        TRUE,
        10,
        '{"website": "https://x.ai"}'::jsonb
    ),
    (
        'openrouter',
        'OpenRouter',
        'openai_compatible',
        'https://openrouter.ai/api/v1',
        'OPENROUTER_API_KEY',
        TRUE,
        TRUE,
        20,
        '{"website": "https://openrouter.ai"}'::jsonb
    ),
    (
        'openai',
        'OpenAI',
        'openai_compatible',
        'https://api.openai.com/v1',
        'OPENAI_API_KEY',
        TRUE,
        FALSE,
        30,
        '{"website": "https://openai.com"}'::jsonb
    ),
    (
        'ollama',
        'Ollama Local',
        'openai_compatible',
        'http://localhost:11434/v1',
        NULL,
        TRUE,
        FALSE,
        40,
        '{"local": true}'::jsonb
    )
ON CONFLICT (id) DO NOTHING;

-- 4. Seed Baseline Models
INSERT INTO models (
    id, provider_id, model_id, slug, title, description,
    context_window, max_output_tokens, max_tokens, category, owned_by,
    input_modalities, output_modalities, aliases, tags, capabilities, pricing,
    is_enabled, is_default, display_order
) VALUES
    -- 4.1 Default Grok 4.7 (Active System Default)
    (
        'xai/grok-4.7',
        'xai',
        'grok-4.7',
        'grok-4.7',
        'Grok 4.7',
        'xAI flagship intelligence model with reasoning capabilities',
        131072,
        16384,
        131072,
        'grok_4',
        'xai',
        '["text", "image"]'::jsonb,
        '["text"]'::jsonb,
        '["latest", "grok-4"]'::jsonb,
        '["flagship", "reasoning"]'::jsonb,
        '{"tools": true, "vision": true, "reasoning": true}'::jsonb,
        '{"prompt_text_token_price": 5000000, "completion_text_token_price": 15000000}'::jsonb,
        TRUE,
        TRUE,
        10
    ),

    -- 4.2 Grok 2 (Slice 1 Test Compatibility: wire model_id 'grok-2-latest' & aliases)
    (
        'xai/grok-2',
        'xai',
        'grok-2-latest',
        'grok-2',
        'Grok 2',
        'xAI standard production model',
        131072,
        8192,
        131072,
        'grok_2',
        'xai',
        '["text"]'::jsonb,
        '["text"]'::jsonb,
        '["grok-2-latest", "grok-2-1212"]'::jsonb,
        '["production"]'::jsonb,
        '{"tools": true, "vision": false, "reasoning": false}'::jsonb,
        '{"prompt_text_token_price": 2000000, "completion_text_token_price": 10000000}'::jsonb,
        TRUE,
        FALSE,
        20
    ),

    -- 4.3 Grok 4.6
    (
        'xai/grok-4.6',
        'xai',
        'grok-4.6',
        'grok-4.6',
        'Grok 4.6',
        'xAI advanced reasoning model',
        131072,
        16384,
        131072,
        'grok_4',
        'xai',
        '["text", "image"]'::jsonb,
        '["text"]'::jsonb,
        '[]'::jsonb,
        '["reasoning"]'::jsonb,
        '{"tools": true, "vision": true, "reasoning": true}'::jsonb,
        '{"prompt_text_token_price": 3000000, "completion_text_token_price": 15000000}'::jsonb,
        TRUE,
        FALSE,
        30
    ),

    -- 4.4 OpenRouter Claude 3.5 Sonnet
    (
        'openrouter/anthropic/claude-3.5-sonnet',
        'openrouter',
        'anthropic/claude-3.5-sonnet',
        'claude-3.5-sonnet',
        'Claude 3.5 Sonnet (OpenRouter)',
        'Anthropic flagship reasoning model via OpenRouter',
        200000,
        8192,
        200000,
        'claude',
        'anthropic',
        '["text", "image"]'::jsonb,
        '["text"]'::jsonb,
        '["sonnet", "claude-sonnet"]'::jsonb,
        '["flagship", "reasoning"]'::jsonb,
        '{"tools": true, "vision": true, "reasoning": true}'::jsonb,
        '{"prompt": "0.000003", "completion": "0.000015"}'::jsonb,
        TRUE,
        FALSE,
        40
    ),

    -- 4.5 OpenRouter Claude 3 Opus
    (
        'openrouter/anthropic/claude-3-opus',
        'openrouter',
        'anthropic/claude-3-opus',
        'claude-3-opus',
        'Claude 3 Opus (OpenRouter)',
        'Anthropic powerful model for highly complex tasks via OpenRouter',
        200000,
        4096,
        200000,
        'claude',
        'anthropic',
        '["text", "image"]'::jsonb,
        '["text"]'::jsonb,
        '["opus", "claude-opus"]'::jsonb,
        '["flagship"]'::jsonb,
        '{"tools": true, "vision": true}'::jsonb,
        '{"prompt": "0.000015", "completion": "0.000075"}'::jsonb,
        TRUE,
        FALSE,
        45
    ),

    -- 4.6 OpenAI GPT-4o
    (
        'openai/gpt-4o',
        'openai',
        'gpt-4o',
        'gpt-4o',
        'GPT-4o',
        'OpenAI flagship omni intelligence model',
        128000,
        16384,
        128000,
        'gpt_4o',
        'openai',
        '["text", "image"]'::jsonb,
        '["text"]'::jsonb,
        '["chatgpt-4o-latest"]'::jsonb,
        '["flagship", "omni"]'::jsonb,
        '{"tools": true, "vision": true, "reasoning": false}'::jsonb,
        '{"prompt": "0.0000025", "completion": "0.00001"}'::jsonb,
        TRUE,
        FALSE,
        50
    ),

    -- 4.7 Ollama DeepSeek R1 (7B)
    (
        'ollama/deepseek-r1:7b',
        'ollama',
        'deepseek-r1:7b',
        'deepseek-r1:7b',
        'DeepSeek R1 (7B)',
        'DeepSeek open reasoning model hosted locally on Ollama',
        131072,
        16384,
        131072,
        'deepseek',
        'deepseek',
        '["text"]'::jsonb,
        '["text"]'::jsonb,
        '["deepseek-r1", "r1"]'::jsonb,
        '["local", "reasoning"]'::jsonb,
        '{"tools": false, "vision": false, "reasoning": true}'::jsonb,
        '{}'::jsonb,
        TRUE,
        FALSE,
        60
    ),

    -- 4.8 Ollama Llama 3 (8B)
    (
        'ollama/llama3:latest',
        'ollama',
        'llama3:latest',
        'llama3',
        'Llama 3 (8B)',
        'Meta Llama 3 local model on Ollama',
        8192,
        8192,
        8192,
        'llama',
        'meta',
        '["text"]'::jsonb,
        '["text"]'::jsonb,
        '["llama3", "llama-3-8b"]'::jsonb,
        '["local", "general"]'::jsonb,
        '{"tools": false, "vision": false}'::jsonb,
        '{}'::jsonb,
        TRUE,
        FALSE,
        70
    )
ON CONFLICT (id) DO NOTHING;
