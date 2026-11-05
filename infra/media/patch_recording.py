"""Small, checked adaptation of MediaMTX v1.21.1's native recorder.

Callbacks run synchronously in the recording reader, independently of the live reader.
External command hooks cannot attest completion because they run asynchronously.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1])


def replace(file, before, after):
    path = root / "internal" / file
    text = path.read_text()
    if text.count(before) != 1:
        raise RuntimeError(f"MediaMTX recording adaptation no longer matches: {file}")
    path.write_text(text.replace(before, after))


replace("recorder/recorder.go", "\trestartPause time.Duration", "\tjournal *nativeJournal\n\trestartPause time.Duration")
replace("recorder/recorder.go", "\tr.terminate = make(chan struct{})", "\tr.initNativeJournal()\n\tr.terminate = make(chan struct{})")
replace("recorder/recorder.go", "\t<-r.done\n}", "\t<-r.done\n\tif r.journal != nil { r.journal.seal() }\n}")
replace("recorder/recorder.go", "\t\tcase <-r.currentInstance.done:\n\t\t\tr.currentInstance.close()", '''\t\tcase <-r.currentInstance.done:
            r.currentInstance.close()
            if r.journal != nil {
                if r.journal.retired() {
                    r.journal.fail("CAPTURE_RETIRED")
                    r.journal.seal()
                    <-r.terminate
                    return
                }
                r.journal.fail("CAPTURE_RECORDER_RESTARTED")
            }''')
replace("recorder/format_mpegts_segment.go", "\t\tif err2 == nil {", "\t\tif err == nil {")
replace("recorder/format_mpegts_segment.go", "\t\tfi, err := os.Create(s.path)", "\t\tif err := nativeRecordingSpace(s.path); err != nil { return 0, err }\n\t\tfi, err := os.Create(s.path)")
replace("recorder/format_mpegts_segment.go", "\treturn s.fi.Write(p)", "\tif err := nativeRecordingWrite(s.path, len(p)); err != nil { return 0, err }\n\treturn s.fi.Write(p)")
replace("auth/manager.go", '"github.com/bluenviron/mediamtx/internal/conf"', '"github.com/bluenviron/mediamtx/internal/conf"\n"github.com/bluenviron/mediamtx/internal/recordbinding"')
replace("auth/manager.go", '\treturn req.Credentials.User, nil\n}\n\nfunc (m *Manager) authenticateJWT',
        '\tif req.Action == conf.AuthActionPublish && req.Protocol == ProtocolRTMP && req.ID != nil { recordbinding.Remember(req.ID.String(), res.Header) }\n\treturn req.Credentials.User, nil\n}\n\nfunc (m *Manager) authenticateJWT')
replace("core/path.go", '"github.com/bluenviron/mediamtx/internal/recorder"', '"github.com/bluenviron/mediamtx/internal/recorder"\n"github.com/bluenviron/mediamtx/internal/recordbinding"')
replace("core/path.go", 'func (pa *path) startRecording() {', '''func (pa *path) startRecording(publisher ...string) {
    pathFormat := pa.conf.RecordPath
    if recordbinding.Enabled() {
        publisherID := ""
        if len(publisher) > 0 { publisherID = publisher[0] } else if pa.source != nil { publisherID = pa.source.APISourceDescribe().ID }
        var ok bool
        pathFormat, ok = recordbinding.Take(publisherID)
        if !ok { pa.Log(logger.Warn, "native recording binding unavailable"); return }
    }''')
replace("core/path.go", '\t\tPathFormat:      pa.conf.RecordPath,', '\t\tPathFormat:      pathFormat,')
replace("core/path.go", '''\tif pa.conf.Record && (!pa.conf.AlwaysAvailable || pa.conf.AlwaysAvailableRecorded) {
\t\tpa.startRecording()
\t}''', '''\tif pa.conf.Record && (!pa.conf.AlwaysAvailable || pa.conf.AlwaysAvailableRecorded) {
        if sourceDesc != nil { pa.startRecording(sourceDesc.ID) } else { pa.startRecording() }
\t}''')

# Enhanced RTMP already decodes all received tracks. Split the native readers,
# preserving the same Stream, authorization, timestamps and capacity reservation.
replace("recorder/recorder.go", '"time"', '"time"\n"github.com/bluenviron/gortsplib/v5/pkg/description"')
replace("recorder/recorder.go", '\tjournal *nativeJournal', '\tjournal *nativeJournal\n\tchildren []*Recorder\n\tselectedDesc *description.Session\n\tnativeRenditions []string')
replace("recorder/recorder.go", '\tr.initNativeJournal()', '\tif r.splitNativeRenditions() { return }\n\tr.initNativeJournal()')
replace("recorder/recorder.go", 'func (r *Recorder) Close() {', 'func (r *Recorder) Close() {\n\tif len(r.children) > 0 { for _, child := range r.children { child.Close() }; return }')
replace("recorder/format_mpegts.go", '\tfor _, media := range f.ri.stream.OrigDesc.Medias {', '\tfor _, media := range f.ri.recordingMedias() {')
replace("protocols/rtmp/to_stream.go", '"github.com/bluenviron/mediamtx/internal/stream"', '"github.com/bluenviron/mediamtx/internal/stream"\n"github.com/bluenviron/mediamtx/internal/recordbinding"\n"github.com/bluenviron/mediamtx/internal/rendition"')
replace("protocols/rtmp/to_stream.go", '\treturn medias, nil', '\tif recordbinding.Enabled() { if _, _, err := rendition.Groups(&description.Session{Medias: medias}, nil); err != nil { return nil, err } }\n\treturn medias, nil')

# One gohlslib muxer per received video; shared AAC is read independently by each.
path = root / "internal/servers/hls/muxer_instance.go"
text = path.read_text()
start = text.index('\tmi.hmuxer = &gohlslib.Muxer{')
end = text.index('\n\tmi.ctx, mi.ctxCancel', start)
text = text[:start] + '\tif err := mi.initializeRenditions(muxerDirectory); err != nil { return err }\n' + text[end:]
start = text.index('\tmi.stream.RemoveReader(mi.reader)')
end = text.index('\n\tmi.Log(logger.Debug, "instance destroyed', start)
text = text[:start] + '''\tfor i, reader := range mi.readers {
        mi.stream.RemoveReader(reader)
        mi.hmuxers[i].Close()
        if mi.hmuxers[i].Directory != "" { os.Remove(mi.hmuxers[i].Directory) }
    }
''' + text[end:]
start = text.index('func (mi *muxerInstance) runInner() error {')
end = text.index('\nfunc (mi *muxerInstance) handleRequest', start)
text = text[:start] + 'func (mi *muxerInstance) runInner() error { return mi.waitRenditions() }\n' + text[end:]
text = text.replace('\tmi.hmuxer.Handle(w, ctx.Request)', '\tmi.serveRenditions(w, ctx.Request)')
text = text.replace('\treader    *stream.Reader', '\treader    *stream.Reader\n\thmuxers []*gohlslib.Muxer\n\treaders []*stream.Reader')
for unused in ['\n\t"github.com/bluenviron/mediamtx/internal/defs"', '\n\t"github.com/bluenviron/mediamtx/internal/protocols/hls"']:
    text = text.replace(unused, '')
path.write_text(text)
