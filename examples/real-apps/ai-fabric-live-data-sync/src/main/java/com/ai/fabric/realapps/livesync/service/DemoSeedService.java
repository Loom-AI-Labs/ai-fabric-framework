package com.ai.fabric.realapps.livesync.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DemoSeedService {

    private final SyncProductService productService;
    private final SyncPolicyService policyService;
    private final SyncGuideService guideService;

    public void seed(String workspaceId) {
        productService.createProduct(
            workspaceId,
            "novabook-air",
            "NovaBook Air",
            "A lightweight 14-inch notebook for mobile teams.",
            "The standard battery is rated for 18 hours of mixed office use. It includes 16 GB memory and a 512 GB SSD.",
            "Laptops",
            new BigDecimal("1299.00"),
            "PUBLISHED"
        );
        productService.createProduct(
            workspaceId,
            "echobuds-pro",
            "EchoBuds Pro",
            "Wireless earbuds designed for calls, travel, and focused work.",
            "Adaptive noise cancellation runs for 8 hours per charge. The charging case provides 24 additional hours.",
            "Audio",
            new BigDecimal("219.00"),
            "PUBLISHED"
        );

        policyService.createPolicy(
            workspaceId,
            "opened-electronics-return",
            "Opened electronics return window",
            "Opened electronics may be returned within 21 days of delivery when complete, undamaged, and supplied with original accessories.",
            "Retail customers",
            "ACTIVE",
            LocalDate.of(2026, 7, 1)
        );
        policyService.createPolicy(
            workspaceId,
            "expedited-shipping",
            "Expedited shipping cut-off",
            "In-stock orders placed before 14:00 local warehouse time qualify for same-day dispatch. Orders after the cut-off dispatch the next business day.",
            "Online customers",
            "ACTIVE",
            LocalDate.of(2026, 7, 1)
        );

        guideService.createGuide(
            workspaceId,
            "amber-synclight",
            "Amber SyncLight recovery",
            "The desk hub SyncLight flashes amber and devices stop pairing.",
            "Disconnect power, hold the reset control for 8 seconds, reconnect power, and wait for a steady blue SyncLight before pairing again.",
            "Desk Hub",
            "MEDIUM"
        );
        guideService.createGuide(
            workspaceId,
            "account-export",
            "Export account records",
            "A customer needs a portable copy of invoices and account activity.",
            "Open Account Settings, choose Data Export, select invoices and activity, then request the encrypted archive. The download link remains valid for 48 hours.",
            "Customer Account",
            "LOW"
        );
    }
}
