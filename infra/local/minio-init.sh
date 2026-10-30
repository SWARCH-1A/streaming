#!/bin/sh
set -eu
mc --config-dir /tmp/mc alias set storage https://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
mc --config-dir /tmp/mc mb --ignore-existing storage/streaming-vod >/dev/null
mc --config-dir /tmp/mc admin user add storage vod "$VOD_S3_PASSWORD" >/dev/null
cat >/tmp/vod-policy.json <<'JSON'
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["s3:GetBucketLocation","s3:ListBucket"],"Resource":["arn:aws:s3:::streaming-vod"]},{"Effect":"Allow","Action":["s3:PutObject","s3:GetObject","s3:DeleteObject"],"Resource":["arn:aws:s3:::streaming-vod/vod/objects/*"]}]}
JSON
mc --config-dir /tmp/mc admin policy create storage vod-storage /tmp/vod-policy.json >/dev/null
mc --config-dir /tmp/mc admin policy attach storage vod-storage --user vod >/dev/null
mc --config-dir /tmp/mc admin user add storage media-reader "$MEDIA_S3_PASSWORD" >/dev/null
cat >/tmp/media-policy.json <<'JSON'
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["s3:GetBucketLocation","s3:ListBucket"],"Resource":["arn:aws:s3:::streaming-vod"]},{"Effect":"Allow","Action":["s3:GetObject"],"Resource":["arn:aws:s3:::streaming-vod/vod/objects/*"]}]}
JSON
mc --config-dir /tmp/mc admin policy create storage media-delivery /tmp/media-policy.json >/dev/null
mc --config-dir /tmp/mc admin policy attach storage media-delivery --user media-reader >/dev/null
echo 'Private VOD bucket and limited storage identity ready'
