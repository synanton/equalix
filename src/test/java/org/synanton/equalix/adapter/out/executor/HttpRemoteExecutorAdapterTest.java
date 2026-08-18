package org.synanton.equalix.adapter.out.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.synanton.equalix.domain.service.DispatchAckService;
import reactor.core.publisher.Mono;

class HttpRemoteExecutorAdapterTest {

    private DispatchAckService dispatchAckService;

    @BeforeEach
    void setUp() {
        dispatchAckService = mock(DispatchAckService.class);
    }

    @Test
    void shouldPostToCorrectPathWithBinaryContentType() throws Exception {
        AtomicReference<String> capturedPath = new AtomicReference<>();
        AtomicReference<String> capturedContentType = new AtomicReference<>();
        AtomicReference<String> capturedMethod = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        WebClient webClient = WebClient.builder()
            .baseUrl("http://executor.local")
            .exchangeFunction(request -> {
                capturedPath.set(request.url().getPath());
                capturedMethod.set(request.method().name());
                capturedContentType.set(request.headers().getFirst("Content-Type"));
                latch.countDown();
                return Mono.just(ClientResponse.create(HttpStatus.OK).build());
            })
            .build();

        HttpRemoteExecutorAdapter adapter = new HttpRemoteExecutorAdapter(webClient, dispatchAckService);
        UUID id = UUID.fromString("00000000-0000-0000-0000-00000000000a");

        adapter.send(id, new byte[]{1, 2, 3}, null);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        verify(dispatchAckService, timeout(1000)).markCommitted(id);
        assertThat(capturedPath.get()).isEqualTo("/tasks/" + id + "/execute");
        assertThat(capturedMethod.get()).isEqualTo("POST");
        assertThat(capturedContentType.get()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }

    @Test
    void shouldNotThrowWhenExecutorReturns5xx() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        WebClient webClient = WebClient.builder()
            .baseUrl("http://executor.local")
            .exchangeFunction(request -> {
                latch.countDown();
                return Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build());
            })
            .build();

        HttpRemoteExecutorAdapter adapter = new HttpRemoteExecutorAdapter(webClient, dispatchAckService);

        assertThatCode(() -> adapter.send(UUID.randomUUID(), new byte[]{1}, null))
            .doesNotThrowAnyException();
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shouldNotThrowWhenExecutorConnectionFails() {
        WebClient webClient = WebClient.builder()
            .baseUrl("http://executor.local")
            .exchangeFunction(request -> Mono.error(new RuntimeException("network down")))
            .build();

        HttpRemoteExecutorAdapter adapter = new HttpRemoteExecutorAdapter(webClient, dispatchAckService);

        assertThatCode(() -> adapter.send(UUID.randomUUID(), new byte[]{1}, null))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldEncodeEnvelopeWithTaskIdPayloadAndPreviousResult() {
        // Envelope layout: [16 bytes taskId][4 bytes payload len][payload][4 bytes prev len][prev]
        // This test verifies the encoding indirectly by measuring the body Content-Length reported.
        AtomicReference<Long> capturedContentLength = new AtomicReference<>();

        WebClient webClient = WebClient.builder()
            .baseUrl("http://executor.local")
            .exchangeFunction(request -> {
                capturedContentLength.set(request.headers().getContentLength());
                return Mono.just(ClientResponse.create(HttpStatus.OK).build());
            })
            .build();

        HttpRemoteExecutorAdapter adapter = new HttpRemoteExecutorAdapter(webClient, dispatchAckService);
        adapter.send(UUID.randomUUID(), new byte[]{1, 2, 3}, new byte[]{7, 8});

        // 16 (uuid) + 4 (payload len) + 3 (payload) + 4 (prev len) + 2 (prev) = 29
        assertThat(capturedContentLength.get()).isEqualTo(29L);
    }
}
