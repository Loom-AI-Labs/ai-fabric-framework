package com.ai.fabric.realapps.itsupport.config;

import com.ai.fabric.realapps.itsupport.repo.AgentRepository;
import com.ai.fabric.realapps.itsupport.repo.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("smoke")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:it_support_seed_concurrency;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "ai.providers.llm-provider=smoke",
    "ai.providers.embedding-provider=smoke",
    "ai.vector-db.type=false"
})
class DataSeederConcurrencyTest {

    @Autowired
    private DataSeeder dataSeeder;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @BeforeEach
    void clearSeedData() {
        ticketRepository.deleteAll();
        agentRepository.deleteAll();
    }

    @Test
    void serializesConcurrentSeedRequestsAndCreatesEachRecordOnce() {
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Void>> requests = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            requests.add(CompletableFuture.runAsync(() -> {
                await(start);
                dataSeeder.seedIfEmpty();
            }));
        }

        start.countDown();
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();

        assertThat(agentRepository.count()).isEqualTo(3);
        assertThat(ticketRepository.count()).isEqualTo(3);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to seed", exception);
        }
    }
}
