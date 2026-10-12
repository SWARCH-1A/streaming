-- Preserve V3 identifiers, labels and version while enforcing one type per ID.
LOCK TABLE taxonomy.categories, taxonomy.tags IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM taxonomy.categories c JOIN taxonomy.tags t ON t.id=c.id) THEN
        RAISE EXCEPTION 'Catalogue ID collision between categories and tags; reconcile existing data before retrying V4'
            USING ERRCODE='23505';
    END IF;
END;
$$;

CREATE TABLE taxonomy.value_ids (
    id VARCHAR(64) PRIMARY KEY CHECK (btrim(id) <> ''),
    kind TEXT NOT NULL CHECK (kind IN ('CATEGORY','TAG'))
);
INSERT INTO taxonomy.value_ids(id,kind)
    SELECT id,'CATEGORY' FROM taxonomy.categories
    UNION ALL SELECT id,'TAG' FROM taxonomy.tags;

CREATE FUNCTION taxonomy.register_value_id() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- AFTER INSERT runs only for rows actually inserted, including ON CONFLICT callers.
    -- The unique index arbitrates concurrent transactions; rollback releases the reservation.
    INSERT INTO taxonomy.value_ids(id,kind) VALUES (NEW.id,TG_ARGV[0]);
    RETURN NEW;
END;
$$;
CREATE TRIGGER categories_register_id AFTER INSERT ON taxonomy.categories
    FOR EACH ROW EXECUTE FUNCTION taxonomy.register_value_id('CATEGORY');
CREATE TRIGGER tags_register_id AFTER INSERT ON taxonomy.tags
    FOR EACH ROW EXECUTE FUNCTION taxonomy.register_value_id('TAG');

CREATE FUNCTION taxonomy.preserve_value_id() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP IN ('DELETE','TRUNCATE') THEN
        RAISE EXCEPTION 'Catalogue identifier reservations cannot be removed' USING ERRCODE='23514';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.kind IS DISTINCT FROM OLD.kind THEN
        RAISE EXCEPTION 'Catalogue identifiers and types are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER value_ids_preserve BEFORE UPDATE OR DELETE ON taxonomy.value_ids
    FOR EACH ROW EXECUTE FUNCTION taxonomy.preserve_value_id();
CREATE TRIGGER value_ids_preserve_catalog BEFORE TRUNCATE ON taxonomy.value_ids
    FOR EACH STATEMENT EXECUTE FUNCTION taxonomy.preserve_value_id();
