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

    private static final List<PolicySeed> BASE_POLICIES = List.of(
        new PolicySeed(
            "Returns and refund policy",
            "Customers may return eligible products within 30 days of delivery. Opened electronics, including opened gaming laptops, may be returned when the device is complete, undamaged, factory reset, and includes the charger, accessories, and original serial-number label. Refunds return to the original payment method after inspection. Missing accessories may reduce the refund. Final-sale clearance items, activated software keys, and damaged-by-customer items are not eligible for a standard return.",
            "returns"
        ),
        new PolicySeed(
            "Laptop warranty policy",
            "Laptop warranty covers manufacturing defects for 12 months and is separate from return eligibility. Warranty service does not decide whether an opened laptop can be returned. For opened gaming laptop returns, use the Returns and refund policy and Open box policy: the return window is 30 days, the laptop must be undamaged and complete, and no restocking fee applies when the condition check passes.",
            "laptop"
        ),
        new PolicySeed(
            "Gaming product stock policy",
            "Gaming products with limited stock may be reserved in a cart for 20 minutes. If stock expires before checkout, the assistant should suggest another in-stock gaming product or ask whether the user wants stock alerts. Stock policy does not override return eligibility.",
            "gaming"
        ),
        new PolicySeed(
            "Coupon eligibility policy",
            "Coupons apply only to active, in-stock products and cannot be combined with clearance pricing, gift cards, or marketplace-seller items. Percentage coupons apply before tax and shipping. The assistant should explain coupon constraints before applying a coupon action.",
            "coupon"
        ),
        new PolicySeed(
            "Checkout payment policy",
            "Checkout requires a valid cart, shipping address, email address, and payment method. Payment authorization is captured only after the user confirms checkout. If a payment method is missing or declined, ask for updated payment details instead of placing an order.",
            "checkout"
        ),
        new PolicySeed(
            "Shipping and delivery policy",
            "Standard delivery takes 3 to 5 business days for in-stock products. Express delivery takes 1 to 2 business days where available. Delivery estimates can change for oversized monitors, international addresses, or items awaiting restock.",
            "shipping"
        ),
        new PolicySeed(
            "Price match policy",
            "Price match requests are eligible within 7 days of purchase for identical products sold by authorized retailers. The compared product must be in stock and match model, storage, color, warranty coverage, and seller region. Marketplace offers and coupon-only prices are excluded.",
            "price"
        ),
        new PolicySeed(
            "Order cancellation policy",
            "Orders can be cancelled before they enter packing or shipment. After shipment, the customer must use the return flow instead of cancellation. The assistant should require confirmation before cancelling an order.",
            "order"
        ),
        new PolicySeed(
            "Support ticket priority policy",
            "Support tickets are prioritized by issue severity. Payment failures, failed checkout, and delivery exceptions are high priority. Product advice and general policy questions are normal priority. The assistant should create a support ticket only after collecting the issue type and description.",
            "support"
        ),
        new PolicySeed(
            "Review moderation policy",
            "Product reviews should discuss product experience, delivery, or support interaction. Reviews containing personal data, abusive content, external links, or unrelated disputes should be rejected or escalated before publication.",
            "review"
        ),
        new PolicySeed(
            "Bundle discount policy",
            "Bundle discounts apply when all required items are present in the cart at checkout. Removing a bundle item removes the bundle discount. Bundle pricing cannot be combined with a larger manual coupon unless the coupon explicitly allows stacking.",
            "bundle"
        ),
        new PolicySeed(
            "International delivery policy",
            "International delivery is available only for supported countries and may require customs duties, local taxes, and longer delivery windows. Warranty and returns remain available, but return shipping labels may require support review before approval.",
            "international"
        ),
        new PolicySeed(
            "Damaged item policy",
            "Items damaged in transit are eligible for replacement or refund when reported within 7 days of delivery with photos of the packaging and product. Transit damage is not treated as customer damage and does not reduce the refund amount.",
            "damaged"
        ),
        new PolicySeed(
            "Gift card policy",
            "Gift cards are non-refundable after activation and cannot be exchanged for cash except where required by law. Gift cards can be used with eligible product purchases but cannot be used to buy another gift card.",
            "gift"
        ),
        new PolicySeed(
            "Account data privacy policy",
            "Customer account data is used only to support orders, delivery, payments, returns, and support workflows. The assistant must not expose payment tokens, full card numbers, or private account details in chat responses.",
            "account"
        ),
        new PolicySeed(
            "Preorder policy",
            "Preorders can be cancelled until the item is allocated for shipment. Estimated release dates may change. Coupons apply at shipment time only if they remain valid and the preorder terms allow promotional pricing.",
            "preorder"
        ),
        new PolicySeed(
            "Open box policy",
            "Opened laptops and other open-box electronics are returnable within 30 days when the product is complete, undamaged, factory reset, and includes original accessories. No restocking fee applies to opened gaming laptops when they pass inspection. If the laptop is missing accessories or has customer-caused damage, support must review the return before refund approval.",
            "open"
        ),
        new PolicySeed(
            "Extended warranty policy",
            "Extended warranty can be added during checkout or within 14 days after purchase for eligible laptops, monitors, and accessories. Extended warranty covers hardware failure after the manufacturer warranty but does not extend the 30-day return window.",
            "extended"
        ),
        new PolicySeed(
            "Business purchase policy",
            "Business purchases may require invoice details, tax identifiers, and approval by the buyer's organization. Bulk orders over 10 units can require manual review before fulfillment. Business return rules follow the standard return policy unless a signed agreement overrides them.",
            "business"
        ),
        new PolicySeed(
            "Marketplace seller policy",
            "Marketplace seller items may have seller-specific shipping and return handling. The product page must disclose when a marketplace seller fulfills the order. If no seller-specific return rule is shown, the standard Returns and refund policy applies.",
            "marketplace"
        )
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
        return BASE_POLICIES;
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
