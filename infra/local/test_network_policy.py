"""Check boundaries on merged Compose, including bypass regression mutations."""
import copy
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('segmentation_policy', Path(__file__).with_name('network_policy.py'))
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


class NetworkPolicy(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with tempfile.TemporaryDirectory() as state:
            cls.model = policy.configuration(state)

    def test_merged_deployment_preserves_boundaries(self):
        policy.validate(self.model)

    def test_api_zone_preserves_existing_network_configuration(self):
        # A new explicit driver/label changes Compose's network hash and makes an
        # in-place upgrade try to remove the bridge while old endpoints remain.
        network = self.model['networks']['default']
        self.assertEqual(set(network), {'name', 'ipam'})
        self.assertEqual(network['ipam']['config'], [
            {'subnet': '192.168.254.0/24', 'ip_range': '192.168.254.128/25'}])
        for name, address in (('proxy', '192.168.254.2'), ('gateway', '192.168.254.3')):
            self.assertEqual(self.model['services'][name]['networks']['default']['ipv4_address'], address)

    def test_cannot_attach_a_datastore_to_application_network(self):
        for store in policy.STORES:
            altered = copy.deepcopy(self.model)
            altered['services'][store]['networks']['default'] = {}
            with self.subTest(store=store), self.assertRaises(AssertionError):
                policy.validate(altered)

    def test_cannot_publish_a_database_or_use_host_network(self):
        for patch in ({'ports': [{'target': 5432, 'published': '5432'}]}, {'network_mode': 'host'}, {'privileged': True}):
            altered = copy.deepcopy(self.model)
            altered['services']['core-db'].update(patch)
            with self.assertRaises(AssertionError):
                policy.validate(altered)

    def test_gateway_and_auditor_cannot_join_shared_broker_plane(self):
        for service in ('gateway', 'web', 'audit'):
            altered = copy.deepcopy(self.model)
            altered['services'][service]['networks']['messaging'] = {}
            with self.subTest(service=service), self.assertRaises(AssertionError):
                policy.validate(altered)

    def test_store_network_cannot_lose_internal_flag(self):
        altered = copy.deepcopy(self.model)
        altered['networks']['core-data']['internal'] = False
        with self.assertRaises(AssertionError):
            policy.validate(altered)
