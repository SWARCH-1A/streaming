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
