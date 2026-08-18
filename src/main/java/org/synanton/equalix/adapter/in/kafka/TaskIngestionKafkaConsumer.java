package org.synanton.equalix.adapter.in.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.synanton.equalix.domain.port.in.TaskIngestionPort;

import java.math.BigDecimal;

/**
 * Consumes raw task messages from Kafka and hands them off to the ingestion port.
 * Uses manual acknowledgment (AckMode.MANUAL_IMMEDIATE) for at-least-once delivery.
 * Empty payloads are acknowledged and dropped. Processing failures are not acknowledged
 * so the record is redelivered.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskIngestionKafkaConsumer {

    private final TaskIngestionPort ingestionPort;

    @KafkaListener(
        topics = "${app.kafka.topics.ingestion}",
        groupId = "${spring.kafka.consumer.group-id}"
    )
    public void consume(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment) {
        try {
            String fairnessKey = record.key() != null ? record.key() : "default";
            byte[] payload = record.value();

            if (payload == null || payload.length == 0) {
                log.warn("Received empty payload from Kafka topic={} partition={} offset={}",
                    record.topic(), record.partition(), record.offset());
                acknowledgment.acknowledge();
                return;
            }

            ingestionPort.createTask(fairnessKey, new BigDecimal("1.0"), payload, false, null, null, false);
            acknowledgment.acknowledge();

            log.debug("Ingested Kafka task for fairnessKey={} offset={}", fairnessKey, record.offset());
        } catch (Exception ex) {
            log.error("Failed to process Kafka message topic={} partition={} offset={}: {}",
                record.topic(), record.partition(), record.offset(), ex.getMessage(), ex);
        }
    }
}
