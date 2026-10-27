"""Live rendition contracts coexist with the current broker/recovery/subtitle registry."""
import copy
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "contracts"))
import check_p2


class RenditionsContractsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _, cls.additions, cls.bundle, _ = check_p2.load()

    def response(self):
        return copy.deepcopy(self.additions["schemas"]["P2Renditions"]["examples"][0])

    def test_one_and_four_received_renditions_are_valid(self):
        value = self.response()
        check_p2.validate_payload(self.bundle, "P2Renditions", value, public=True)
        source = value["renditions"][0]
        value["renditions"] = [{**source, "renditionId": f"rend_{height}", "height": height}
                               for height in (360, 480, 720, 1080)]
        check_p2.validate_payload(self.bundle, "P2Renditions", value, public=True)

    def test_empty_duplicate_or_fifth_rendition_is_rejected(self):
        value = self.response()
        source = value["renditions"][0]
        for renditions in ([], [source, source], [{**source, "renditionId": f"rend_{i}"} for i in range(5)]):
            with self.subTest(count=len(renditions)), self.assertRaises((ValueError, check_p2.ValidationError)):
                check_p2.validate_payload(self.bundle, "P2Renditions", {**value, "renditions": renditions}, public=True)

    def test_master_is_bound_to_the_requested_public_session(self):
        value = self.response()
        prefix = f"/hls/{value['sessionId']}/"
        for path in ("/hls/ses_other/index.m3u8", prefix + "../index.m3u8", prefix + "index.m3u8?key=fixture",
                     prefix + "index.m3u8#x", prefix + "%2e/index.m3u8", "https://other.test/index.m3u8"):
            with self.subTest(path=path), self.assertRaises((ValueError, check_p2.ValidationError)):
                check_p2.validate_payload(self.bundle, "P2Renditions", {**value, "masterPlaylistUri": path}, public=True)

    def test_video_only_source_does_not_require_fabricated_audio(self):
        value = self.response()
        value["renditions"][0]["audioCodec"] = None
        check_p2.validate_payload(self.bundle, "P2Renditions", value, public=True)


if __name__ == "__main__":
    unittest.main()
