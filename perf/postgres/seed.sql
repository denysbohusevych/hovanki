-- The lab's database as production's after 90 days of a game that took off (docs/perf.md, «Данные как на проде»), so
-- the load tests meet realistic table sizes: the history, the statistics, the leaderboard and the achievements read
-- game_results on every request. Test data of made-up players; never run it anywhere but the lab's database.
--
--   perf/seed.sh                                   100 000 accounts, about 1 000 000 results (1.5 min, 760 MB)
--   perf/seed.sh -v users=20000 -v results=200000  smaller; the simulations then need -Pperf.seedUsers=20000
--
-- Runs against a database the server has migrated already (Flyway at its start: perf/server.sh once), in one
-- transaction. Running it again first deletes what an earlier run made (its ids, below) and nothing else, and compacts
-- those tables (VACUUM FULL): the simulations' own accounts and games stay. Times are relative to now() at the run;
-- nothing is older than its retention (DataRetention: routes and recordings 90 days, friend requests 90 days, idle
-- sessions 180 days).
--
-- Parameters (psql -v name=value, defaults in brackets):
--   users       accounts [100000]
--   results     rows of game_results to aim at [1000000]; the games are as many as that takes (~8.5 accounts each)
--   days        how long the game has been on [90]
--   growth      how many times busier the last day is than the first [10]: players and games grow exponentially,
--               so the leaderboard's weeks (last Monday on) hold a quarter to a third of the results, by the day
--   recordings  the most recent games that get their recording, everybody's way (ADR 0011) [2000]
--   routes      the most recent saved routes (ADR 0007) [5000]
--
-- Accounts (perf/src/gatling/kotlin/app/hovanki/perf/SeedAccounts.kt computes the same; keep them together):
--   index n     0 until users, in the order they signed up
--   id          'pu' || lpad((n * 7919731 mod 10^10), 10, '0')   12 characters of [a-z0-9] like IdGenerator's
--   login       email 'player<n>@hovanki.test'; nickname: a first name, maybe a separator, then n (e.g. 'marta_4821')
--   password    perf-player-password (one BCrypt hash of strength 10 for everybody, as production's)
--   token       lowercase hex SHA-256 of 'hovanki-perf-user-<n>': 64 hex characters like the server's own tokens
--   token_hash  lowercase hex SHA-256 of the token (AccountKeys.tokenHash), one session per account with it
--   Every account is verified, never banned (a few have lifted or expired bans and chat bans), a few own paid extras.
--
-- Other ids: games 'pg' || 10 digits (the same scramble of the game's number), groups 'pr' || 10 digits, recorded
-- players md5-derived. The seed deletes exactly those.
--
-- The full recording volume (measured 14.10.2026 on the sample of the defaults): production keeps a recording of every
-- ordinary game played to its end with an account in it (ADR 0011), everybody's way, for 90 days. At this scale
-- (118 000 games of 10.7 players on average over 90 days: 1.26 million ways) and ReplayTrack's format (a point every
-- 5 s, full doubles, about 310 points a way in rounds of 33 minutes on average), a way is 25 KB of JSON, 8.3 KB in
-- jsonb after PostgreSQL's pglz, 9.5 KB with TOAST's and the index's overhead: about 11 GB on disk for 90 days (GiB,
-- as pg_size_pretty counts), 30 GB as JSON. The saved routes (ADR 0007) of the 12 % of accounts that keep them: 95 000
-- routes, 6.4 KB each, 0.6 GB. A run prints the same estimate at its end («recordings, all games», «routes, all»).

\set ON_ERROR_STOP on
\pset pager off
\if :{?users}
\else
  \set users 100000
\endif
\if :{?results}
\else
  \set results 1000000
\endif
\if :{?days}
\else
  \set days 90
\endif
\if :{?growth}
\else
  \set growth 10
\endif
\if :{?recordings}
\else
  \set recordings 2000
\endif
\if :{?routes}
\else
  \set routes 5000
\endif

SELECT to_regclass('public.game_results') IS NOT NULL
   AND EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'achievements_seen_at')
       AS migrated \gset
\if :migrated
\else
  \echo 'No schema yet (or an old one): start the server once (perf/server.sh), Flyway migrates it, then seed.'
  SELECT 1 / 0 AS not_migrated;
\endif

\echo Seeding :users accounts and about :results results over :days days, growth :growth

-- An earlier run's rows go first, in a transaction of their own, and their tables are rewritten without them: a plain
-- DELETE leaves the files as big as they were, and the new rows would come on top of the old ones' space.
SELECT EXISTS (SELECT 1 FROM users WHERE id ~ '^pu[0-9]{10}$')
    OR EXISTS (SELECT 1 FROM played_games WHERE id ~ '^pg[0-9]{10}$') AS reseed \gset
\if :reseed
  \echo 'Deleting the previous seed'
  BEGIN;
  DELETE FROM users WHERE id ~ '^pu[0-9]{10}$';
  DELETE FROM played_games WHERE id ~ '^pg[0-9]{10}$';
  COMMIT;
  VACUUM (FULL) users, account_sessions, friendships, friend_requests, blocks, user_groups, group_members, sanctions,
      entitlements, played_games, game_results, game_routes, game_recordings, game_recording_tracks;
\endif

BEGIN;
-- One run, one sequence of random(): parallel workers would draw their own.
SET LOCAL max_parallel_workers_per_gather = 0;
SET LOCAL synchronous_commit = off;
SET LOCAL work_mem = '64MB';
SET LOCAL temp_buffers = '64MB';
SELECT setseed(0.42) \gset

-- The cities of the city leaderboard (Cities.ALL in :shared), weighted roughly by how many people live there.
CREATE TEMP TABLE seed_cities (
    id text PRIMARY KEY, lat float8, lon float8, radius float8, weight float8, lo float8, hi float8
) ON COMMIT DROP;
INSERT INTO seed_cities (id, lat, lon, radius, weight) VALUES
    ('kyiv', 50.4501, 30.5234, 30000, 30), ('kharkiv', 49.9935, 36.2304, 25000, 12),
    ('odesa', 46.4825, 30.7233, 25000, 10), ('dnipro', 48.4647, 35.0462, 25000, 9),
    ('lviv', 49.8397, 24.0297, 20000, 8), ('zaporizhzhia', 47.8388, 35.1396, 20000, 5),
    ('kryvyi_rih', 47.9105, 33.3918, 30000, 4), ('mykolaiv', 46.9750, 31.9946, 20000, 3),
    ('vinnytsia', 49.2331, 28.4682, 20000, 3), ('poltava', 49.5883, 34.5514, 20000, 2.5),
    ('chernihiv', 51.4982, 31.2893, 20000, 2), ('cherkasy', 49.4444, 32.0598, 20000, 2),
    ('khmelnytskyi', 49.4229, 26.9871, 20000, 2), ('zhytomyr', 50.2547, 28.6587, 20000, 2),
    ('sumy', 50.9077, 34.7981, 20000, 2), ('rivne', 50.6199, 26.2516, 20000, 2),
    ('ivano_frankivsk', 48.9226, 24.7111, 20000, 2), ('kropyvnytskyi', 48.5079, 32.2623, 20000, 1.5),
    ('ternopil', 49.5535, 25.5948, 20000, 1.5), ('lutsk', 50.7472, 25.3254, 20000, 1.5),
    ('kherson', 46.6354, 32.6169, 20000, 1.5), ('bila_tserkva', 49.7988, 30.1153, 20000, 1),
    ('kremenchuk', 49.0670, 33.4204, 20000, 1), ('uzhhorod', 48.6208, 22.2879, 20000, 1),
    ('chernivtsi', 48.2915, 25.9403, 20000, 1.5);
UPDATE seed_cities c SET lo = w.lo, hi = CASE WHEN w.last THEN 2 ELSE w.hi END
FROM (SELECT id,
             (sum(weight) OVER (ORDER BY weight DESC, id) - weight) / sum(weight) OVER () AS lo,
             sum(weight) OVER (ORDER BY weight DESC, id) / sum(weight) OVER () AS hi,
             row_number() OVER (ORDER BY weight DESC, id) = count(*) OVER () AS last
      FROM seed_cities) w
WHERE w.id = c.id;

SELECT round(extract(epoch FROM clock_timestamp() - now())) AS s \gset
\echo '[1/8] accounts, at' :s 's'
-- Sign-ups grow like the games: account n signed up at the n-th quantile of the exponential curve.
CREATE TEMP TABLE seed_users ON COMMIT DROP AS
SELECT n,
       'pu' || lpad(((n::bigint * 7919731) % 10000000000)::text, 10, '0') AS id,
       now() - make_interval(days => :days)
           + make_interval(secs => (86400.0 * :days
               * ln(1 + (n + 0.5) / :users * (:growth - 1)) / ln(:growth))::float8) AS created_at,
       random() AS r_name, random() AS r_style, random() AS r_lang, random() AS r_city, random() AS r_routes,
       random() AS r_seen, random() AS r_t1, random() AS r_t2
FROM generate_series(0, :users - 1) AS n;
ALTER TABLE seed_users ADD PRIMARY KEY (n);

CREATE TEMP TABLE seed_accounts ON COMMIT DROP AS
SELECT u.n, u.id, u.created_at,
       CASE WHEN u.r_style < 0.40 THEN nm.name || u.n
            WHEN u.r_style < 0.60 THEN nm.name || '_' || u.n
            WHEN u.r_style < 0.75 AND nm.name ~ '^[a-z]' THEN initcap(nm.name) || u.n
            WHEN u.r_style < 0.90 THEN nm.name || '.' || u.n
            ELSE nm.name || '-' || u.n END AS nickname,
       CASE WHEN u.r_lang < 0.60 THEN 'uk' WHEN u.r_lang < 0.85 THEN 'en' ELSE 'ru' END AS language,
       c.id AS city,
       CASE WHEN u.r_routes < 0.12 THEN u.created_at + (now() - u.created_at) * (u.r_t1 * 0.3) END AS save_routes_since,
       CASE WHEN u.r_seen < 0.60 THEN u.created_at + (now() - u.created_at) * u.r_t2 END AS achievements_seen_at
FROM seed_users u
CROSS JOIN LATERAL (
    SELECT (ARRAY['oleksa', 'marta', 'taras', 'sofiia', 'danylo', 'iryna', 'bohdan', 'yana', 'maksym', 'olena',
                  'andrii', 'kateryna', 'dmytro', 'anna', 'ivan', 'daria', 'artem', 'yuliia', 'nazar', 'viktoriia',
                  'roman', 'khrystyna', 'denys', 'alina', 'vlad', 'mariia', 'serhii', 'oksana', 'yurii', 'polina',
                  'ostap', 'zlata', 'mykola', 'vira', 'petro', 'nadiia', 'ihor', 'lesia', 'stepan', 'solomiia',
                  'fox', 'ninja', 'shadow', 'tiger', 'ghost', 'hunter', 'max', 'kit',
                  'тарас', 'оксана', 'богдан', 'соломія', 'остап', 'мирослава', 'хованка', 'тінь'])
               [1 + floor(u.r_name * 56)::int] AS name
) nm
LEFT JOIN seed_cities c ON u.r_city >= 0.35 AND (u.r_city - 0.35) / 0.65 >= c.lo AND (u.r_city - 0.35) / 0.65 < c.hi;
ALTER TABLE seed_accounts ADD PRIMARY KEY (n);

INSERT INTO users (id, nickname, nickname_key, email, email_key, email_verified_at, password_hash, language,
                   created_at, save_routes_since, role, city, achievements_seen_at)
SELECT id, nickname, lower(nickname), 'player' || n || '@hovanki.test', 'player' || n || '@hovanki.test',
       created_at + make_interval(secs => (60 + (n % 900))::float8),
       -- BCrypt (strength 10) of perf-player-password, made by Spring's BCryptPasswordEncoder.
       '$2a$10$jbn1GZjw9al4DCv8HjzIrOEsuS0Gg5dOfJXk4OGrR0VniG/bbShpm',
       language, created_at, save_routes_since, 'PLAYER', city, achievements_seen_at
FROM seed_accounts
ORDER BY n;

SELECT round(extract(epoch FROM clock_timestamp() - now())) AS s \gset
\echo '[2/8] sessions, at' :s 's'
-- The known session of every account: registering logs the phone in. Mostly used lately.
INSERT INTO account_sessions (token_hash, user_id, created_at, last_used_at)
SELECT encode(sha256(convert_to(encode(sha256(convert_to('hovanki-perf-user-' || n, 'UTF8')), 'hex'), 'UTF8')), 'hex'),
       id, created_at, created_at + (now() - created_at) * (1 - power(random(), 3))
FROM seed_accounts
ORDER BY n;
-- A second phone (or a login after a reinstall) for a third of them; nobody knows these tokens.
INSERT INTO account_sessions (token_hash, user_id, created_at, last_used_at)
SELECT encode(sha256(convert_to('hovanki-perf-other-' || n || '-' || r, 'UTF8')), 'hex'), id, t,
       t + (now() - t) * random()
FROM (SELECT n, id, random() AS r, created_at + (now() - created_at) * random() AS t FROM seed_accounts) s
WHERE r < 0.35;

SELECT round(extract(epoch FROM clock_timestamp() - now())) AS s \gset
\echo '[3/8] friends, requests, blocks, groups, at' :s 's'
-- Each account asks 0..8 others (4 on average), mostly those who signed up around the same time: ~8 friends each.
CREATE TEMP TABLE seed_pairs ON COMMIT DROP AS
SELECT DISTINCT least(a, b) AS a, greatest(a, b) AS b
FROM (SELECT u.n AS a,
             ((u.n + 1 + floor((:users - 1) * power(random(), 4)))::bigint % :users)::int AS b
      FROM (SELECT n, floor(random() * 9)::int AS asks FROM seed_users) u
      CROSS JOIN LATERAL generate_series(1, u.asks) AS k) p
WHERE a <> b;

INSERT INTO friendships (user_id, friend_id, created_at)
SELECT x.id, y.id, f.t
FROM (SELECT p.a, p.b,
             greatest(ua.created_at, ub.created_at)
                 + (now() - greatest(ua.created_at, ub.created_at)) * (random() * 0.6) AS t
      FROM seed_pairs p
      JOIN seed_users ua ON ua.n = p.a
      JOIN seed_users ub ON ub.n = p.b) f
CROSS JOIN LATERAL (VALUES (f.a, f.b), (f.b, f.a)) AS d (me, other)
JOIN seed_users x ON x.n = d.me
JOIN seed_users y ON y.n = d.other;

-- Pending friend requests of the last month, between accounts that are not friends.
INSERT INTO friend_requests (from_user, to_user, created_at)
SELECT DISTINCT ON (least(a, b), greatest(a, b)) x.id, y.id,
       greatest(x.created_at, y.created_at, now() - interval '30 days')
           + (now() - greatest(x.created_at, y.created_at, now() - interval '30 days')) * r
FROM (SELECT floor(random() * :users)::int AS a, floor(random() * :users)::int AS b, random() AS r
      FROM generate_series(1, :users / 6)) q
JOIN seed_users x ON x.n = q.a
JOIN seed_users y ON y.n = q.b
WHERE a <> b
  AND NOT EXISTS (SELECT 1 FROM seed_pairs p WHERE p.a = least(q.a, q.b) AND p.b = greatest(q.a, q.b));

-- A few blocks, never between friends (a block ends a friendship).
INSERT INTO blocks (blocker_id, blocked_id, created_at)
SELECT DISTINCT ON (q.a, q.b) x.id, y.id, greatest(x.created_at, y.created_at)
    + (now() - greatest(x.created_at, y.created_at)) * q.r
FROM (SELECT floor(random() * :users)::int AS a, floor(random() * :users)::int AS b, random() AS r
      FROM generate_series(1, :users / 100)) q
JOIN seed_users x ON x.n = q.a
JOIN seed_users y ON y.n = q.b
WHERE q.a <> q.b
  AND NOT EXISTS (SELECT 1 FROM seed_pairs p WHERE p.a = least(q.a, q.b) AND p.b = greatest(q.a, q.b))
  AND NOT EXISTS (SELECT 1 FROM friend_requests r WHERE r.from_user IN (x.id, y.id) AND r.to_user IN (x.id, y.id));

-- Groups: one in ten accounts with at least two friends owns one, with 2..10 of those friends in it.
CREATE TEMP TABLE seed_groups ON COMMIT DROP AS
SELECT row_number() OVER (ORDER BY o.n) AS g, o.n AS owner, o.id AS owner_id, o.t AS created_at,
       2 + floor(o.r * 9)::int AS size,
       (ARRAY['Двір', 'Школа', 'Друзі', 'Hide & Seek', 'Субота у парку', 'Сусіди', 'Клас 9-Б', 'Команда',
              'Хованки на Подолі', 'Uni crew', 'Family', 'Офіс', 'Велосипедисти', 'Табір', 'Рідні', 'Ninjas',
              'Вечірні хованки', 'Гуртожиток'])[1 + floor(o.r2 * 18)::int] AS name
FROM (SELECT u.n, u.id, u.created_at + (now() - u.created_at) * random() AS t, random() AS r, random() AS r2,
             random() AS pick
      FROM seed_users u) o
WHERE o.pick < 0.10
  AND (SELECT count(*) FROM friendships f WHERE f.user_id = o.id) >= 2;

INSERT INTO user_groups (id, name, owner_id, created_at)
SELECT 'pr' || lpad(((g * 7919731) % 10000000000)::text, 10, '0'), name, owner_id, created_at
FROM seed_groups;

INSERT INTO group_members (group_id, user_id, joined_at)
SELECT 'pr' || lpad(((g.g * 7919731) % 10000000000)::text, 10, '0'), m.user_id, g.created_at
FROM seed_groups g
CROSS JOIN LATERAL (
    SELECT g.owner_id AS user_id
    UNION ALL
    (SELECT f.friend_id FROM friendships f WHERE f.user_id = g.owner_id ORDER BY random() LIMIT g.size)
) m;

SELECT round(extract(epoch FROM clock_timestamp() - now())) AS s \gset
\echo '[4/8] games, at' :s 's'
-- The games grow like the accounts; each game's lobby opens in the afternoon or the evening in Kyiv.
SELECT ceil(:results / 8.47)::int AS games \gset
CREATE TEMP TABLE seed_games0 ON COMMIT DROP AS
SELECT g,
       now() - make_interval(days => :days)
           + make_interval(secs => (86400.0 * :days
               * ln(1 + (g - random()) / :games * (:growth - 1)) / ln(:growth))::float8) AS base,
       4 + floor(random() * 7)::int + floor(random() * 7)::int
           + CASE WHEN random() < 0.12 THEN floor(random() * 13)::int ELSE 0 END AS players,
       random() AS r_seekers, random() AS r_hour1, random() AS r_hour2, random() AS r_win, random() AS r_seek,
       random() AS r_lobby, random() AS r_radius, random() AS r_stages, random() AS r_buildings, random() AS r_chat,
       random() AS r_city, random() AS r_x, random() AS r_y, random() AS r_street, random() AS r_disputes,
       (ARRAY[180, 300, 300, 300, 600])[1 + floor(random() * 5)::int] AS hiding_seconds,
       (ARRAY[900, 1200, 1800, 1800, 1800, 2700, 3600])[1 + floor(random() * 7)::int] AS seeking_seconds
FROM generate_series(1, :games) AS g;

CREATE TEMP TABLE seed_games ON COMMIT DROP AS
SELECT x.*,
       x.finished_at - make_interval(secs => (x.hiding_seconds + x.seek_actual)::float8) AS started_at,
       x.finished_at - make_interval(secs => x.seek_actual::float8) AS zone_started_at,
       x.finished_at - make_interval(secs => (x.hiding_seconds + x.seek_actual + 120 + floor(x.r_lobby * 780))::float8)
           AS created_at
FROM (SELECT g0.g, g0.players, g0.hiding_seconds, g0.seeking_seconds, g0.r_chat, g0.r_disputes, g0.r_street,
             g0.r_lobby,
             'pg' || lpad(((g0.g::bigint * 7919731) % 10000000000)::text, 10, '0') AS id,
             CASE WHEN f.t > now() - interval '2 minutes' THEN f.t - interval '1 day'
                  WHEN f.t < now() - make_interval(days => :days) + interval '3 hours' THEN f.t + interval '1 day'
                  ELSE f.t END AS finished_at,
             least(g0.players - 1, greatest(1, round(g0.players * (0.15 + 0.15 * g0.r_seekers))::int)) AS seekers,
             g0.r_win < 0.45 AS seekers_win,
             CASE WHEN g0.r_win < 0.45 THEN floor(g0.seeking_seconds * (0.35 + 0.6 * g0.r_seek))::int
                  ELSE g0.seeking_seconds END AS seek_actual,
             (250 + floor(g0.r_radius * 125) * 10)::float8 AS zone_radius,
             floor(g0.r_stages * 5)::int AS zone_stages,
             CASE WHEN g0.r_buildings < 0.9 THEN 'READY' ELSE 'UNAVAILABLE' END AS buildings,
             c.lat + (g0.r_x - 0.5) * 1.2 * c.radius / 111320.0 AS zlat,
             c.lon + (g0.r_y - 0.5) * 1.2 * c.radius / 111320.0 / cos(radians(c.lat)) AS zlon
      FROM seed_games0 g0
      CROSS JOIN LATERAL (
          SELECT (date_trunc('day', g0.base AT TIME ZONE 'Europe/Kyiv')
                     + make_interval(secs => (3600 * (11 + 11 * (g0.r_hour1 + g0.r_hour2) / 2))::float8))
                     AT TIME ZONE 'Europe/Kyiv' AS t
      ) f
      JOIN seed_cities c ON g0.r_city >= c.lo AND g0.r_city < c.hi) x;
ALTER TABLE seed_games ADD PRIMARY KEY (g);

SELECT round(extract(epoch FROM clock_timestamp() - now())) AS s \gset
\echo '[5/8] players of the games, at' :s 's'
-- Every player of every game: the first ones seek; four in five have an account, picked among the accounts that
-- existed when the lobby opened (so the oldest accounts have played the most); a hider is caught or eliminated, or
-- not when the hiders won (then at least one is still free).
CREATE TEMP TABLE seed_slots0 ON COMMIT DROP AS
SELECT g.g, s, s <= g.seekers AS seeker, random() >= 0.2 AS account_wanted,
       floor(random() * greatest(1, least(:users, floor(:users * (power(:growth::float8,
           extract(epoch FROM g.created_at - (now() - make_interval(days => :days))) / 86400.0 / :days) - 1)
           / (:growth - 1)))))::int AS user_n,
       random() AS r_free, random() AS r_out, random() AS r_out_at, random() AS r_claims, random() AS r_zone,
       random() AS r_bld, random() AS r_fixes, random() AS r_speed, random() AS r_moving, random() AS r_max,
       random() AS r_sparks, random() AS r_sparks2, random() AS r_quests, random() AS r_quests2, random() AS r_name,
       0.004 + random() * 0.012 AS w1, 0.01 + random() * 0.03 AS w2, random() * 6.283 AS p1, random() * 6.283 AS p2,
       random() * 6.283 AS p3, random() * 6.283 AS p4, 0.3 + random() * 0.55 AS amp
FROM seed_games g
CROSS JOIN LATERAL generate_series(1, g.players) AS s;

CREATE TEMP TABLE seed_slots1 ON COMMIT DROP AS
SELECT sl.*,
       CASE WHEN sl.seeker THEN 'ACTIVE'
            WHEN NOT g.seekers_win AND (sl.s = g.seekers + 1 OR sl.r_free < 0.55) THEN 'ACTIVE'
            WHEN sl.r_out < 0.85 THEN 'CAUGHT'
            ELSE 'ELIMINATED' END AS status,
       -- An account plays once per game, and only after it signed up; else the place is a guest's.
       sl.account_wanted AND u.created_at < g.created_at
           AND row_number() OVER (PARTITION BY sl.g, sl.user_n, sl.account_wanted ORDER BY sl.s) = 1 AS account
FROM seed_slots0 sl
JOIN seed_games g ON g.g = sl.g
JOIN seed_users u ON u.n = sl.user_n;
DROP TABLE seed_slots0;

CREATE TEMP TABLE seed_slots ON COMMIT DROP AS
SELECT sl.g, sl.s, sl.seeker, sl.account, sl.status, sl.user_n,
       sl.r_claims, sl.r_zone, sl.r_bld, sl.r_fixes, sl.r_speed, sl.r_moving, sl.r_max, sl.r_sparks, sl.r_sparks2,
       sl.r_quests, sl.r_quests2, sl.r_name, sl.w1, sl.w2, sl.p1, sl.p2, sl.p3, sl.p4, sl.amp,
       CASE WHEN sl.status = 'ACTIVE' THEN NULL
            ELSE floor(g.seek_actual * sl.r_out_at)::int END AS out_seconds,
       -- The j-th caught hider went to seeker (j - 1) mod seekers + 1: the seekers' catches add up to the game's.
       CASE WHEN sl.status = 'CAUGHT' THEN
                (row_number() OVER (PARTITION BY sl.g, sl.status ORDER BY sl.s) - 1) % g.seekers + 1 END AS caught_by_s,
       count(*) FILTER (WHERE sl.status = 'CAUGHT') OVER (PARTITION BY sl.g) AS game_caught
FROM seed_slots1 sl
JOIN seed_games g ON g.g = sl.g;
ALTER TABLE seed_slots ADD PRIMARY KEY (g, s);
DROP TABLE seed_slots1;

-- What each player's round adds up to (game_results' numbers), from the slots above.
CREATE TEMP TABLE seed_results ON COMMIT DROP AS
SELECT sl.g, sl.s, sl.account, sl.user_n, sl.seeker, sl.status, sl.out_seconds, sl.caught_by_s,
       catches, catches + CASE WHEN sl.seeker AND sl.r_claims < 0.3 THEN 1 ELSE 0 END AS catch_claims,
       CASE WHEN sl.seeker THEN NULL WHEN sl.status = 'ACTIVE' THEN g.seek_actual ELSE sl.out_seconds END
           AS survived_seconds,
       floor(power(sl.r_zone, 3) * 4)::int AS zone_warnings,
       CASE WHEN g.buildings = 'READY' THEN floor(power(sl.r_bld, 4) * 3)::int ELSE 0 END AS building_warnings,
       floor(active / 3.0 * (0.75 + 0.25 * sl.r_fixes))::int AS fixes,
       round((active * speed)::numeric, 1)::float8 AS distance_meters,
       least(active, floor(active * speed / (1.2 + 0.7 * sl.r_moving)))::int AS moving_seconds,
       CASE WHEN sl.r_max < 0.02 THEN NULL ELSE round((2.0 + 5.0 * sl.r_max)::numeric, 2)::float8 END AS max_speed_mps,
       CASE WHEN sl.r_sparks < 0.15 THEN floor(sl.r_sparks2 * 6)::int ELSE 0 END AS sparks,
       CASE WHEN sl.r_quests < 0.15 THEN floor(sl.r_quests2 * 4)::int ELSE 0 END AS quests_done,
       active,
       g.hiding_seconds
FROM seed_slots sl
JOIN seed_games g ON g.g = sl.g
CROSS JOIN LATERAL (
    SELECT CASE WHEN sl.seeker THEN sl.game_caught / g.seekers
                    + CASE WHEN sl.s <= sl.game_caught % g.seekers THEN 1 ELSE 0 END
                ELSE 0 END AS catches,
           g.hiding_seconds + coalesce(sl.out_seconds, g.seek_actual) AS active,
           CASE WHEN sl.seeker THEN 1.0 + 1.2 * sl.r_speed ELSE 0.4 + 0.9 * sl.r_speed END AS speed
) m;
ALTER TABLE seed_results ADD PRIMARY KEY (g, s);

INSERT INTO played_games (id, created_at, started_at, zone_started_at, finished_at, players, guests, seekers,
                          hiders_caught, hiders_eliminated, catch_claims, catches, disputes, chat_messages, buildings,
                          zone_radius_meters, zone_stages, hiding_seconds, seeking_seconds)
SELECT g.id, g.created_at, g.started_at, g.zone_started_at, g.finished_at, g.players, a.guests, g.seekers,
       a.caught, a.eliminated, a.claims, a.catches, floor(g.r_disputes * (a.claims - a.catches + 1))::int,
       floor(power(g.r_chat, 2) * 40)::int, g.buildings, g.zone_radius, g.zone_stages, g.hiding_seconds,
       g.seeking_seconds
FROM seed_games g
JOIN (SELECT r.g, count(*) FILTER (WHERE NOT r.account) AS guests,
             count(*) FILTER (WHERE r.status = 'CAUGHT') AS caught,
             count(*) FILTER (WHERE r.status = 'ELIMINATED') AS eliminated,
             sum(r.catch_claims) AS claims, sum(r.catches) AS catches
      FROM seed_results r GROUP BY r.g) a ON a.g = g.g
ORDER BY g.g;

SELECT round(extract(epoch FROM clock_timestamp() - now())) AS s \gset
\echo '[6/8] results, at' :s 's'
-- In the order they finished, as production writes them.
INSERT INTO game_results (user_id, game_id, started_at, finished_at, role, status, won, players, seekers, catch_claims,
                          catches, survived_seconds, zone_warnings, building_warnings, fixes, distance_meters,
                          moving_seconds, max_speed_mps, sparks, quests_done)
SELECT u.id, g.id, g.started_at, g.finished_at, CASE WHEN r.seeker THEN 'SEEKER' ELSE 'HIDER' END, r.status,
       CASE WHEN r.seeker THEN g.seekers_win ELSE r.status = 'ACTIVE' END,
       g.players, g.seekers, r.catch_claims, r.catches, r.survived_seconds, r.zone_warnings, r.building_warnings,
       r.fixes, r.distance_meters, r.moving_seconds, r.max_speed_mps, r.sparks, r.quests_done
FROM seed_results r
JOIN seed_games g ON g.g = r.g
JOIN seed_users u ON u.n = r.user_n
WHERE r.account
ORDER BY g.finished_at, g.g, r.s;

SELECT round(extract(epoch FROM clock_timestamp() - now())) AS s \gset
\echo '[7/8] recordings and saved routes, at' :s 's'
-- The sample's games: the most recent ones with an account (production records every such game, ADR 0011), and the
-- games of the most recent saved routes of the accounts that keep them (ADR 0007).
CREATE TEMP TABLE seed_rec_games ON COMMIT DROP AS
SELECT g.g FROM seed_games g
WHERE EXISTS (SELECT 1 FROM seed_slots s WHERE s.g = g.g AND s.account)
ORDER BY g.finished_at DESC
LIMIT :recordings;

CREATE TEMP TABLE seed_route_rows ON COMMIT DROP AS
SELECT r.g, r.s, a.id AS user_id
FROM seed_results r
JOIN seed_games g ON g.g = r.g
JOIN seed_accounts a ON a.n = r.user_n
WHERE r.account AND a.save_routes_since IS NOT NULL AND a.save_routes_since < g.finished_at
  AND g.finished_at > now() - make_interval(days => :days)
ORDER BY g.finished_at DESC
LIMIT :routes;

-- The zone schedule (ZoneSchedule) and, for half of the games, the zone by streets: one polygon per stage
-- (ZonePolygon), a ragged circle of 56 corners.
CREATE TEMP TABLE seed_zones ON COMMIT DROP AS
SELECT g.g,
       jsonb_build_object(
           'initial', jsonb_build_object('center', jsonb_build_object('lat', g.zlat, 'lon', g.zlon),
                                         'radiusMeters', g.zone_radius),
           'stages', coalesce((
               SELECT jsonb_agg(jsonb_build_object(
                          'holdSeconds', 300, 'shrinkSeconds', 120,
                          'target', jsonb_build_object(
                              'center', jsonb_build_object(
                                  'lat', g.zlat + g.zone_radius / 111320.0 * 0.08 * k * sin(g.g + k),
                                  'lon', g.zlon + g.zone_radius / 111320.0 * 0.08 * k * cos(g.g + k)
                                      / cos(radians(g.zlat))),
                              'radiusMeters', round(g.zone_radius * (1 - 0.18 * k)))) ORDER BY k)
               FROM generate_series(1, g.zone_stages) AS k), '[]'::jsonb)) AS zone,
       CASE WHEN g.r_street < 0.5 THEN (
           SELECT jsonb_agg(jsonb_build_object('outline', (
                      SELECT jsonb_agg(jsonb_build_object(
                                 'lat', g.zlat + g.zone_radius / 111320.0 * (1 - 0.18 * k)
                                     * (0.92 + 0.08 * sin(5 * v + g.g)) * sin(v * 6.283185307 / 56),
                                 'lon', g.zlon + g.zone_radius / 111320.0 / cos(radians(g.zlat)) * (1 - 0.18 * k)
                                     * (0.92 + 0.08 * cos(7 * v + g.g)) * cos(v * 6.283185307 / 56)) ORDER BY v)
                      FROM generate_series(0, 55) AS v)) ORDER BY k)
           FROM generate_series(0, g.zone_stages) AS k) END AS street_zone
FROM seed_games g
WHERE g.g IN (SELECT g FROM seed_rec_games UNION SELECT g FROM seed_route_rows);
ALTER TABLE seed_zones ADD PRIMARY KEY (g);

INSERT INTO game_recordings (game_id, saved_at, started_at, zone_started_at, finished_at, zone, street_zone)
SELECT g.id, g.finished_at + interval '1 second', g.started_at, g.zone_started_at, g.finished_at, z.zone, z.street_zone
FROM seed_rec_games rg
JOIN seed_games g ON g.g = rg.g
JOIN seed_zones z ON z.g = rg.g;

-- Everybody's way (ReplayTrack): accurate fixes, one every 5 s, full doubles; a hider's until they were out. A walk
-- about the zone's center, inside it.
INSERT INTO game_recording_tracks (game_id, player_id, user_id, name, role, status, out_at, caught_by, points)
SELECT g.id, substr(md5('p' || r.g || ':' || r.s), 1, 12), CASE WHEN r.account THEN a.id END,
       CASE WHEN r.account THEN a.nickname
            ELSE (ARRAY['Sasha', 'Max', 'Dima', 'Оля', 'Kat', 'Tima', 'Іра', 'Lena', 'Mia', 'Nika', 'Тато', 'Мама',
                        'Bro', 'Сашко', 'Ліза', 'Guest'])[1 + floor(sl.r_name * 16)::int] END,
       CASE WHEN r.seeker THEN 'SEEKER' ELSE 'HIDER' END, r.status,
       CASE WHEN r.out_seconds IS NOT NULL THEN g.zone_started_at + make_interval(secs => r.out_seconds::float8) END,
       CASE WHEN r.caught_by_s IS NOT NULL THEN substr(md5('p' || r.g || ':' || r.caught_by_s), 1, 12) END,
       coalesce((
           SELECT jsonb_agg(jsonb_build_object(
                      'lat', g.zlat + sl.amp * g.zone_radius / 111320.0
                          * (0.65 * sin(sl.w1 * i + sl.p1) + 0.35 * sin(sl.w2 * i + sl.p2))
                          + (random() - 0.5) * 0.00003,
                      'lon', g.zlon + sl.amp * g.zone_radius / 111320.0 / cos(radians(g.zlat))
                          * (0.65 * cos(sl.w1 * i + sl.p3) + 0.35 * sin(sl.w2 * i + sl.p4))
                          + (random() - 0.5) * 0.00004,
                      'atMillis', (extract(epoch FROM g.started_at) * 1000)::bigint + i * 5000
                          + floor(random() * 900)::bigint) ORDER BY i)
           FROM generate_series(0, r.active / 5) AS i
           WHERE random() > 0.04), '[]'::jsonb)
FROM seed_rec_games rg
JOIN seed_games g ON g.g = rg.g
JOIN seed_results r ON r.g = rg.g
JOIN seed_slots sl ON sl.g = r.g AND sl.s = r.s
LEFT JOIN seed_accounts a ON a.n = r.user_n AND r.account;

-- The saved routes (RouteRecorder): coordinates to 6 decimals, the accuracy, a point every 5 s while moving and one a
-- minute while standing: a seeker's every 6 s, a hider's every 15 s on average. The same walk as the recording's.
INSERT INTO game_routes (user_id, game_id, saved_at, role, zone, started_at, zone_started_at, finished_at, points,
                         street_zone)
SELECT rr.user_id, g.id, g.finished_at + interval '2 seconds', CASE WHEN r.seeker THEN 'SEEKER' ELSE 'HIDER' END,
       z.zone, g.started_at, g.zone_started_at, g.finished_at,
       coalesce((
           SELECT jsonb_agg(jsonb_build_object(
                      'lat', round((g.zlat + sl.amp * g.zone_radius / 111320.0
                          * (0.65 * sin(sl.w1 * t / 5.0 + sl.p1) + 0.35 * sin(sl.w2 * t / 5.0 + sl.p2)))::numeric, 6),
                      'lon', round((g.zlon + sl.amp * g.zone_radius / 111320.0 / cos(radians(g.zlat))
                          * (0.65 * cos(sl.w1 * t / 5.0 + sl.p3) + 0.35 * sin(sl.w2 * t / 5.0 + sl.p4)))::numeric, 6),
                      'accuracyMeters', round((3 + random() * 12)::numeric, 1),
                      'atMillis', (extract(epoch FROM g.started_at) * 1000)::bigint + t * 1000
                          + floor(random() * 900)::bigint) ORDER BY t)
           FROM generate_series(0, r.active, CASE WHEN r.seeker THEN 6 ELSE 15 END) AS t), '[]'::jsonb),
       z.street_zone
FROM seed_route_rows rr
JOIN seed_games g ON g.g = rr.g
JOIN seed_results r ON r.g = rr.g AND r.s = rr.s
JOIN seed_slots sl ON sl.g = rr.g AND sl.s = rr.s
JOIN seed_zones z ON z.g = rr.g;

SELECT round(extract(epoch FROM clock_timestamp() - now())) AS s \gset
\echo '[8/8] chat bans, old bans, paid extras, at' :s 's'
-- Never a ban in force: every seeded account must be able to log in. The leaderboard's ban check still finds rows:
-- 0 a chat ban still on, 1 a chat ban that ended, 2 a ban that ended, 3 a ban for ever, lifted early.
INSERT INTO sanctions (user_id, kind, reason, created_by, created_at, until, lifted_at, lifted_by)
SELECT u.id,
       CASE WHEN q.kind_no < 2 THEN 'MUTE' ELSE 'BAN' END,
       (ARRAY['Образи в чаті', 'Спам у чаті', 'Нечесна гра', 'Підміна GPS'])[1 + q.kind_no],
       'admin_perf', t.at,
       CASE q.kind_no WHEN 0 THEN now() + interval '3 days'
                      WHEN 1 THEN least(now() - interval '1 second', t.at + interval '1 day')
                      WHEN 2 THEN least(now() - interval '1 second', t.at + interval '7 days') END,
       CASE WHEN q.kind_no = 3 THEN least(now(), t.at + interval '2 days') END,
       CASE WHEN q.kind_no = 3 THEN 'admin_perf' END
FROM (SELECT floor(random() * :users)::int AS n, floor(random() * 4)::int AS kind_no, random() AS r
      FROM generate_series(1, greatest(1, :users / 300))) q
JOIN seed_users u ON u.n = q.n
CROSS JOIN LATERAL (SELECT u.created_at + (now() - u.created_at) * (q.r * 0.9) AS at) t;

-- Paid extras granted in the admin (ADR 0023) for one account in two hundred.
INSERT INTO entitlements (user_id, entitlement, source, granted_at, until, granted_by)
SELECT q.id, (ARRAY['premium_host', 'map_styles', 'cosmetics'])[1 + floor(q.r * 3)::int], 'ADMIN', q.at,
       CASE WHEN q.r2 < 0.5 THEN NULL ELSE now() + interval '60 days' * q.r2 END, 'admin_perf'
FROM (SELECT u.id, u.created_at + (now() - u.created_at) * random() AS at, random() AS r, random() AS r2,
             random() AS pick
      FROM seed_users u) q
WHERE q.pick < 0.005;

SELECT round(extract(epoch FROM clock_timestamp() - now())) AS s \gset
COMMIT;
\echo 'Committed after' :s 's'

\echo 'Vacuum and statistics'
VACUUM (ANALYZE);

\echo
\echo 'Rows and sizes'
SELECT t AS "table", to_char(n, 'FM999G999G990') AS "rows",
       pg_size_pretty(pg_total_relation_size(t::regclass)) AS "total",
       pg_size_pretty(pg_relation_size(t::regclass)) AS "heap",
       pg_size_pretty(pg_indexes_size(t::regclass)) AS "indexes"
FROM (VALUES ('users', (SELECT count(*) FROM users)),
             ('account_sessions', (SELECT count(*) FROM account_sessions)),
             ('friendships', (SELECT count(*) FROM friendships)),
             ('friend_requests', (SELECT count(*) FROM friend_requests)),
             ('blocks', (SELECT count(*) FROM blocks)),
             ('user_groups', (SELECT count(*) FROM user_groups)),
             ('group_members', (SELECT count(*) FROM group_members)),
             ('played_games', (SELECT count(*) FROM played_games)),
             ('game_results', (SELECT count(*) FROM game_results)),
             ('game_routes', (SELECT count(*) FROM game_routes)),
             ('game_recordings', (SELECT count(*) FROM game_recordings)),
             ('game_recording_tracks', (SELECT count(*) FROM game_recording_tracks)),
             ('sanctions', (SELECT count(*) FROM sanctions)),
             ('entitlements', (SELECT count(*) FROM entitlements))) v (t, n)
ORDER BY pg_total_relation_size(t::regclass) DESC;

SELECT pg_size_pretty(pg_database_size(current_database())) AS "database";

\echo 'The leaderboard: this week and last week, from Monday in Kyiv; the accounts'
SELECT count(*) AS "results since last Monday",
       count(DISTINCT r.user_id) AS "players",
       count(*) FILTER (WHERE u.city = 'kyiv') AS "of them in kyiv"
FROM game_results r JOIN users u ON u.id = r.user_id
WHERE r.finished_at >= (date_trunc('week', now() AT TIME ZONE 'Europe/Kyiv') - interval '7 days')
                           AT TIME ZONE 'Europe/Kyiv';
SELECT percentile_disc(ARRAY[0.5, 0.9, 0.99, 1.0]) WITHIN GROUP (ORDER BY games) AS "games per account p50/p90/p99/max",
       round(avg(friends), 1) AS "friends per account"
FROM (SELECT u.id, (SELECT count(*) FROM game_results r WHERE r.user_id = u.id) AS games,
             (SELECT count(*) FROM friendships f WHERE f.user_id = u.id) AS friends
      FROM users u WHERE u.id ~ '^pu[0-9]{10}$') a;

\echo 'Recordings of every game of the period and every saved route (ADR 0011, 0007), by the sample'
SELECT to_char(w.games, 'FM999G999G990') AS "recorded games",
       to_char(w.ways, 'FM999G999G990') AS "ways",
       round(t.points) AS "points a way",
       pg_size_pretty(t.bytes::bigint) AS "a way",
       pg_size_pretty((w.ways * t.bytes + w.games * g.bytes)::bigint) AS "recordings, all games",
       to_char(rt.routes, 'FM999G999G990') AS "saved routes",
       pg_size_pretty(rs.bytes::bigint) AS "a route",
       pg_size_pretty((rt.routes * rs.bytes)::bigint) AS "routes, all"
FROM (SELECT count(*) AS games, sum(players) AS ways FROM played_games
      WHERE id ~ '^pg[0-9]{10}$' AND players > guests AND finished_at > now() - make_interval(days => :days)) w,
     (SELECT avg(jsonb_array_length(points)) AS points,
             pg_total_relation_size('game_recording_tracks')::numeric / greatest(1, count(*)) AS bytes
      FROM game_recording_tracks) t,
     (SELECT pg_total_relation_size('game_recordings')::numeric / greatest(1, count(*)) AS bytes
      FROM game_recordings) g,
     (SELECT count(*) AS routes FROM game_results r JOIN users u ON u.id = r.user_id
      WHERE u.save_routes_since < r.finished_at AND r.finished_at > now() - make_interval(days => :days)) rt,
     (SELECT pg_total_relation_size('game_routes')::numeric / greatest(1, count(*)) AS bytes FROM game_routes) rs;
