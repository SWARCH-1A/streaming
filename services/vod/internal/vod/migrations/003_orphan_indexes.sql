CREATE INDEX vod_objects_orphans ON vod_objects(created_at)
 WHERE confirmed AND split_part(identity,':',2)='subtitle';
CREATE INDEX vod_assets_object ON vod_assets(vod_id,(ref->>'objectId'));
