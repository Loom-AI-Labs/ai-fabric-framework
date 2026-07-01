package com.ai.fabric.realapps.customerruntime.web;

import com.ai.fabric.realapps.customerruntime.service.CustomerRuntimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/customer-runtime")
@RequiredArgsConstructor
public class CustomerRuntimeController {

    private final CustomerRuntimeService service;

    @PostMapping("/records")
    public CustomerRuntimeService.SyncEvidence upsert(@RequestBody CustomerRuntimeService.DomainRecord record) {
        return service.upsertDomainRecord(record);
    }

    @DeleteMapping("/records/{id}")
    public CustomerRuntimeService.SyncEvidence delete(@PathVariable String id) {
        return service.deleteDomainRecord(id);
    }

    @GetMapping("/search")
    public List<CustomerRuntimeService.SearchHit> search(@RequestParam String tenantId,
                                                         @RequestParam(required = false) String q) {
        return service.search(tenantId, q);
    }

    @PostMapping("/actions/execute")
    public CustomerRuntimeService.ActionOutcome execute(@RequestBody CustomerRuntimeService.ActionRequest request) {
        return service.executeAction(request);
    }

    @PostMapping("/connector/availability")
    public void setConnectorAvailability(@RequestParam boolean available) {
        service.setConnectorAvailable(available);
    }
}
