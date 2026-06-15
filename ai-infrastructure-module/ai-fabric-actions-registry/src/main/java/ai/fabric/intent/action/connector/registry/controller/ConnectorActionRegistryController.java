package ai.fabric.intent.action.connector.registry.controller;

import ai.fabric.intent.action.connector.ConnectorActionDefinition;
import ai.fabric.intent.action.connector.registry.service.ConnectorActionRegistryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/ai/actions/registry")
@RequiredArgsConstructor
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "ai.actions.db", name = "enabled", havingValue = "true")
public class ConnectorActionRegistryController {

    private final ConnectorActionRegistryService registryService;

    @GetMapping
    public ResponseEntity<List<ConnectorActionDefinition>> list() {
        return ResponseEntity.ok(registryService.list());
    }

    @PostMapping
    public ResponseEntity<ConnectorActionDefinition> register(@Valid @RequestBody ConnectorActionDefinition definition) {
        ConnectorActionDefinition saved = registryService.register(definition);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @DeleteMapping("/{actionName}")
    public ResponseEntity<Void> deregister(@PathVariable String actionName) {
        registryService.deregister(actionName);
        return ResponseEntity.noContent().build();
    }
}

