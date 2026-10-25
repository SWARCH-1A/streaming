"""Contract/profile evidence only: no VOD provider or media player is simulated."""
import copy
import sys
import unittest
from pathlib import Path
from jsonschema import ValidationError

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'contracts'))
from check_p2 import schema_bundle, validate, verify
from webvtt import InvalidTrack, MAX_BYTES, MAX_CUES, validate_webvtt

FIXTURES = Path(__file__).parent / 'fixtures/subtitles'


def vtt(body):
    return ('WEBVTT\n\n' + body).encode('utf-8')


class SubtitleContractsTest(unittest.TestCase):
    def setUp(self):
        self.source, self.schema = schema_bundle()
        self.metadata = copy.deepcopy(self.source['schemas']['P2SubtitleUploadMetadata']['examples'][0])
        self.track = copy.deepcopy(self.source['schemas']['P2SubtitleTrack']['examples'][0])

    def test_examples_and_generated_source_remain_valid(self):
        verify(self.source, self.schema)

    def test_metadata_accepts_language_and_script_region_profile(self):
        for language in ['es', 'en-US', 'zh-Hant-TW', 'es-419']:
            validate(self.schema, 'P2SubtitleUploadMetadata', {**self.metadata, 'language': language})

    def test_invalid_metadata_and_claimed_ownership_are_rejected(self):
        for change in [{'language': 'es_CO'}, {'label': '   '}, {'label': 'x\ny'},
                       {'label': 'x' * 81}, {'expectedMappingVersion': 0},
                       {'expectedMappingVersion': 9007199254740992}, {'ownerUserId': 'usr_foreign'}]:
            with self.subTest(change=change), self.assertRaises((ValueError, ValidationError)):
                validate(self.schema, 'P2SubtitleUploadMetadata', {**self.metadata, **change})

    def test_paths_cannot_reference_another_vod_or_external_object(self):
        for path in ['/vod-media/another/subtitles/sub_fixture.vtt', 'https://bucket.example.test/a',
                     '/vod-media/vod_fixture/subtitles/../a.vtt']:
            with self.subTest(path=path), self.assertRaises((ValueError, ValidationError)):
                validate(self.schema, 'P2SubtitleTrack', {**self.track, 'contentPath': path})

    def test_list_rejects_duplicate_tracks_and_mixed_vods(self):
        for second in [self.track, {**self.track, 'trackId': 'sub_other', 'vodId': 'vod_other',
                                   'contentPath': '/vod-media/vod_other/subtitles/sub_other.vtt'}]:
            with self.assertRaises(ValueError):
                validate(self.schema, 'P2SubtitlePage', {'items': [self.track, second], 'nextCursor': None})

    def test_routes_are_explicitly_unimplemented_and_owned_by_vod(self):
        operations = [op for op in self.source['operations'] if op['id'].startswith('p2.subtitles.')]
        self.assertEqual({op['id'].rsplit('.', 1)[1] for op in operations}, {'upload', 'list', 'read', 'delete'})
        for op in operations:
            self.assertEqual(op['status'], 'contract-only')
            self.assertEqual(op['provider'], 'vod')
            self.assertEqual(op['cacheControl'], 'no-store')
        for op in operations:
            if op['method'] in ['POST', 'DELETE']:
                self.assertEqual(op['auth'], 'active-owner-session')
                self.assertIn('Idempotency-Key', op['headers'])
                self.assertIn('X-CSRF-TOKEN', op['headers'])

    def test_unknown_response_schema_and_duplicate_operations_fail(self):
        for defect in ['unknown', 'duplicate']:
            source = copy.deepcopy(self.source)
            if defect == 'unknown':
                source['operations'][0]['responses']['201'] = 'MissingSchema'
            else:
                source['operations'].append(source['operations'][0])
            with self.assertRaises(ValueError):
                verify(source, self.schema)

    def test_profile_limits_match_checker(self):
        self.assertEqual(self.source['subtitleProfile']['maxBytes'], MAX_BYTES)
        self.assertEqual(self.source['subtitleProfile']['maxCues'], MAX_CUES)


class WebVttProfileTest(unittest.TestCase):
    def test_real_fixtures_two_languages_have_matching_media_offsets(self):
        tracks = [validate_webvtt((FIXTURES / name).read_bytes(), duration_ms=12000)
                  for name in ['education-es.vtt', 'education-en.vtt']]
        self.assertEqual([(c.start_ms, c.end_ms) for c in tracks[0]], [(c.start_ms, c.end_ms) for c in tracks[1]])
        self.assertEqual(tracks[0][-1].end_ms, 12000)

    def test_bom_and_all_line_terminators(self):
        for ending in ['\n', '\r\n', '\r']:
            for bom in ['', '\ufeff']:
                data = (bom + 'WEBVTT\n\n00:00.000 --> 00:01.000\nHola\n').replace('\n', ending).encode('utf-8')
                self.assertEqual(len(validate_webvtt(data, duration_ms=1000)), 1)

    def test_hour_timestamps_overlap_notes_and_multiline(self):
        data = vtt('NOTE Un comentario\n\nuno\n01:00:00.000 --> 01:00:03.000\nHola\nMundo\n\n'
                   '01:00:01.000 --> 01:00:02.000\nOtra voz\n')
        result = validate_webvtt(data, duration_ms=3603000)
        self.assertEqual(result[0].start_ms, 3600000)
        self.assertEqual(len(result), 2)

    def test_invalid_encoded_and_control_bytes_are_rejected(self):
        for data in [b'WEBVTT\n\n\xff', b'\xff\xfeW\x00', vtt('00:00.000 --> 00:01.000\nHi\x00')]:
            with self.assertRaises(InvalidTrack):
                validate_webvtt(data, duration_ms=1000)

    def test_empty_or_malformed_header_is_rejected(self):
        for data in [b'', b'WEBVTT', b'WEBVTT\n\n', b'WEBVTTwrong\n\n', b'WEBVTT -->\n\n',
                     b'WEBVTT\n00:00.000 --> 00:01.000\nHi']:
            with self.assertRaises(InvalidTrack):
                validate_webvtt(data, duration_ms=1000)

    def test_negative_reversed_out_of_range_and_unordered_timing(self):
        for body in ['-00:01.000 --> 00:01.000\nHi', '00:00.000 --> 00:00.000\nHi',
                     '00:01.000 --> 00:00.000\nHi', '00:00.000 --> 00:02.001\nHi',
                     '00:61.000 --> 00:62.000\nHi', '00:00,000 --> 00:01.000\nHi',
                     '00:01.000 --> 00:02.000\nHi\n\n00:00.000 --> 00:01.000\nEarlier']:
            with self.subTest(body=body), self.assertRaises(InvalidTrack):
                validate_webvtt(vtt(body), duration_ms=2000)

    def test_features_outside_text_profile_are_rejected(self):
        for body in ['STYLE\n::cue { color: red; }', 'REGION\nid:a',
                     '00:00.000 --> 00:01.000 align:start\nHi',
                     '00:00.000 --> 00:01.000\n<b>Hi</b>',
                     '00:00.000 --> 00:01.000\nHi &unsupported;',
                     '00:00.000 --> 00:01.000\nHi\n00:01.000 --> 00:02.000\nMissing separator',
                     '00:00.000 --> 00:01.000\n  ']:
            with self.subTest(body=body), self.assertRaises(InvalidTrack):
                validate_webvtt(vtt(body), duration_ms=2000)

    def test_negative_fixture_files(self):
        for name in ['invalid-reversed-time.vtt', 'invalid-markup.vtt']:
            with self.subTest(name=name), self.assertRaises(InvalidTrack):
                validate_webvtt((FIXTURES / name).read_bytes(), duration_ms=12000)

    def test_resource_limits_and_authoritative_duration(self):
        with self.assertRaisesRegex(InvalidTrack, 'TRACK_TOO_LARGE'):
            validate_webvtt(b'x' * (MAX_BYTES + 1), duration_ms=1000)
        with self.assertRaisesRegex(InvalidTrack, 'TOO_MANY_CUES'):
            validate_webvtt(vtt(('00:00.000 --> 00:01.000\nx\n\n') * (MAX_CUES + 1)), duration_ms=1000)
        for duration in [0, -1, True, 1.5, 9007199254740992]:
            with self.assertRaisesRegex(InvalidTrack, 'INVALID_DURATION'):
                validate_webvtt(vtt('00:00.000 --> 00:01.000\nHi'), duration_ms=duration)


if __name__ == '__main__':
    unittest.main()
