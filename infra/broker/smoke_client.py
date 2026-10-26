"""AMQP assertions executed inside the disposable broker operator image."""
import json
import logging
import os
from pathlib import Path
import ssl
import subprocess
import sys

import pika

logging.getLogger("pika").setLevel(logging.CRITICAL)
FIXTURE = json.loads(Path("/fixture.json").read_text())
EVENT = FIXTURE["event"]
BODY = json.dumps(EVENT, sort_keys=True).encode()


def connect(role="operator", context=None, hostname="broker"):
    context = context or ssl.create_default_context(cafile="/run/secrets/ca.crt")
    return pika.BlockingConnection(pika.ConnectionParameters(
        host="broker", port=5671, virtual_host="streaming-p2",
        credentials=pika.PlainCredentials(role, os.environ["BROKER_" + role.upper().replace("-", "_") + "_PASSWORD"]),
        ssl_options=pika.SSLOptions(context, hostname), heartbeat=30,
        socket_timeout=3, stack_timeout=5, blocked_connection_timeout=5))


def operation(action, *args, expected=0):
    result = subprocess.run([sys.executable, "/broker_ops.py", action, *args],
                            capture_output=True, text=True, timeout=15)
    if result.returncode != expected:
        raise AssertionError("broker operation failed: " + action)
    return result.stdout


def publish():
    for context, hostname in ((ssl.create_default_context(), "broker"),
                              (ssl.create_default_context(cafile="/run/secrets/ca.crt"), "wrong.invalid")):
        try:
            connection = connect(context=context, hostname=hostname)
        except (pika.exceptions.AMQPConnectionError, ssl.SSLError):
            pass
        else:
            connection.close()
            raise AssertionError("TLS accepted an untrusted CA or wrong hostname")
    for action in (lambda channel: channel.basic_publish("p2.events", "account.status.changed.v1", b"{}", mandatory=True),
                   lambda channel: channel.basic_get("notifications.recovery.email", auto_ack=False)):
        connection = connect("vod")
        try:
            channel = connection.channel()
            channel.confirm_delivery()
            try:
                action(channel)
            except pika.exceptions.ChannelClosedByBroker as error:
                assert error.reply_code == 403
            else:
                raise AssertionError("VOD was allowed to access another domain's messages")
        finally:
            connection.close()
    connection = connect("streaming")
    try:
        channel = connection.channel()
        channel.confirm_delivery()
        channel.basic_publish("p2.events", "stream.session.changed.v1.update", BODY,
                              pika.BasicProperties(content_type="application/json", delivery_mode=2,
                                                   message_id=EVENT["eventId"]), mandatory=True)
    finally:
        connection.close()
    print("PASS: verified TLS, negative ACL and confirmed publication of a P2 lifecycle envelope")


def verify():
    connection = connect()
    try:
        channel = connection.channel()
        channel.confirm_delivery()
        for queue in ("core.lifecycle", "chat.lifecycle", "vod.lifecycle"):
            assert channel.queue_declare(queue, passive=True).method.message_count == 1
        assert channel.queue_declare("notifications.live", passive=True).method.message_count == 0
        for queue in ("core.lifecycle", "chat.lifecycle"):
            method, properties, body = channel.basic_get(queue, auto_ack=False)
            assert method and body == BODY and properties.delivery_mode == 2
            channel.basic_ack(method.delivery_tag)

        def dead_letter():
            channel.basic_publish("core.lifecycle.dead", "failed", BODY,
                                  pika.BasicProperties(content_type="application/json", delivery_mode=2,
                                                       message_id=EVENT["eventId"], headers={
                                                           "x-p2-consumer": "core.lifecycle", "x-p2-attempt": 4,
                                                           "x-p2-cause": "SMOKE_TEST"}), mandatory=True)

        target = ("--subscription", "core.lifecycle")
        selected = (*target, "--event-id", EVENT["eventId"], "--reason", "Disposable broker regression")
        dead_letter()
        inspected = json.loads(operation("inspect", *target))
        assert inspected["eventId"] == EVENT["eventId"] and inspected["attempts"] == 4
        operation("redrive", *target, "--event-id", "different_id", "--reason", "Wrong head", expected=1)
        assert channel.queue_declare("core.lifecycle.dlq", passive=True).method.message_count == 1
        operation("redrive", *selected)
        method, properties, body = channel.basic_get("core.lifecycle", auto_ack=False)
        assert method and body == BODY and properties.headers["x-p2-attempt"] == 0
        channel.basic_ack(method.delivery_tag)
        assert channel.queue_declare("chat.lifecycle", passive=True).method.message_count == 0
        dead_letter()
        operation("close", *selected)
        assert channel.queue_declare("core.lifecycle.dlq", passive=True).method.message_count == 0
        audit = [json.loads(line) for line in Path("/audit/operations.jsonl").read_text().splitlines()]
        assert [entry["action"] for entry in audit] == ["redrive", "close"]
        assert all(entry["eventId"] == EVENT["eventId"] for entry in audit)
        metrics = [json.loads(line) for line in operation("metrics").splitlines()]
        assert {queue["name"] for queue in metrics} == set(FIXTURE["queues"])
    finally:
        connection.close()
    print("PASS: durable independent copies, routing, DLQ inspect/redrive/close, audit and queue metrics")


if __name__ == "__main__":
    {"publish": publish, "verify": verify}[sys.argv[1]]()
