"""SPEC-14 neutral contracts: completeness and mapping are semantic, not only JSON shapes."""
import copy
from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'contracts'))
import check_p2

class VodContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _,cls.additions,cls.bundle,_=check_p2.load()
    def example(self,name):return copy.deepcopy(self.additions['schemas'][name]['examples'][0])
    def test_complete_index_rejects_duplicate_unknown_and_missing_variants(self):
        original=self.example('P2RecordingIndex')
        for mode in ['duplicate','unknown','empty','gap']:
            value=copy.deepcopy(original)
            if mode=='duplicate':value['segments']*=2
            elif mode=='unknown':value['segments'][0]['renditionId']='rend_unknown'
            elif mode=='empty':
                v=copy.deepcopy(value['renditions'][0]);v['renditionId']='rend_missing';value['renditions'].append(v)
            else:
                seg=copy.deepcopy(value['segments'][0]);seg.update(sequence=2,sessionStartMs=2000,discontinuity=True);value['segments'].append(seg)
            with self.subTest(mode=mode),self.assertRaises((ValueError,check_p2.ValidationError)):
                check_p2.validate_payload(self.bundle,'P2RecordingIndex',value)
    def test_compacted_timeline_cannot_invent_duration_or_overlap(self):
        for field in ['durationMs','mediaStartMs']:
            value=self.example('P2Timeline')
            if field=='durationMs':value[field]+=100
            else:value['intervals'][0][field]=100
            with self.assertRaises(ValueError):check_p2.validate_payload(self.bundle,'P2Timeline',value)
    def test_event_identity_and_private_bytes_are_bound(self):
        value=self.example('P2EventRecordingSegmentAvailable');value['sequence']+=1
        with self.assertRaises(ValueError):check_p2.validate_payload(self.bundle,'P2EventRecordingSegmentAvailable',value,event=True)
        value=self.example('P2EventRecordingClosed');value['payload']['index']['presignedUrl']='https://private.invalid'
        with self.assertRaises((ValueError,check_p2.ValidationError)):check_p2.validate_payload(self.bundle,'P2EventRecordingClosed',value,event=True)
    def test_replay_window_and_operation_state_remain_explicit(self):
        with self.assertRaises(ValueError):check_p2.validate_payload(self.bundle,'P2ReplayQuery',{'fromMs':100,'toMs':100})
        value=self.example('P2OperationStatus');self.assertEqual(value['replayCleanup'],'DELEGATED_TO_CHAT')
        providers={op['id']:op.get('status') for op in self.additions['operations']}
        self.assertEqual(providers['p2.chat.replay'],'contract-only')
        self.assertEqual(providers['p2.vod.timeline'],'implemented')
    def test_preparing_prefix_keeps_live_clock_nonnegative(self):
        value=self.example('P2Segment');value.update(sessionStartMs=-500,sourceGeneration=7)
        check_p2.validate_payload(self.bundle,'P2Segment',value)
        value['discontinuity']=True
        with self.assertRaises(ValueError):check_p2.validate_payload(self.bundle,'P2Segment',value)
        timeline=self.example('P2Timeline');timeline['intervals'][0]['mediaStartMs']=500;timeline['durationMs']+=500
        check_p2.validate_payload(self.bundle,'P2Timeline',timeline)
    def test_recording_binding_extends_only_private_authorization_response(self):
        extension=next(item for item in self.additions['protocolExtensions'] if item['operation']=='media.auth')
        self.assertEqual(extension['visibility'],'private')
        self.assertEqual(set(extension['headers'].values()),{'sessionId','sourceGeneration'})
        value=self.example(extension['schema']);value['sessionId']='live/sk_fixture'
        with self.assertRaises(check_p2.ValidationError):check_p2.validate_payload(self.bundle,extension['schema'],value)
