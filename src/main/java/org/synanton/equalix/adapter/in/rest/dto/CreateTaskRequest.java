package org.synanton.equalix.adapter.in.rest.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;
import org.jspecify.annotations.Nullable;

@Data
public class CreateTaskRequest {

    @NotBlank
    private String fairnessKey;

    @Positive
    private BigDecimal weight = new BigDecimal("1.0");

    @NotNull
    private byte[] payload;

    private boolean sequential;

    @Nullable
    private Long sequenceNumber;

    @Nullable
    private UUID dependsOnTaskId;

    private boolean requiresPreviousResult;

    @AssertTrue(message = "sequenceNumber is required when sequential is true")
    public boolean isSequenceNumberPresentWhenSequential() {
        return !sequential || sequenceNumber != null;
    }
}
