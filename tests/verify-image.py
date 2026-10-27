#!/usr/bin/env python3
"""Verify the produced Docker-load archive contains the matching plugins."""
import gzip
import hashlib
import json
import tarfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
image = root / 'dist/trino-paimon-iceberg-0.4.0-native-arm64.docker.tar.gz'
expected = (root / 'dist/SHA256SUMS').read_text().split()[0]
with image.open('rb') as stream:
    assert hashlib.file_digest(stream, 'sha256').hexdigest() == expected
with tarfile.open(image, 'r:gz') as archive:
    manifest = json.load(archive.extractfile('manifest.json'))
    assert len(manifest) == 1
    image_manifest = manifest[0]
    config = json.load(archive.extractfile(image_manifest['Config']))
    assert config['architecture'] == 'arm64'
    assert image_manifest['RepoTags'] == ['trino-paimon-iceberg:0.4.0-native-arm64']
    last_layer = archive.extractfile(image_manifest['Layers'][-1])
    with gzip.GzipFile(fileobj=last_layer) as stream:
        with tarfile.open(fileobj=stream, mode='r|') as layer:
            found = set()
            required = {
                'opt/trino/plugin/paimon/paimon-trino-484-2.1.0.jar',
                'opt/trino/plugin/iceberg/trino-iceberg-484.jar',
                'etc/trino/catalog/paimon.properties',
                'etc/trino/catalog/iceberg.properties',
                'opt/java/bin/java',
            }
            for member in layer:
                assert "alluxio" not in member.name.lower(), member.name
                if member.name in required:
                    found.add(member.name)
            assert found == required, f'Missing: {required - found}'
print(f'ARM64 native-cache image and plugins verified: {image.name}')
