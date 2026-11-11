DO
$do$
BEGIN
   IF (SELECT count(*) FROM pg_available_extensions WHERE name IN ('pg_cron', 'pg_prewarm')) = 2 THEN
      CREATE EXTENSION IF NOT EXISTS pg_prewarm WITH SCHEMA public CASCADE;
      CREATE EXTENSION IF NOT EXISTS pg_cron;

      DROP MATERIALIZED VIEW IF EXISTS mv_aggregates_index_prewarm;

      CREATE MATERIALIZED VIEW mv_aggregates_index_prewarm AS
      SELECT
         pg_prewarm(schemaname || '.' || indexname) AS blocks,
         (schemaname || '.' || indexname) AS index
      FROM pg_indexes
      WHERE schemaname = '${flyway:defaultSchema}'
      ORDER BY 1 DESC;

      PERFORM cron.schedule(
         '${flyway:defaultSchema}_mv_aggregates_index_prewarm',
         '*/15 * * * *',
         'refresh materialized view ${flyway:defaultSchema}.mv_aggregates_index_prewarm'
      );
   ELSE
      RAISE NOTICE 'pg_cron or pg_prewarm is not available, indexes are not prewarmed';
   END IF;
END
$do$;
