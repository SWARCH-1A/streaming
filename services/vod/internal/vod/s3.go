package vod

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"fmt"
	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
	"io"
	"net/http"
	"net/url"
	"strings"
)

type S3 struct {
	Client         *minio.Client
	Bucket, Prefix string
}

func NewS3(endpoint, bucket, prefix, region, access, secret string) (*S3, error) {
	u, e := url.Parse(endpoint)
	if e != nil || u.Host == "" || (u.Scheme != "https" && u.Scheme != "http") || u.User != nil || u.RawQuery != "" {
		return nil, fmt.Errorf("invalid S3 endpoint")
	}
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.TLSClientConfig = &tls.Config{MinVersion: tls.VersionTLS12}
	var creds *credentials.Credentials
	if access != "" {
		creds = credentials.NewStaticV4(access, secret, "")
	} else {
		creds = credentials.NewIAM("")
	}
	client, e := minio.New(u.Host, &minio.Options{Creds: creds, Secure: u.Scheme == "https", Region: region, Transport: transport})
	if e != nil {
		return nil, e
	}
	return &S3{client, bucket, strings.Trim(prefix, "/")}, nil
}
func (s *S3) key(o ObjectRef) string { return s.Prefix + "/objects/" + o.ObjectID }
func (s *S3) Put(ctx context.Context, o ObjectRef, r io.Reader) error {
	if !o.Valid() {
		return Fail(422, "OBJECT_INVALID")
	}
	_, e := s.Client.PutObject(ctx, s.Bucket, s.key(o), r, o.ByteLength, minio.PutObjectOptions{ContentType: o.ContentType, UserMetadata: map[string]string{"sha256": o.SHA256}, DisableMultipart: true})
	if e != nil {
		return Fail(503, "S3_UNAVAILABLE")
	}
	return nil
}
func (s *S3) Get(ctx context.Context, o ObjectRef, rangeHeader string) (io.ReadCloser, int64, error) {
	opts := minio.GetObjectOptions{}
	size := o.ByteLength
	if rangeHeader != "" {
		start, end, e := byteRange(rangeHeader, size)
		if e != nil {
			return nil, 0, e
		}
		if e = opts.SetRange(start, end); e != nil {
			return nil, 0, Fail(416, "RANGE_INVALID")
		}
		size = end - start + 1
	}
	obj, e := s.Client.GetObject(ctx, s.Bucket, s.key(o), opts)
	if e != nil {
		return nil, 0, Fail(503, "S3_UNAVAILABLE")
	}
	if info, err := s.Client.StatObject(ctx, s.Bucket, s.key(o), minio.StatObjectOptions{}); err != nil || info.Size != o.ByteLength {
		obj.Close()
		return nil, 0, Fail(503, "OBJECT_MISSING")
	}
	return obj, size, nil
}
func (s *S3) Verify(ctx context.Context, o ObjectRef) error {
	info, e := s.Client.StatObject(ctx, s.Bucket, s.key(o), minio.StatObjectOptions{})
	if e != nil {
		return Fail(503, "OBJECT_MISSING")
	}
	if info.Size != o.ByteLength || info.ContentType != o.ContentType {
		return Fail(422, "OBJECT_METADATA_CONFLICT")
	}
	body, _, e := s.Get(ctx, o, "")
	if e != nil {
		return e
	}
	defer body.Close()
	h := sha256.New()
	n, e := io.Copy(h, io.LimitReader(body, o.ByteLength+1))
	if e != nil {
		return Fail(503, "S3_UNAVAILABLE")
	}
	if n != o.ByteLength || hex.EncodeToString(h.Sum(nil)) != o.SHA256 {
		return Fail(422, "OBJECT_CHECKSUM_CONFLICT")
	}
	return nil
}
func (s *S3) Delete(ctx context.Context, o ObjectRef) error {
	e := s.Client.RemoveObject(ctx, s.Bucket, s.key(o), minio.RemoveObjectOptions{})
	if e != nil {
		return Fail(503, "S3_UNAVAILABLE")
	}
	return nil
}
func (s *S3) Health(ctx context.Context) error {
	exists, e := s.Client.BucketExists(ctx, s.Bucket)
	if e != nil || !exists {
		return Fail(503, "S3_UNAVAILABLE")
	}
	return nil
}
