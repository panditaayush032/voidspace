-- Run in Supabase SQL Editor. Safe to run repeatedly for the bridge tables.
-- The plugin uses the service-role key ONLY on the Paper server, never in the website.

create table if not exists public.player_stats (
  uuid uuid primary key,
  username text not null,
  rank text not null default 'member',
  money numeric not null default 0,
  kills bigint not null default 0,
  deaths bigint not null default 0,
  playtime_seconds bigint not null default 0,
  updated_at timestamptz not null default now()
);

alter table public.player_stats add column if not exists rank text not null default 'member';
alter table public.player_stats add column if not exists money numeric not null default 0;
alter table public.player_stats add column if not exists kills bigint not null default 0;
alter table public.player_stats add column if not exists deaths bigint not null default 0;
alter table public.player_stats add column if not exists playtime_seconds bigint not null default 0;
alter table public.player_stats add column if not exists updated_at timestamptz not null default now();

create table if not exists public.online_players (
  uuid uuid primary key,
  username text not null,
  server_id text not null default 'main-paper',
  joined_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
alter table public.online_players add column if not exists server_id text not null default 'main-paper';
alter table public.online_players add column if not exists joined_at timestamptz not null default now();
alter table public.online_players add column if not exists updated_at timestamptz not null default now();

create table if not exists public.player_events (
  id uuid primary key default gen_random_uuid(),
  player_uuid uuid not null,
  username text not null,
  event_type text not null check (event_type in ('join','leave')),
  server_id text not null default 'main-paper',
  occurred_at timestamptz not null default now()
);
alter table public.player_events add column if not exists server_id text not null default 'main-paper';
create index if not exists player_events_occurred_at_idx on public.player_events (occurred_at desc);
create index if not exists player_stats_money_idx on public.player_stats (money desc);
create index if not exists player_stats_kills_idx on public.player_stats (kills desc);

alter table public.player_stats enable row level security;
alter table public.online_players enable row level security;
alter table public.player_events enable row level security;

drop policy if exists "Public can read player stats" on public.player_stats;
create policy "Public can read player stats" on public.player_stats for select to anon, authenticated using (true);
drop policy if exists "Public can read online players" on public.online_players;
create policy "Public can read online players" on public.online_players for select to anon, authenticated using (true);
drop policy if exists "Public can read player events" on public.player_events;
create policy "Public can read player events" on public.player_events for select to anon, authenticated using (true);

-- Do not create public insert/update/delete policies. The plugin uses service-role credentials server-side.
-- Add tables to Realtime publication if it exists and they are not already present.
do $$ begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    begin alter publication supabase_realtime add table public.player_stats; exception when duplicate_object then null; when others then null; end;
    begin alter publication supabase_realtime add table public.online_players; exception when duplicate_object then null; when others then null; end;
    begin alter publication supabase_realtime add table public.player_events; exception when duplicate_object then null; when others then null; end;
  end if;
end $$;
