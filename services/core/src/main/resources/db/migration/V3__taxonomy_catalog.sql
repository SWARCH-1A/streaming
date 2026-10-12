-- Extend Core without rewriting applied migrations or owning StreamConfig associations.
CREATE SCHEMA IF NOT EXISTS taxonomy;

CREATE TABLE taxonomy.catalog_state (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    catalog_version BIGINT NOT NULL CHECK (catalog_version >= 1)
);

CREATE TABLE taxonomy.categories (
    id VARCHAR(64) PRIMARY KEY CHECK (btrim(id) <> ''),
    name TEXT NOT NULL CHECK (btrim(name) <> ''),
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE taxonomy.tags (
    id VARCHAR(64) PRIMARY KEY CHECK (btrim(id) <> ''),
    name TEXT NOT NULL CHECK (btrim(name) <> ''),
    active BOOLEAN NOT NULL DEFAULT TRUE
);

-- Fixed opaque IDs: the seed is one initial catalogue at version 1, not fifteen edits.
INSERT INTO taxonomy.catalog_state(singleton,catalog_version) VALUES (TRUE,1);
INSERT INTO taxonomy.categories(id,name,active) VALUES
    ('cat_00000000000000000000000000000001','Conversación',TRUE),
    ('cat_00000000000000000000000000000002','Videojuegos',TRUE),
    ('cat_00000000000000000000000000000003','Música',TRUE),
    ('cat_00000000000000000000000000000004','Arte',TRUE),
    ('cat_00000000000000000000000000000005','Educación',TRUE),
    ('cat_00000000000000000000000000000006','Ciencia y tecnología',TRUE),
    ('cat_00000000000000000000000000000007','Deportes',TRUE);
INSERT INTO taxonomy.tags(id,name,active) VALUES
    ('tag_00000000000000000000000000000001','Español',TRUE),
    ('tag_00000000000000000000000000000002','Inglés',TRUE),
    ('tag_00000000000000000000000000000003','Educativo',TRUE),
    ('tag_00000000000000000000000000000004','Competitivo',TRUE),
    ('tag_00000000000000000000000000000005','Casual',TRUE),
    ('tag_00000000000000000000000000000006','Principiantes',TRUE),
    ('tag_00000000000000000000000000000007','Programación',TRUE),
    ('tag_00000000000000000000000000000008','IRL',TRUE);

CREATE FUNCTION taxonomy.preserve_catalog_value() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP IN ('DELETE','TRUNCATE') THEN
        RAISE EXCEPTION 'Catalogue values must be deactivated, not removed' USING ERRCODE='23514';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id THEN
        RAISE EXCEPTION 'Catalogue identifiers are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION taxonomy.advance_catalog_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' THEN
        IF NEW.name IS NOT DISTINCT FROM OLD.name AND NEW.active IS NOT DISTINCT FROM OLD.active THEN
            RETURN NEW;
        END IF;
    END IF;
    UPDATE taxonomy.catalog_state SET catalog_version=catalog_version+1 WHERE singleton=TRUE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Catalogue version is missing' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER categories_preserve_value BEFORE UPDATE OR DELETE ON taxonomy.categories
    FOR EACH ROW EXECUTE FUNCTION taxonomy.preserve_catalog_value();
CREATE TRIGGER tags_preserve_value BEFORE UPDATE OR DELETE ON taxonomy.tags
    FOR EACH ROW EXECUTE FUNCTION taxonomy.preserve_catalog_value();
CREATE TRIGGER categories_preserve_catalog BEFORE TRUNCATE ON taxonomy.categories
    FOR EACH STATEMENT EXECUTE FUNCTION taxonomy.preserve_catalog_value();
CREATE TRIGGER tags_preserve_catalog BEFORE TRUNCATE ON taxonomy.tags
    FOR EACH STATEMENT EXECUTE FUNCTION taxonomy.preserve_catalog_value();
CREATE TRIGGER categories_catalog_version AFTER INSERT OR UPDATE ON taxonomy.categories
    FOR EACH ROW EXECUTE FUNCTION taxonomy.advance_catalog_version();
CREATE TRIGGER tags_catalog_version AFTER INSERT OR UPDATE ON taxonomy.tags
    FOR EACH ROW EXECUTE FUNCTION taxonomy.advance_catalog_version();

-- Published read contracts include tombstones. Public options filter active=true;
-- readers displaying existing associations retain inactive IDs and their last names.
CREATE VIEW taxonomy.public_categories AS SELECT id,name,active FROM taxonomy.categories;
CREATE VIEW taxonomy.public_tags AS SELECT id,name,active FROM taxonomy.tags;
REVOKE INSERT,UPDATE,DELETE ON taxonomy.public_categories,taxonomy.public_tags FROM PUBLIC;
