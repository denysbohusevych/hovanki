-- The zone by streets of the game (docs/adr/0009-game-setup-glow-streets.md) with each saved route, one polygon per
-- stage ([{"outline":[{"lat":..,"lon":..}, ...]}, ...], ZonePolygon), so the route's map shows the zone the game had,
-- not only its circles. Map data, kept and deleted with the route (docs/adr/0007-game-history-and-routes.md).
-- NULL: the game played with circles, or the route was saved before this column.
ALTER TABLE game_routes ADD COLUMN street_zone jsonb;
