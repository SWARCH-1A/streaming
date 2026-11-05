"""Run the same profile vectors used by the executable VOD provider."""
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'contracts'))
from webvtt import InvalidTrack, validate_webvtt


class ProviderCorpus(unittest.TestCase):
    def test_shared_vectors(self):
        cases = json.loads((ROOT / 'services/vod/internal/vod/testdata/webvtt-profile.json').read_text(encoding='utf-8'))
        for case in cases:
            with self.subTest(case=case['name']):
                try:
                    validate_webvtt(case['text'].encode('utf-8'), duration_ms=case['durationMs'])
                    accepted = True
                except InvalidTrack:
                    accepted = False
                self.assertEqual(accepted, case['valid'])
