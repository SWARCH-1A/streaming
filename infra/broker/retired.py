"""Audited retirement of subscriptions that never owned a projection (ADR-027)."""
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path

RETIRED = {
    "chat.account": "account.status.changed.v1",
    "core.vod": "vod.availability.changed.v1",
}


def archive(path, queue, properties, body, reason):
    """Persist exact AMQP property bytes and body before releasing responsibility."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    record = dict(at=datetime.now(timezone.utc).isoformat(), queue=queue, reason=reason,
                  sha256=hashlib.sha256(body).hexdigest(),
                  propertiesBase64=base64.b64encode(b"".join(properties.encode())).decode(),
                  bodyBase64=base64.b64encode(body).decode())
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    with os.fdopen(fd, "a", encoding="utf-8") as target:
        target.write(json.dumps(record) + "\n")
        target.flush()
        os.fsync(target.fileno())
    directory = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def retire(channel, subscription, existing, reason, archive_path):
    if subscription not in RETIRED or not reason.strip():
        raise ValueError("Only explicitly retired subscriptions may be archived")
    names = [subscription + suffix for suffix in (".retry.1", ".retry.5", ".retry.30", ".dlq", "")]
    present = [name for name in names if name in existing]
    if not present:
        return 0
    for name in present:
        state = channel.queue_declare(name, passive=True).method
        if state.consumer_count:
            raise ValueError("Cannot retire a subscription with active consumers")
    if subscription in present:
        channel.queue_unbind(subscription, "p2.events", RETIRED[subscription])
    count = 0
    for name in present:
        while True:
            method, properties, body = channel.basic_get(name, auto_ack=False)
            if method is None:
                break
            # A lost ACK may create a second archive record; exact bytes remain recoverable.
            archive(archive_path, name, properties, body, reason)
            channel.basic_ack(method.delivery_tag)
            count += 1
            if count >= 10000:
                raise ValueError("Retirement batch limit reached; rerun to continue")
        # Quorum queues reject conditional queue.delete flags. Recheck immediately;
        # producer routes are detached and neither retired queue has a runtime.
        state = channel.queue_declare(name, passive=True).method
        if state.consumer_count or state.message_count:
            raise ValueError("Queue changed during retirement; retain and retry")
        channel.queue_delete(name)
    for suffix in (".retry", ".dead"):
        channel.exchange_delete(subscription + suffix, if_unused=True)
    return count
