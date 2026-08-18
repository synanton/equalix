package org.synanton.equalix.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.jspecify.annotations.Nullable;

@Data
public class CompleteTaskRequest {

    private boolean success;

    @Nullable
    private byte[] result;

    @Nullable
    private String error;

    @JsonIgnore
    @AssertTrue(message = "error must be provided when success is false")
    public boolean isErrorPresentWhenFailed() {
        if (success) {
            return true;
        }
        return error != null && !error.isBlank();
    }
}
