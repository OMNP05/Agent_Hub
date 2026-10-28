-- 0001_init: core schema, integrity constraints and row-level security.
--
-- Design rules (CLAUDE.md, architecture.md 1.4 and 3.8):
--  * Every table has row-level security. The app role `authenticated` can only READ its own rows; all writes go
--    through the backend, which checks ownership in code as well. `anon` can touch nothing.
--  * Anything encrypted lives in a separate "secret" table with RLS on and NO policy, so not even the owner's
--    own session can read it. Only the backend's service connection can.
--  * Child rows repeat the owner's user_id and reference their parent with a COMPOSITE foreign key, so the
--    database itself refuses a row whose user_id differs from its parent's.
--  * Deleting a user in auth.users removes everything they own (account deletion, architecture 1.6).
--  * The catalog is locked at the database level: exactly three agents (GitHub, Gmail, Calendar).
-- Forward-only: there are no "down" migrations. Fix mistakes with a new migration.

create function public.set_updated_at() returns trigger
language plpgsql
set search_path = ''
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

-- ----------------------------------------------------------------------------------------------- llm profiles
create table public.llm_profiles (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users (id) on delete cascade,
    name text not null check (char_length(btrim(name)) between 1 and 60),
    provider text not null check (provider in ('openai', 'anthropic', 'openai_compatible')),
    base_url text check (base_url is null or (char_length(base_url) <= 500 and base_url like 'https://%')),
    model text not null check (char_length(btrim(model)) between 1 and 200),
    temperature numeric(3, 2) not null default 0.2 check (temperature between 0 and 2),
    max_tokens integer not null default 1000 check (max_tokens between 1 and 200000),
    key_last4 text check (key_last4 is null or char_length(key_last4) <= 4),
    is_default boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (id, user_id),
    -- an OpenAI-compatible endpoint is useless without an address
    check (provider <> 'openai_compatible' or base_url is not null)
);
create unique index llm_profiles_one_default_per_user on public.llm_profiles (user_id) where is_default;
create index llm_profiles_user_idx on public.llm_profiles (user_id, created_at);
create trigger llm_profiles_updated before update on public.llm_profiles
    for each row execute function public.set_updated_at();

-- The encrypted API key. Write-only for the app: even the API response only ever carries key_last4.
create table public.llm_profile_secrets (
    profile_id uuid primary key references public.llm_profiles (id) on delete cascade,
    key_enc bytea not null,
    key_version integer not null default 1 check (key_version >= 1),
    updated_at timestamptz not null default now()
);

-- ----------------------------------------------------------------------------------------------------- agents
create table public.agents (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users (id) on delete cascade,
    name text not null check (char_length(btrim(name)) between 1 and 60),
    template_id text not null,
    connector text not null,
    instructions text not null default '' check (char_length(instructions) <= 2000),
    status text not null default 'draft' check (status in ('draft', 'active', 'paused')),
    llm_profile_id uuid,
    time_zone text check (time_zone is null or char_length(time_zone) <= 64),
    toggles jsonb not null default '{}' check (jsonb_typeof(toggles) = 'object'),
    approval_modes jsonb not null default '{}' check (jsonb_typeof(approval_modes) = 'object'),
    last_activity_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (id, user_id),
    unique (id, user_id, connector),
    -- v1 ships exactly three agents, and each template has exactly one connector
    check (
        (template_id = 'github-assistant' and connector = 'github')
        or (template_id = 'gmail-assistant' and connector = 'gmail')
        or (template_id = 'calendar-assistant' and connector = 'calendar')
    ),
    -- deleting the profile leaves the agent without one, never deletes the agent
    foreign key (llm_profile_id, user_id) references public.llm_profiles (id, user_id)
        on delete set null (llm_profile_id)
);
create index agents_user_idx on public.agents (user_id, created_at desc);
create trigger agents_updated before update on public.agents
    for each row execute function public.set_updated_at();

-- ------------------------------------------------------------------------------------------------ connections
-- One provider grant per agent (architecture 1.3). Only status and scopes are visible to the app.
create table public.connections (
    id uuid primary key default gen_random_uuid(),
    agent_id uuid not null unique,
    user_id uuid not null,
    connector text not null,
    status text not null default 'active' check (status in ('active', 'needs_reauth', 'revoked')),
    scopes_granted text[] not null default '{}',
    expires_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (id, user_id),
    -- the connection's connector must be the agent's own connector
    foreign key (agent_id, user_id, connector) references public.agents (id, user_id, connector) on delete cascade
);
create trigger connections_updated before update on public.connections
    for each row execute function public.set_updated_at();

create table public.connection_secrets (
    connection_id uuid primary key references public.connections (id) on delete cascade,
    access_token_enc bytea not null,
    refresh_token_enc bytea,
    key_version integer not null default 1 check (key_version >= 1),
    updated_at timestamptz not null default now()
);

-- Pending OAuth handshakes. Only the hash of `state` is stored, the PKCE verifier is encrypted, and a row can
-- be used once (used_at) and only before expires_at.
create table public.oauth_states (
    state_hash text primary key check (state_hash ~ '^[0-9a-f]{64}$'),
    user_id uuid not null references auth.users (id) on delete cascade,
    agent_id uuid not null,
    connector text not null,
    scopes text[] not null default '{}',
    code_verifier_enc bytea not null,
    key_version integer not null default 1 check (key_version >= 1),
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    used_at timestamptz,
    check (expires_at > created_at),
    foreign key (agent_id, user_id, connector) references public.agents (id, user_id, connector) on delete cascade
);
create index oauth_states_expiry_idx on public.oauth_states (expires_at);

-- ------------------------------------------------------------------------------------- conversations and runs
create table public.conversations (
    id uuid primary key default gen_random_uuid(),
    agent_id uuid not null,
    user_id uuid not null,
    title text check (title is null or char_length(title) <= 200),
    summary text check (summary is null or char_length(summary) <= 20000),
    summary_covers_seq bigint not null default 0 check (summary_covers_seq >= 0),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    archived_at timestamptz,
    unique (id, user_id),
    unique (id, user_id, agent_id),
    foreign key (agent_id, user_id) references public.agents (id, user_id) on delete cascade
);
create index conversations_agent_idx on public.conversations (agent_id, updated_at desc);
create trigger conversations_updated before update on public.conversations
    for each row execute function public.set_updated_at();

create table public.runs (
    id uuid primary key default gen_random_uuid(),
    conversation_id uuid not null,
    agent_id uuid not null,
    user_id uuid not null,
    trigger text not null check (trigger in ('user', 'schedule')),
    status text not null default 'running'
        check (status in ('queued', 'running', 'awaiting_approval', 'completed', 'failed', 'cancelled', 'expired')),
    llm_profile_id uuid,
    model text check (model is null or char_length(model) <= 200),
    input_tokens bigint not null default 0 check (input_tokens >= 0),
    output_tokens bigint not null default 0 check (output_tokens >= 0),
    step_count integer not null default 0 check (step_count >= 0),
    tool_call_count integer not null default 0 check (tool_call_count >= 0),
    error_code text check (error_code is null or char_length(error_code) <= 100),
    cancel_requested boolean not null default false,
    started_at timestamptz not null default now(),
    last_progress_at timestamptz not null default now(),
    finished_at timestamptz,
    unique (id, user_id),
    unique (id, user_id, agent_id),
    -- a run is finished exactly when its status says so
    check ((status in ('completed', 'failed', 'cancelled', 'expired')) = (finished_at is not null)),
    foreign key (conversation_id, user_id, agent_id)
        references public.conversations (id, user_id, agent_id) on delete cascade
);
-- One active run per conversation (architecture 3.4). A run waiting for approval is parked in the database and
-- does not block the conversation.
create unique index runs_one_active_per_conversation on public.runs (conversation_id)
    where status in ('queued', 'running');
create index runs_agent_idx on public.runs (agent_id, started_at desc);
create index runs_stale_idx on public.runs (last_progress_at) where status in ('queued', 'running');

create table public.messages (
    id uuid primary key default gen_random_uuid(),
    conversation_id uuid not null,
    user_id uuid not null,
    run_id uuid,
    seq bigint not null check (seq > 0),
    role text not null check (role in ('user', 'assistant', 'tool')),
    content jsonb not null default '[]'
        check (jsonb_typeof(content) = 'array' and octet_length(content::text) <= 200000),
    token_count integer check (token_count is null or token_count >= 0),
    created_at timestamptz not null default now(),
    unique (conversation_id, seq),
    foreign key (conversation_id, user_id) references public.conversations (id, user_id) on delete cascade,
    foreign key (run_id, user_id) references public.runs (id, user_id) on delete set null (run_id)
);

create table public.run_events (
    run_id uuid not null,
    seq integer not null check (seq > 0),
    user_id uuid not null,
    type text not null check (type in (
        'run.started', 'message.delta', 'tool.started', 'tool.finished',
        'approval.required', 'run.completed', 'run.error'
    )),
    payload jsonb not null default '{}'
        check (jsonb_typeof(payload) = 'object' and octet_length(payload::text) <= 65536),
    created_at timestamptz not null default now(),
    primary key (run_id, seq),
    foreign key (run_id, user_id) references public.runs (id, user_id) on delete cascade
);
create index run_events_cleanup_idx on public.run_events (created_at);

-- --------------------------------------------------------------------------------------------------- approvals
create table public.approvals (
    id uuid primary key default gen_random_uuid(),
    run_id uuid not null,
    agent_id uuid not null,
    user_id uuid not null,
    tool_call_id text not null check (char_length(tool_call_id) between 1 and 200),
    tool text not null check (tool ~ '^(github|gmail|calendar)\.[a-z_]+$'),
    args jsonb not null check (jsonb_typeof(args) = 'object' and octet_length(args::text) <= 100000),
    args_hash text not null check (args_hash ~ '^[0-9a-f]{64}$'),
    status text not null default 'pending' check (status in ('pending', 'approved', 'rejected', 'expired')),
    created_at timestamptz not null default now(),
    decided_at timestamptz,
    expires_at timestamptz not null,
    unique (run_id, tool_call_id),
    check ((status = 'pending') = (decided_at is null)),
    check (expires_at > created_at),
    foreign key (run_id, user_id, agent_id) references public.runs (id, user_id, agent_id) on delete cascade
);
create index approvals_user_status_idx on public.approvals (user_id, status);
create index approvals_pending_expiry_idx on public.approvals (expires_at) where status = 'pending';

-- "Approved actions run with the stored arguments only" (CLAUDE.md rule 4), enforced by the database:
-- what is being approved can never change, and a decided approval can never be touched again. The one allowed
-- change is pending -> approved / rejected / expired.
create function public.approvals_guard() returns trigger
language plpgsql
set search_path = ''
as $$
begin
    if old.status <> 'pending' then
        raise exception 'approval already decided' using errcode = '23514';
    end if;
    if new.id is distinct from old.id
        or new.run_id is distinct from old.run_id
        or new.agent_id is distinct from old.agent_id
        or new.user_id is distinct from old.user_id
        or new.tool_call_id is distinct from old.tool_call_id
        or new.tool is distinct from old.tool
        or new.args is distinct from old.args
        or new.args_hash is distinct from old.args_hash
        or new.created_at is distinct from old.created_at
        or new.expires_at is distinct from old.expires_at then
        raise exception 'approval content is immutable' using errcode = '23514';
    end if;
    return new;
end;
$$;
create trigger approvals_guard before update on public.approvals
    for each row execute function public.approvals_guard();

-- ---------------------------------------------------------------------------------------------- audit and more
create table public.audit_logs (
    id uuid primary key default gen_random_uuid(),
    agent_id uuid not null,
    user_id uuid not null,
    run_id uuid,
    tool text not null check (char_length(tool) between 1 and 100),
    args_redacted jsonb check (args_redacted is null or (jsonb_typeof(args_redacted) = 'object'
        and octet_length(args_redacted::text) <= 20000)),
    result_summary text not null default '' check (char_length(result_summary) <= 2000),
    created_at timestamptz not null default now(),
    foreign key (agent_id, user_id) references public.agents (id, user_id) on delete cascade
);
create index audit_logs_agent_idx on public.audit_logs (agent_id, created_at desc, id desc);

-- The audit trail is append-only.
create function public.audit_logs_no_update() returns trigger
language plpgsql
set search_path = ''
as $$
begin
    raise exception 'audit log entries cannot be changed' using errcode = '23514';
end;
$$;
create trigger audit_logs_no_update before update on public.audit_logs
    for each row execute function public.audit_logs_no_update();

create table public.devices (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users (id) on delete cascade,
    fcm_token text not null unique check (char_length(fcm_token) between 20 and 4096),
    created_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now()
);
create index devices_user_idx on public.devices (user_id);

create table public.schedules (
    id uuid primary key default gen_random_uuid(),
    agent_id uuid not null unique,
    user_id uuid not null,
    enabled boolean not null default false,
    cron text not null check (char_length(cron) between 1 and 100),
    time_zone text not null check (char_length(time_zone) between 1 and 64),
    prompt text not null default '' check (char_length(prompt) <= 1000),
    last_run_at timestamptz,
    next_run_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    foreign key (agent_id, user_id) references public.agents (id, user_id) on delete cascade
);
create index schedules_due_idx on public.schedules (next_run_at) where enabled;
create trigger schedules_updated before update on public.schedules
    for each row execute function public.set_updated_at();

-- ----------------------------------------------------------------------------------------- row-level security
alter table public.llm_profiles enable row level security;
alter table public.llm_profile_secrets enable row level security;
alter table public.agents enable row level security;
alter table public.connections enable row level security;
alter table public.connection_secrets enable row level security;
alter table public.oauth_states enable row level security;
alter table public.conversations enable row level security;
alter table public.runs enable row level security;
alter table public.messages enable row level security;
alter table public.run_events enable row level security;
alter table public.approvals enable row level security;
alter table public.audit_logs enable row level security;
alter table public.devices enable row level security;
alter table public.schedules enable row level security;

-- Read-only access to your own rows. Tables with no policy (the three secret tables) are unreadable to the app.
create policy llm_profiles_select_own on public.llm_profiles for select to authenticated
    using (user_id = (select auth.uid()));
create policy agents_select_own on public.agents for select to authenticated
    using (user_id = (select auth.uid()));
create policy connections_select_own on public.connections for select to authenticated
    using (user_id = (select auth.uid()));
create policy conversations_select_own on public.conversations for select to authenticated
    using (user_id = (select auth.uid()));
create policy runs_select_own on public.runs for select to authenticated
    using (user_id = (select auth.uid()));
create policy messages_select_own on public.messages for select to authenticated
    using (user_id = (select auth.uid()));
create policy run_events_select_own on public.run_events for select to authenticated
    using (user_id = (select auth.uid()));
create policy approvals_select_own on public.approvals for select to authenticated
    using (user_id = (select auth.uid()));
create policy audit_logs_select_own on public.audit_logs for select to authenticated
    using (user_id = (select auth.uid()));
create policy devices_select_own on public.devices for select to authenticated
    using (user_id = (select auth.uid()));
create policy schedules_select_own on public.schedules for select to authenticated
    using (user_id = (select auth.uid()));

-- ------------------------------------------------------------------------------------------------- privileges
-- Supabase grants new tables to anon/authenticated by default. Take everything away, then give back only what
-- is intended: SELECT on the user-owned tables, nothing on the secret tables, nothing to anon.
revoke all on public.llm_profiles, public.llm_profile_secrets, public.agents, public.connections,
    public.connection_secrets, public.oauth_states, public.conversations, public.runs, public.messages,
    public.run_events, public.approvals, public.audit_logs, public.devices, public.schedules
    from anon, authenticated;
grant select on public.llm_profiles, public.agents, public.connections, public.conversations, public.runs,
    public.messages, public.run_events, public.approvals, public.audit_logs, public.devices, public.schedules
    to authenticated;

revoke all on function public.set_updated_at(), public.approvals_guard(), public.audit_logs_no_update()
    from public, anon, authenticated;
