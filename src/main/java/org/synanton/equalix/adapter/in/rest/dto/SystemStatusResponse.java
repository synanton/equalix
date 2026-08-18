package org.synanton.equalix.adapter.in.rest.dto;

import lombok.Value;

@Value
public class SystemStatusResponse {

    long inFlight;
    double currentRps;
}
