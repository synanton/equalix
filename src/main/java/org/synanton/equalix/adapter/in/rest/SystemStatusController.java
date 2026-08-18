package org.synanton.equalix.adapter.in.rest;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.synanton.equalix.adapter.in.rest.dto.SystemStatusResponse;
import org.synanton.equalix.domain.port.out.CMSProviderPort;
import org.synanton.equalix.domain.service.AdaptiveRpsController;

@RestController
@RequestMapping("/api/v1/status")
@RequiredArgsConstructor
public class SystemStatusController {

    private final CMSProviderPort cms;
    private final AdaptiveRpsController adaptiveRpsController;

    @GetMapping
    public SystemStatusResponse getStatus() {
        return new SystemStatusResponse(cms.totalInFlight(), adaptiveRpsController.getCurrentRps());
    }
}
