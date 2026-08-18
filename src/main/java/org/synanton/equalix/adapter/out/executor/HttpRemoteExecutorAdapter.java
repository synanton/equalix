package org.synanton.equalix.adapter.out.executor;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.synanton.equalix.domain.port.out.RemoteExecutorPort;
import org.synanton.equalix.domain.service.DispatchAckService;

/**
 * Sends task payloads to the remote executor via HTTP.
 * Fire-and-forget: completion is reported back via the TaskCompletionController webhook.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpRemoteExecutorAdapter implements RemoteExecutorPort {

    private final WebClient remoteExecutorWebClient;
    private final DispatchAckService dispatchAckService;

    @Override
    public void send(UUID taskId, byte[] payload, @Nullable byte[] previousResult) {
        byte[] body = buildBody(taskId, payload, previousResult);

        remoteExecutorWebClient.post()
            .uri("/tasks/{taskId}/execute", taskId)
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(body.length))
            .bodyValue(body)
            .retrieve()
            .toBodilessEntity()
            .doOnError(error -> log.error("Failed to send task {} to remote executor: {}", taskId, error.getMessage()))
            .subscribe(
                    response -> {
                        log.debug("Task {} accepted by remote executor: {}", taskId, response.getStatusCode());
                        if (response.getStatusCode().is2xxSuccessful()) {
                            dispatchAckService.markCommitted(taskId);
                        }
                    },
                    error -> log.error("Async error sending task {}: {}", taskId, error.getMessage())
            );
    }

    private byte[] buildBody(UUID taskId, byte[] payload, @Nullable byte[] previousResult) {
        // Simple concatenation: first 16 bytes = taskId UUID, next 4 bytes = payload length, then payload,
        // then 4 bytes = previousResult length (0 if null), then previousResult
        int prevLen = previousResult != null ? previousResult.length : 0;
        byte[] body = new byte[16 + 4 + payload.length + 4 + prevLen];
        writeUuid(taskId, body, 0);
        writeInt(payload.length, body, 16);
        System.arraycopy(payload, 0, body, 20, payload.length);
        writeInt(prevLen, body, 20 + payload.length);
        if (previousResult != null) {
            System.arraycopy(previousResult, 0, body, 24 + payload.length, prevLen);
        }
        return body;
    }

    private void writeUuid(UUID uuid, byte[] dest, int offset) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int ii = 0; ii < 8; ii++) {
            dest[offset + ii] = (byte) (msb >>> (56 - 8 * ii));
            dest[offset + 8 + ii] = (byte) (lsb >>> (56 - 8 * ii));
        }
    }

    private void writeInt(int value, byte[] dest, int offset) {
        dest[offset] = (byte) (value >>> 24);
        dest[offset + 1] = (byte) (value >>> 16);
        dest[offset + 2] = (byte) (value >>> 8);
        dest[offset + 3] = (byte) value;
    }
}
