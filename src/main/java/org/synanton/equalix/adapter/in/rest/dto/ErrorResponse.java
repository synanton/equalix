package org.synanton.equalix.adapter.in.rest.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.jspecify.annotations.Nullable;

@Data
@AllArgsConstructor
public class ErrorResponse {

    private String code;
    private String message;
    private Instant timestamp;

    @Nullable
    private List<Map<String, String>> fieldErrors;

    public static ErrorResponse of(String code, String message, Instant timestamp) {
        return new ErrorResponse(code, message, timestamp, null);
    }
}
