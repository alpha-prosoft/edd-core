DROP INDEX IF EXISTS ${flyway:defaultSchema}.command_store_target_breadcrumbs;


CREATE INDEX command_store_target_breadcrumbs
    ON ${flyway:defaultSchema}.command_store (request_id, target_breadcrumbs);

