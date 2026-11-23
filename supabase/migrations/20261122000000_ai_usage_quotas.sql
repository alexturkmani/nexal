-- ─── AI usage quotas ────────────────────────────────────────────────────────
--
-- Free accounts get 3 AI actions for life, shared across features (feature
-- 'free', window 2000-01-01). Premium accounts get fair-use caps per feature,
-- per day or per week. Only the Edge Functions (service role) write here;
-- users can read their own rows to show what they have left.

create table if not exists public.ai_usage (
  user_id uuid not null references auth.users(id) on delete cascade,
  feature text not null,
  window_start date not null,
  count integer not null default 0 check (count >= 0),
  updated_at timestamptz not null default now(),
  primary key (user_id, feature, window_start)
);

alter table public.ai_usage enable row level security;

drop policy if exists "Users read own usage" on public.ai_usage;
create policy "Users read own usage" on public.ai_usage
  for select to authenticated using (auth.uid() = user_id);

revoke insert, update, delete on public.ai_usage from anon, authenticated;

-- Takes one unit if the caller is under p_limit. Atomic, so two parallel
-- requests can't both take the last unit.
create or replace function public.consume_ai_quota(
  p_user uuid, p_feature text, p_window date, p_limit integer
) returns table (allowed boolean, used integer)
language plpgsql
security definer
set search_path = public
as $$
declare
  v_count integer;
begin
  if p_limit < 1 then
    select coalesce(max(u.count), 0) into v_count from public.ai_usage u
      where u.user_id = p_user and u.feature = p_feature and u.window_start = p_window;
    return query select false, v_count;
    return;
  end if;

  insert into public.ai_usage as u (user_id, feature, window_start, count)
    values (p_user, p_feature, p_window, 1)
  on conflict (user_id, feature, window_start)
    do update set count = u.count + 1, updated_at = now()
    where u.count < p_limit
  returning u.count into v_count;

  if v_count is null then
    select u.count into v_count from public.ai_usage u
      where u.user_id = p_user and u.feature = p_feature and u.window_start = p_window;
    return query select false, v_count;
  else
    return query select true, v_count;
  end if;
end;
$$;

-- Gives a unit back when the AI call failed, so errors don't cost the user.
create or replace function public.refund_ai_quota(
  p_user uuid, p_feature text, p_window date
) returns void
language sql
security definer
set search_path = public
as $$
  update public.ai_usage
    set count = greatest(count - 1, 0), updated_at = now()
    where user_id = p_user and feature = p_feature and window_start = p_window;
$$;

revoke execute on function public.consume_ai_quota(uuid, text, date, integer) from public, anon, authenticated;
revoke execute on function public.refund_ai_quota(uuid, text, date) from public, anon, authenticated;
grant execute on function public.consume_ai_quota(uuid, text, date, integer) to service_role;
grant execute on function public.refund_ai_quota(uuid, text, date) to service_role;
