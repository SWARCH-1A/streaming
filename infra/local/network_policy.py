"""Executable network boundaries of the complete local deployment (ADR-030)."""
from pathlib import Path
import json
import os
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
MANIFESTS = ('compose.yaml', 'infra/broker/compose.yaml', 'infra/audit/compose.yaml')
STORES = {
    'core-db': ('core-data', {'core'}),
    'live-db': ('streaming-data', {'live'}),
    'chat-db': ('chat-cache', {'chat'}),
    'chat-pg': ('chat-data', {'chat'}),
    'vod-db': ('vod-data', {'vod'}),
    'notifications-db': ('notifications-data', {'notifications'}),
    'audit-db': ('audit-data', {'audit'}),
    'minio': ('vod-storage', {'vod', 'media-recorder', 'minio-init'}),
}
GROUPS = {
    'default': {'proxy', 'gateway', 'web', 'core', 'live', 'chat', 'vod', 'notifications'},
    'messaging': {'broker', 'core', 'live', 'chat', 'vod', 'notifications', 'media-recorder', 'broker-ops'},
    'audit-messaging': {'audit', 'broker'},
    # ADR-032: the WAF relay reaches only the broker, never another producer's API.
    'waf-messaging': {'waf-relay', 'broker'},
    'audit-query': {'core', 'audit'},
    'media': {'live', 'media-server', 'media-recorder', 'vod'},
    'smtp': {'notifications', 'mailpit'},
    # ADR-031: the WAF is the public entry and shares a network only with Caddy.
    'edge': {'waf', 'proxy'},
}
PUBLIC = {'waf': {8443}, 'media-server': {1936}, 'mailpit': {8025}}


def networks(service):
    return set(service.get('networks') or ())


def validate(model):
    """Fail on missing, over-broad, implicitly default or host-attached services."""
    services, definitions = model['services'], model['networks']
    expected = dict(GROUPS)
    expected.update({network: owners | {store} for store, (network, owners) in STORES.items()})
    assert set(definitions) == set(expected), 'Unexpected or missing network'
    for name, service in services.items():
        assert networks(service), f'{name}: implicit default network'
        assert not service.get('network_mode'), f'{name}: network_mode bypass'
        assert not service.get('privileged'), f'{name}: privileged container'
        assert not service.get('external_links'), f'{name}: external link bypass'
        ports = {int(p['target']) for p in service.get('ports', [])}
        assert ports == PUBLIC.get(name, set()), f'{name}: unexpected published ports'
        assert not any('docker.sock' in str(v.get('source', '')) for v in service.get('volumes', [])), f'{name}: Docker socket bypass'
    for network, members in expected.items():
        actual = {name for name, service in services.items() if network in networks(service)}
        assert actual == members, f'{network}: membership {sorted(actual)} != {sorted(members)}'
        definition = definitions[network]
        assert definition.get('driver', 'bridge') == 'bridge', network
        assert not definition.get('external'), f'{network}: external/shared project network'
        if network not in {'default', 'media', 'smtp', 'edge'}:
            assert definition.get('internal') is True, f'{network}: requires internal isolation'
    for store, (network, _) in STORES.items():
        assert networks(services[store]) == {network}, f'{store}: datastore attached elsewhere'
    assert networks(services['waf']) == {'edge'}, 'WAF reaches more than the reverse proxy'
    # ADR-032: WAF evidence crosses only through the spool volume, never a shared network.
    assert networks(services['waf-relay']) == {'waf-messaging'}, 'WAF relay attached beyond the broker'
    spool = {name for name, service in services.items()
             if any(v.get('type') == 'volume' and v.get('source') == 'waf-audit' for v in service.get('volumes', []))}
    assert spool == {'waf', 'waf-relay'}, f'waf-audit spool shared with {sorted(spool)}'
    assert not (networks(services['gateway']) & networks(services['broker'])), 'Gateway reaches broker'
    assert not (networks(services['web']) & networks(services['broker'])), 'SSR reaches broker'
    # A common broker network would otherwise expose Audit HTTP to every producer.
    for name in {'web', 'proxy', 'gateway', 'chat', 'live', 'vod', 'notifications', 'media-recorder', 'waf-relay'}:
        assert not (networks(services[name]) & networks(services['audit'])), f'{name}: reaches private Auditor'


def configuration(state, subnet='192.168.254.0/24', project='segmentation-config', edge='192.168.253.0/24'):
    """Resolve actual manifests with fake secrets; never reads private installation state."""
    import ipaddress
    addresses, edge_addresses = ipaddress.ip_network(subnet), ipaddress.ip_network(edge)
    env = os.environ.copy()
    for manifest in MANIFESTS:
        source = (ROOT / manifest).read_text()
        for variable in re.findall(r'\$\{([A-Z_][A-Z_0-9]*)', source):
            env.pop(variable, None)
        for variable in re.findall(r'\$\{([A-Z_][A-Z_0-9]*):\?', source):
            env[variable] = 'fixture-only-012345678901234567890123456789'
    env.update(P1_STATE=str(state), LOCAL_SUBNET=subnet,
               LOCAL_DYNAMIC_RANGE=str(list(addresses.subnets(prefixlen_diff=1))[1]),
               LOCAL_PROXY_IP=str(addresses.network_address + 2), LOCAL_GATEWAY_IP=str(addresses.network_address + 3),
               LOCAL_EDGE_SUBNET=edge, LOCAL_WAF_IP=str(edge_addresses.network_address + 2),
               LOCAL_EDGE_PROXY_IP=str(edge_addresses.network_address + 3),
               WEB_ORIGIN='https://localhost:3445', COMPOSE_PROFILES='tools')
    command = ['docker', 'compose', '--env-file', os.devnull, '--project-directory', str(ROOT), '-p', project]
    for manifest in MANIFESTS:
        command += ['-f', str(ROOT / manifest)]
    result = subprocess.run([*command, 'config', '--format', 'json'], env=env, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError('Cannot resolve local Compose network configuration: ' + result.stderr[:1000])
    return json.loads(result.stdout)
