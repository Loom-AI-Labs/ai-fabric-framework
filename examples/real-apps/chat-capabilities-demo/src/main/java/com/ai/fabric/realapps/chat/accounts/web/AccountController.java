package com.ai.fabric.realapps.chat.accounts.web;

import com.ai.fabric.realapps.chat.accounts.domain.Account;
import com.ai.fabric.realapps.chat.accounts.service.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @GetMapping
    public List<Account> list(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        return accountService.list(limit);
    }

    @GetMapping("/{id}")
    public Account get(@PathVariable long id) {
        return accountService.get(id);
    }

    @GetMapping("/by-user")
    public ResponseEntity<Account> getByUserId(@RequestParam("userId") String userId) {
        return accountService.findByUserId(userId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Account> create(@Valid @RequestBody CreateAccountRequest request) {
        Account created = accountService.create(
            request.getUserId(),
            request.getName(),
            request.getEmail(),
            request.getTier(),
            request.getSegment()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public Account update(@PathVariable long id, @Valid @RequestBody UpdateAccountRequest request) {
        return accountService.update(id, request.getName(), request.getEmail(), request.getTier(), request.getSegment());
    }

    @DeleteMapping("/{id}")
    public Account delete(@PathVariable long id) {
        return accountService.delete(id);
    }

    @Data
    public static class CreateAccountRequest {
        @NotBlank
        private String userId;
        @NotBlank
        private String name;
        @NotBlank
        private String email;
        private String tier = "standard";
        private String segment = "general";
    }

    @Data
    public static class UpdateAccountRequest {
        private String name;
        private String email;
        private String tier;
        private String segment;
    }
}
