// MediaMTX reads RTMP credentials from the publish query. OBS appends its stream key
// to the server, so keep the credentials in the key instead of the server field.
export function obsIngest(rtmpUrl: string, streamKey: string) {
  const separator = rtmpUrl.lastIndexOf('/');
  return {
    server: rtmpUrl.slice(0, separator),
    key: streamKey
      ? `${rtmpUrl.slice(separator + 1)}?user=publisher&pass=${encodeURIComponent(streamKey)}`
      : '',
  };
}
