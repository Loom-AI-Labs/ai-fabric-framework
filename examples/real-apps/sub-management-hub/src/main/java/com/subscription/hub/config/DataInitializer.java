package com.subscription.hub.config;

import ai.fabric.service.AICapabilityService;
import com.subscription.hub.entity.*;
import com.subscription.hub.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Comprehensive data seeder for all entities:
 * - SubscriptionPlan (3 plans)
 * - User (100 users)
 * - Subscription (~90 subscriptions with all statuses)
 * - Address (billing and shipping for all subscriptions)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final SubscriptionPlanRepository planRepository;
    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final ObjectProvider<AICapabilityService> aiCapabilityServiceProvider;
    private final Random random = new Random();

    // Seed data arrays
    private static final String[] FIRST_NAMES = {
        "James", "Mary", "John", "Patricia", "Robert", "Jennifer", "Michael", "Linda",
        "William", "Elizabeth", "David", "Barbara", "Richard", "Susan", "Joseph", "Jessica",
        "Thomas", "Sarah", "Charles", "Karen", "Christopher", "Nancy", "Daniel", "Lisa",
        "Matthew", "Betty", "Anthony", "Margaret", "Mark", "Sandra", "Donald", "Ashley",
        "Steven", "Kimberly", "Paul", "Emily", "Andrew", "Donna", "Joshua", "Michelle",
        "Kenneth", "Carol", "Kevin", "Amanda", "Brian", "Dorothy", "George", "Melissa",
        "Timothy", "Deborah", "Ronald", "Stephanie", "Jason", "Rebecca", "Edward", "Sharon",
        "Jeffrey", "Laura", "Ryan", "Cynthia", "Jacob", "Kathleen", "Gary", "Amy",
        "Nicholas", "Angela", "Eric", "Shirley", "Jonathan", "Anna", "Stephen", "Brenda",
        "Larry", "Pamela", "Justin", "Emma", "Scott", "Nicole", "Brandon", "Helen",
        "Benjamin", "Samantha", "Samuel", "Katherine", "Frank", "Christine", "Gregory", "Debra",
        "Raymond", "Rachel", "Alexander", "Carolyn", "Patrick", "Janet", "Jack", "Virginia",
        "Dennis", "Maria", "Jerry", "Heather", "Tyler", "Diane", "Aaron", "Julie"
    };

    private static final String[] LAST_NAMES = {
        "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis",
        "Rodriguez", "Martinez", "Hernandez", "Lopez", "Wilson", "Anderson", "Thomas", "Taylor",
        "Moore", "Jackson", "Martin", "Lee", "Thompson", "White", "Harris", "Sanchez",
        "Clark", "Ramirez", "Lewis", "Robinson", "Walker", "Young", "Allen", "King",
        "Wright", "Scott", "Torres", "Nguyen", "Hill", "Flores", "Green", "Adams",
        "Nelson", "Baker", "Hall", "Rivera", "Campbell", "Mitchell", "Carter", "Roberts",
        "Gomez", "Phillips", "Evans", "Turner", "Diaz", "Parker", "Cruz", "Edwards",
        "Collins", "Reyes", "Stewart", "Morris", "Rogers", "Reed", "Cook", "Morgan",
        "Bell", "Murphy", "Bailey", "Rivera", "Cooper", "Richardson", "Cox", "Howard",
        "Ward", "Torres", "Peterson", "Gray", "Ramirez", "James", "Watson", "Brooks",
        "Kelly", "Sanders", "Price", "Bennett", "Wood", "Barnes", "Ross", "Henderson",
        "Coleman", "Jenkins", "Perry", "Powell", "Long", "Patterson", "Hughes", "Flores",
        "Washington", "Butler", "Simmons", "Foster", "Gonzales", "Bryant", "Alexander", "Russell"
    };

    private static final String[] STREET_NAMES = {
        "Main St", "Oak Ave", "Pine Rd", "Elm St", "Maple Dr", "Park Ave", "First St", "Second St",
        "Washington Ave", "Lincoln Way", "Church St", "Market St", "Broadway", "High St", "Spring St",
        "Cedar Ln", "Hill Rd", "Valley Dr", "Sunset Blvd", "Lake Dr", "River Rd", "Forest Ave",
        "Meadow Ln", "Garden St", "Rose Ave", "Lily Dr", "Cherry St", "Apple Way", "Peach Blvd"
    };

    private static final String[] CITIES = {
        "New York", "Los Angeles", "Chicago", "Houston", "Phoenix", "Philadelphia", "San Antonio",
        "San Diego", "Dallas", "San Jose", "Austin", "Jacksonville", "Fort Worth", "Columbus",
        "Charlotte", "San Francisco", "Indianapolis", "Seattle", "Denver", "Washington", "Boston",
        "El Paso", "Nashville", "Detroit", "Oklahoma City", "Portland", "Las Vegas", "Memphis",
        "Louisville", "Baltimore", "Milwaukee", "Albuquerque", "Tucson", "Fresno", "Sacramento",
        "Kansas City", "Mesa", "Atlanta", "Omaha", "Colorado Springs", "Raleigh", "Virginia Beach",
        "Miami", "Oakland", "Minneapolis", "Tulsa", "Cleveland", "Wichita", "Arlington"
    };

    private static final String[] STATES = {
        "NY", "CA", "IL", "TX", "AZ", "PA", "TX", "CA", "TX", "CA", "TX", "FL", "TX", "OH",
        "NC", "CA", "IN", "WA", "CO", "DC", "MA", "TX", "TN", "MI", "OK", "OR", "NV", "TN",
        "KY", "MD", "WI", "NM", "AZ", "CA", "CA", "MO", "AZ", "GA", "NE", "CO", "NC", "VA",
        "FL", "CA", "MN", "OK", "OH", "KS", "TX"
    };

    private static final String[] ZIP_CODES = {
        "10001", "90001", "60601", "77001", "85001", "19101", "78201", "92101", "75201", "95101",
        "78701", "32201", "76101", "43201", "28201", "94101", "46201", "98101", "80201", "20001",
        "02101", "79901", "37201", "48201", "73101", "97201", "89101", "38101", "40201", "21201",
        "53201", "87101", "85701", "93701", "95801", "64101", "85201", "30301", "68101", "80901",
        "27601", "23451", "33101", "94601", "55401", "74101", "44101", "67201", "76001"
    };

    @Override
    @Transactional
    public void run(String... args) {
        log.info("Starting comprehensive data initialization...");

        // Initialize all entities in order
        List<SubscriptionPlan> plans = initializePlans();
        List<User> users = initializeUsers();
        List<Subscription> subscriptions = initializeSubscriptions(users, plans);

        log.info("Data initialization completed!");
        log.info("Summary: {} plans, {} users, {} subscriptions, {} addresses",
            plans.size(), users.size(), subscriptions.size(),
            subscriptions.stream().mapToInt(s -> (s.getBillingAddress() != null ? 1 : 0) +
                                                (s.getShippingAddress() != null ? 1 : 0)).sum());
    }

    private List<SubscriptionPlan> initializePlans() {
        if (planRepository.count() == 0) {
            log.info("Initializing subscription plans...");

            SubscriptionPlan basicPlan = SubscriptionPlan.builder()
                .name("Basic Plan")
                .description("Perfect for individuals getting started. Includes 10GB storage, basic support, and core features.")
                .monthlyPrice(new BigDecimal("9.99"))
                .annualPrice(new BigDecimal("99.99"))
                .tier(SubscriptionPlan.PlanTier.BASIC)
                .features(Arrays.asList("10GB storage", "Email support", "Core features", "Mobile app access"))
                .maxUsers(1)
                .storageGB(10)
                .isActive(true)
                .build();

            SubscriptionPlan proPlan = SubscriptionPlan.builder()
                .name("Pro Plan")
                .description("For professionals and small teams. Includes 100GB storage, priority support, advanced features, and team collaboration tools.")
                .monthlyPrice(new BigDecimal("49.99"))
                .annualPrice(new BigDecimal("499.99"))
                .tier(SubscriptionPlan.PlanTier.PRO)
                .features(Arrays.asList("100GB storage", "Priority support", "Advanced features", "Team collaboration", "API access", "Analytics dashboard"))
                .maxUsers(5)
                .storageGB(100)
                .isActive(true)
                .build();

            SubscriptionPlan enterprisePlan = SubscriptionPlan.builder()
                .name("Enterprise Plan")
                .description("For large organizations. Includes unlimited storage, 24/7 dedicated support, all features, custom integrations, and enterprise security.")
                .monthlyPrice(new BigDecimal("199.99"))
                .annualPrice(new BigDecimal("1999.99"))
                .tier(SubscriptionPlan.PlanTier.ENTERPRISE)
                .features(Arrays.asList("Unlimited storage", "24/7 dedicated support", "All features", "Custom integrations", "Enterprise security", "SLA guarantee", "Dedicated account manager"))
                .maxUsers(null) // Unlimited
                .storageGB(null) // Unlimited
                .isActive(true)
                .build();

            List<SubscriptionPlan> plans = Arrays.asList(basicPlan, proPlan, enterprisePlan);
            planRepository.saveAll(plans);
            log.info("Initialized {} subscription plans", plans.size());
            indexPlansForAi(plans);
            return plans;
        } else {
            log.info("Plans already exist. Skipping plan initialization.");
            List<SubscriptionPlan> plans = planRepository.findAll();
            indexPlansForAi(plans);
            return plans;
        }
    }

    private void indexPlansForAi(List<SubscriptionPlan> plans) {
        AICapabilityService aiCapabilityService = aiCapabilityServiceProvider.getIfAvailable();
        if (aiCapabilityService == null) {
            log.debug("AICapabilityService not available; skipping plan indexing");
            return;
        }

        if (plans == null || plans.isEmpty()) {
            return;
        }

        int indexed = 0;
        for (SubscriptionPlan plan : plans) {
            try {
                aiCapabilityService.processEntityForAI(plan, "subscription-plan");
                indexed++;
            } catch (Exception ex) {
                log.warn("Failed to index subscription plan {} for AI search", plan != null ? plan.getId() : null, ex);
            }
        }

        log.info("AI indexing completed for {} subscription plan(s)", indexed);
    }

    private List<User> initializeUsers() {
        if (userRepository.count() == 0) {
            log.info("Initializing 100 users...");

            List<User> users = new ArrayList<>();

            for (long userId = 1; userId <= 100; userId++) {
                // Use varied names from seed data
                String firstName = FIRST_NAMES[random.nextInt(FIRST_NAMES.length)];
                String lastName = LAST_NAMES[random.nextInt(LAST_NAMES.length)];

                User user = User.builder()
                    .userId(userId)
                    .username("user_" + userId)
                    .email(String.format("%s.%s%d@example.com", firstName.toLowerCase(), lastName.toLowerCase(), userId))
                    .firstName(firstName)
                    .lastName(lastName)
                    .isGuest(false)
                    .createdAt(LocalDateTime.now().minusDays(random.nextInt(730))) // Up to 2 years ago
                    .lastLoginAt(random.nextBoolean() ? LocalDateTime.now().minusDays(random.nextInt(30)) : null)
                    .build();

                users.add(user);
            }

            userRepository.saveAll(users);
            log.info("Created {} users with varied names and emails", users.size());
            return users;
        } else {
            log.info("Users already exist. Skipping user initialization.");
            return userRepository.findAll();
        }
    }

    private List<Subscription> initializeSubscriptions(List<User> users, List<SubscriptionPlan> plans) {
        if (subscriptionRepository.count() == 0) {
            log.info("Initializing subscriptions with all statuses...");

            List<Subscription> subscriptions = new ArrayList<>();

            for (int i = 0; i < users.size(); i++) {
                User user = users.get(i);
                int chance = random.nextInt(100);

                if (chance < 60) {
                    // 60% - ACTIVE subscriptions
                    subscriptions.add(createActiveSubscription(user, plans));
                } else if (chance < 80) {
                    // 20% - CANCELLED subscriptions
                    subscriptions.add(createCancelledSubscription(user, plans));
                } else if (chance < 90) {
                    // 10% - PAST_DUE subscriptions
                    subscriptions.add(createPastDueSubscription(user, plans));
                } else if (chance < 95) {
                    // 5% - EXPIRED subscriptions
                    subscriptions.add(createExpiredSubscription(user, plans));
                }
                // 5% - No subscription
            }

            subscriptionRepository.saveAll(subscriptions);
            log.info("Created {} subscriptions with varied statuses", subscriptions.size());
            return subscriptions;
        } else {
            log.info("Subscriptions already exist. Skipping subscription initialization.");
            return subscriptionRepository.findAll();
        }
    }

    private Subscription createActiveSubscription(User user, List<SubscriptionPlan> plans) {
        SubscriptionPlan plan = plans.get(random.nextInt(plans.size()));
        Subscription.BillingCycle billingCycle = random.nextBoolean()
            ? Subscription.BillingCycle.MONTHLY
            : Subscription.BillingCycle.ANNUAL;

        LocalDateTime startDate = LocalDateTime.now().minusMonths(random.nextInt(12));
        LocalDateTime endDate = billingCycle == Subscription.BillingCycle.MONTHLY
            ? startDate.plusMonths(1)
            : startDate.plusYears(1);

        return Subscription.builder()
            .userId(user.getId())
            .planId(plan.getId())
            .status(Subscription.SubscriptionStatus.ACTIVE)
            .startDate(startDate)
            .endDate(endDate)
            .billingCycle(billingCycle)
            .lastActivityDate(LocalDateTime.now().minusDays(random.nextInt(30)))
            .churnRiskScore(random.nextDouble() * 0.4) // Low to medium risk (0.0-0.4)
            .billingAddress(createRandomAddress(Address.AddressType.BILLING, user.getUserId()))
            .shippingAddress(createRandomAddress(Address.AddressType.SHIPPING, user.getUserId()))
            .build();
    }

    private Subscription createCancelledSubscription(User user, List<SubscriptionPlan> plans) {
        SubscriptionPlan plan = plans.get(random.nextInt(plans.size()));
        LocalDateTime startDate = LocalDateTime.now().minusMonths(random.nextInt(24));
        LocalDateTime cancelDate = startDate.plusMonths(random.nextInt(6) + 1);

        return Subscription.builder()
            .userId(user.getId())
            .planId(plan.getId())
            .status(Subscription.SubscriptionStatus.CANCELLED)
            .startDate(startDate)
            .endDate(cancelDate)
            .billingCycle(Subscription.BillingCycle.MONTHLY)
            .lastActivityDate(cancelDate)
            .churnRiskScore(0.85 + random.nextDouble() * 0.15) // High risk (0.85-1.0)
            .billingAddress(createRandomAddress(Address.AddressType.BILLING, user.getUserId()))
            .build();
    }

    private Subscription createPastDueSubscription(User user, List<SubscriptionPlan> plans) {
        SubscriptionPlan plan = plans.get(random.nextInt(plans.size()));
        LocalDateTime startDate = LocalDateTime.now().minusMonths(random.nextInt(6));
        LocalDateTime endDate = startDate.plusMonths(1);

        return Subscription.builder()
            .userId(user.getId())
            .planId(plan.getId())
            .status(Subscription.SubscriptionStatus.PAST_DUE)
            .startDate(startDate)
            .endDate(endDate)
            .billingCycle(Subscription.BillingCycle.MONTHLY)
            .lastActivityDate(LocalDateTime.now().minusDays(random.nextInt(60) + 30))
            .churnRiskScore(0.6 + random.nextDouble() * 0.25) // Medium-high risk (0.6-0.85)
            .billingAddress(createRandomAddress(Address.AddressType.BILLING, user.getUserId()))
            .shippingAddress(createRandomAddress(Address.AddressType.SHIPPING, user.getUserId()))
            .build();
    }

    private Subscription createExpiredSubscription(User user, List<SubscriptionPlan> plans) {
        SubscriptionPlan plan = plans.get(random.nextInt(plans.size()));
        LocalDateTime startDate = LocalDateTime.now().minusMonths(random.nextInt(24) + 12);
        LocalDateTime endDate = startDate.plusMonths(random.nextInt(6) + 1);

        return Subscription.builder()
            .userId(user.getId())
            .planId(plan.getId())
            .status(Subscription.SubscriptionStatus.EXPIRED)
            .startDate(startDate)
            .endDate(endDate)
            .billingCycle(Subscription.BillingCycle.MONTHLY)
            .lastActivityDate(endDate.minusDays(random.nextInt(30)))
            .churnRiskScore(0.9 + random.nextDouble() * 0.1) // Very high risk (0.9-1.0)
            .billingAddress(createRandomAddress(Address.AddressType.BILLING, user.getUserId()))
            .build();
    }

    private Address createRandomAddress(Address.AddressType type, Long userId) {
        // Use userId to create consistent but varied addresses
        int baseIndex = (int) (userId % CITIES.length);
        int streetNum = (int) (userId % 9999) + 1;
        int cityIndex = (baseIndex + random.nextInt(5)) % CITIES.length;

        // Sometimes use same city for billing/shipping, sometimes different
        String city = CITIES[cityIndex];
        String state = STATES[cityIndex % STATES.length];
        String zipCode = ZIP_CODES[cityIndex % ZIP_CODES.length];
        String streetName = STREET_NAMES[random.nextInt(STREET_NAMES.length)];

        // Validation score varies based on address completeness
        double validationScore = 0.85 + random.nextDouble() * 0.15;
        boolean isValidated = validationScore > 0.9;

        return Address.builder()
            .streetAddress(String.format("%d %s", streetNum, streetName))
            .city(city)
            .state(state)
            .postalCode(zipCode)
            .country("USA")
            .type(type)
            .isValidated(isValidated)
            .validationScore(validationScore)
            .build();
    }
}
