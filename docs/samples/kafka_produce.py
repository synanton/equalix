#!/usr/bin/env python3
"""Produce one Equalix Kafka ingest record: key=fairnessKey, value=raw payload."""

from __future__ import annotations

import sys

try:
    from kafka import KafkaProducer
except ImportError as error:
    sys.stderr.write("Install kafka-python: pip install kafka-python\n")
    raise SystemExit(1) from error


def main() -> None:
    fairness_key = sys.argv[1] if len(sys.argv) > 1 else "tenant-kafka"
    payload = (sys.argv[2] if len(sys.argv) > 2 else "hello-kafka").encode("utf-8")
    brokers = sys.argv[3] if len(sys.argv) > 3 else "localhost:9092"
    topic = sys.argv[4] if len(sys.argv) > 4 else "equalix-tasks"

    producer = KafkaProducer(bootstrap_servers=brokers)
    future = producer.send(topic, key=fairness_key.encode("utf-8"), value=payload)
    metadata = future.get(timeout=10)
    producer.flush()
    print(f"sent topic={metadata.topic} partition={metadata.partition} offset={metadata.offset}")


if __name__ == "__main__":
    main()
