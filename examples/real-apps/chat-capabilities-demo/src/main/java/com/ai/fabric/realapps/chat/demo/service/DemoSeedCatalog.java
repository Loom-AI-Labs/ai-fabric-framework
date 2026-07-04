package com.ai.fabric.realapps.chat.demo.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

final class DemoSeedCatalog {

    private static final List<ProductSeed> BASE_PRODUCTS = List.of(
        new ProductSeed("SKU-LAP-9001", "Alienware M18 R2 Gaming Laptop",
            "High performance 18 inch gaming laptop with Intel Core i9, RTX graphics, 32GB memory, 1TB SSD, vapor chamber cooling, and 480Hz display.",
            "Laptops", "gaming,laptop,rtx,high performance,esports", "https://images.unsplash.com/photo-1603302576837-37561b2e2302?auto=format&fit=crop&w=900&q=80", "2499.00", 17),
        new ProductSeed("SKU-LAP-9002", "Surface Laptop 6 Pro",
            "Portable productivity laptop with fast processor, long battery life, 16GB memory, 512GB SSD, and premium keyboard for work and study.",
            "Laptops", "business,laptop,portable,productivity", "https://images.unsplash.com/photo-1496181133206-80ce9b88a853?auto=format&fit=crop&w=900&q=80", "1299.00", 36),
        new ProductSeed("SKU-LAP-9003", "Razer Blade 16 Studio",
            "Creator and gaming laptop with calibrated OLED display, RTX graphics, 32GB memory, 2TB SSD, and quiet performance profile.",
            "Laptops", "creator,gaming,laptop,oLED,studio", "https://images.unsplash.com/photo-1593642702749-b7d2a804fbcf?auto=format&fit=crop&w=900&q=80", "2899.00", 11),
        new ProductSeed("SKU-PHN-8101", "Xiaomi 14 Ultra",
            "Flagship phone with advanced camera system, bright AMOLED display, fast charging, and 512GB storage.",
            "Phones", "phone,camera,android,flagship", "https://images.unsplash.com/photo-1511707171634-5f897ff02aa9?auto=format&fit=crop&w=900&q=80", "1099.00", 54),
        new ProductSeed("SKU-PHN-8102", "Pixel 9 Pro",
            "AI-forward phone with excellent camera quality, clean Android experience, secure updates, and all-day battery.",
            "Phones", "phone,ai,camera,android", "https://images.unsplash.com/photo-1598327105666-5b89351aff97?auto=format&fit=crop&w=900&q=80", "999.00", 47),
        new ProductSeed("SKU-AUD-7201", "Sony WH-1000XM6 Headphones",
            "Wireless noise cancelling headphones with rich sound, multipoint Bluetooth, long battery life, and travel case.",
            "Headphones", "audio,headphones,noise cancelling,wireless", "https://images.unsplash.com/photo-1505740420928-5e560c06d30e?auto=format&fit=crop&w=900&q=80", "399.00", 89),
        new ProductSeed("SKU-AUD-7202", "Studio Buds Pro",
            "Compact earbuds with active noise cancellation, transparency mode, wireless charging case, and water resistance.",
            "Headphones", "earbuds,audio,wireless,commute", "https://images.unsplash.com/photo-1606220588913-b3aacb4d2f46?auto=format&fit=crop&w=900&q=80", "179.00", 132),
        new ProductSeed("SKU-MON-6101", "Odyssey 32 Inch 4K Gaming Monitor",
            "32 inch 4K monitor with high refresh rate, low input lag, HDR, and adaptive sync for gaming and productivity.",
            "Monitors", "monitor,4k,gaming,hdr,display", "https://images.unsplash.com/photo-1527443224154-c4a3942d3acf?auto=format&fit=crop&w=900&q=80", "699.00", 28),
        new ProductSeed("SKU-MON-6102", "CreatorView 27 Inch Color Monitor",
            "27 inch color accurate monitor with USB-C docking, factory calibration, wide gamut, and ergonomic stand.",
            "Monitors", "monitor,creator,color,usb-c,design", "https://images.unsplash.com/photo-1593640408182-31c70c8268f5?auto=format&fit=crop&w=900&q=80", "549.00", 42),
        new ProductSeed("SKU-ACC-5001", "Thunderbolt Pro Dock",
            "Compact Thunderbolt dock with multiple display outputs, Ethernet, USB-C charging, SD reader, and fast device connectivity.",
            "Accessories", "dock,thunderbolt,usb-c,workstation", "https://images.unsplash.com/photo-1625842268584-8f3296236761?auto=format&fit=crop&w=900&q=80", "249.00", 76)
    );

    private static final List<String> POLICY_TITLES = List.of(
        "Returns and refund policy",
        "Laptop warranty policy",
        "Gaming product stock policy",
        "Coupon eligibility policy",
        "Checkout payment policy",
        "Shipping and delivery policy",
        "Price match policy",
        "Order cancellation policy",
        "Support ticket priority policy",
        "Review moderation policy",
        "Bundle discount policy",
        "International delivery policy",
        "Damaged item policy",
        "Gift card policy",
        "Account data privacy policy",
        "Preorder policy",
        "Open box policy",
        "Extended warranty policy",
        "Business purchase policy",
        "Marketplace seller policy"
    );

    private DemoSeedCatalog() {
    }

    static List<ProductSeed> products() {
        List<ProductSeed> out = new ArrayList<>(100);
        out.addAll(BASE_PRODUCTS);

        String[] categories = {"Laptops", "Phones", "Headphones", "Monitors", "Accessories"};
        String[] useCases = {"gaming", "remote work", "creator workflows", "travel", "student productivity"};
        for (int i = out.size() + 1; i <= 100; i++) {
            String category = categories[(i - 1) % categories.length];
            String useCase = useCases[(i - 1) % useCases.length];
            String sku = "SKU-DEMO-%04d".formatted(i);
            BigDecimal price = BigDecimal.valueOf(79 + (i * 37L) % 2200).setScale(2);
            out.add(new ProductSeed(
                sku,
                "%s Demo Product %02d".formatted(category.substring(0, category.length() - 1), i),
                "Demo %s item tuned for %s. Includes realistic specifications, availability, and purchase context for AI Fabric retrieval.".formatted(category.toLowerCase(), useCase),
                category,
                "%s,%s,demo,commerce".formatted(category.toLowerCase(), useCase.replace(' ', '-')),
                imageForCategory(category),
                price.toPlainString(),
                10 + (i * 7) % 140
            ));
        }
        return out;
    }

    static List<PolicySeed> policies() {
        List<PolicySeed> out = new ArrayList<>(POLICY_TITLES.size());
        for (int i = 0; i < POLICY_TITLES.size(); i++) {
            String title = POLICY_TITLES.get(i);
            String classification = title.split(" ")[0].toLowerCase();
            out.add(new PolicySeed(
                title,
                "Policy guidance for %s. Use this policy when answering commerce questions. Explain user options, mention limits clearly, and prefer confirmed actions for checkout, returns, coupon application, and order changes.".formatted(title.toLowerCase()),
                classification
            ));
        }
        return out;
    }

    static List<CouponSeed> coupons() {
        List<CouponSeed> out = new ArrayList<>(20);
        for (int i = 1; i <= 20; i++) {
            boolean percent = i % 2 == 0;
            out.add(new CouponSeed(
                "DEMO%d".formatted(100 + i),
                percent
                    ? "%d percent off eligible demo products".formatted(5 + i)
                    : "$%d off eligible orders".formatted(10 + i),
                "Valid for public demo carts. Not valid with other large-discount promotions. AI should explain constraints before applying.",
                true,
                percent ? 5 + i : null,
                percent ? null : BigDecimal.valueOf(10L + i)
            ));
        }
        return out;
    }

    static List<ReviewSeed> reviews(List<ProductSeed> products) {
        List<ReviewSeed> out = new ArrayList<>(200);
        for (int i = 0; i < 200; i++) {
            ProductSeed product = products.get(i % products.size());
            int rating = 3 + (i % 3);
            out.add(new ReviewSeed(
                "reviewer-%03d".formatted(i + 1),
                product.sku(),
                rating,
                "Rated %d stars. The %s worked well for %s. Notes include performance, value, build quality, and delivery experience for grounded comparison.".formatted(
                    rating,
                    product.name(),
                    product.category().toLowerCase()
                )
            ));
        }
        return out;
    }

    static List<TicketSeed> tickets() {
        String[] issueTypes = {"ORDER_STATUS", "RETURN", "COUPON", "DELIVERY", "PAYMENT"};
        List<TicketSeed> out = new ArrayList<>(50);
        for (int i = 1; i <= 50; i++) {
            String issueType = issueTypes[(i - 1) % issueTypes.length];
            out.add(new TicketSeed(
                "shopping-demo-user-seed-%02d".formatted((i % 5) + 1),
                issueType,
                "Demo support ticket %02d about %s. Used to show support-side commerce workflows after full data is loaded.".formatted(i, issueType.toLowerCase()),
                i % 4 == 0 ? "PO-DEMO-%03d".formatted(i) : null
            ));
        }
        return out;
    }

    private static String imageForCategory(String category) {
        return switch (category) {
            case "Laptops" -> "https://images.unsplash.com/photo-1496181133206-80ce9b88a853?auto=format&fit=crop&w=900&q=80";
            case "Phones" -> "https://images.unsplash.com/photo-1511707171634-5f897ff02aa9?auto=format&fit=crop&w=900&q=80";
            case "Headphones" -> "https://images.unsplash.com/photo-1505740420928-5e560c06d30e?auto=format&fit=crop&w=900&q=80";
            case "Monitors" -> "https://images.unsplash.com/photo-1527443224154-c4a3942d3acf?auto=format&fit=crop&w=900&q=80";
            default -> "https://images.unsplash.com/photo-1625842268584-8f3296236761?auto=format&fit=crop&w=900&q=80";
        };
    }

    record ProductSeed(String sku,
                       String name,
                       String description,
                       String category,
                       String tags,
                       String imageUrl,
                       String price,
                       int stock) {
    }

    record PolicySeed(String title, String text, String classification) {
    }

    record ReviewSeed(String userId, String sku, int rating, String text) {
    }

    record CouponSeed(String code,
                      String description,
                      String rules,
                      boolean active,
                      Integer discountPercent,
                      BigDecimal discountAmount) {
    }

    record TicketSeed(String userId, String issueType, String description, String orderNumber) {
    }
}
