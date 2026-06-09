package com.ai.fabric.realapps.chat.payments.web;

import com.ai.fabric.realapps.chat.payments.domain.Payment;
import com.ai.fabric.realapps.chat.payments.service.PaymentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @GetMapping
    public List<Payment> list(@RequestParam("userId") String userId,
                              @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return paymentService.listForUser(userId, limit);
    }

    @GetMapping("/{id}")
    public Payment get(@PathVariable long id) {
        return paymentService.get(id);
    }

    @DeleteMapping("/{id}")
    public Payment delete(@PathVariable long id) {
        return paymentService.delete(id);
    }
}
