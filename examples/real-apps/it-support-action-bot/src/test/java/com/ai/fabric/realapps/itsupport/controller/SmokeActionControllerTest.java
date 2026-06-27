package com.ai.fabric.realapps.itsupport.controller;

import com.ai.fabric.realapps.itsupport.domain.Ticket;
import com.ai.fabric.realapps.itsupport.repo.TicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("smoke")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:it_support_smoke_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "ai.providers.llm-provider=smoke",
    "ai.providers.embedding-provider=smoke",
    "ai.vector-db.type=false"
})
class SmokeActionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TicketRepository ticketRepository;

    @Test
    void exposesActionContractsForSupportBot() throws Exception {
        mockMvc.perform(get("/api/smoke/actions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.actions.create_ticket.accessMode").value("WRITE_ONLY"))
            .andExpect(jsonPath("$.actions.create_ticket.confirmationRequired").value(false))
            .andExpect(jsonPath("$.actions.create_ticket.requiredParameters", hasItem("title")))
            .andExpect(jsonPath("$.actions.assign_ticket.accessMode").value("WRITE_ONLY"))
            .andExpect(jsonPath("$.actions.assign_ticket.confirmationRequired").value(true))
            .andExpect(jsonPath("$.actions.assign_ticket.requiredParameters", hasSize(2)));
    }

    @Test
    void deniesMissingIdentityButExecutesAllowedNonConfirmableAction() throws Exception {
        String params = """
            {
              "params": {
                "title": "Smoke profile laptop setup",
                "description": "Provision a laptop for a new starter.",
                "priority": "HIGH"
              }
            }
            """;

        mockMvc.perform(post("/api/smoke/actions/create_ticket")
                .contentType(MediaType.APPLICATION_JSON)
                .content(params))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.allowed").value(false))
            .andExpect(jsonPath("$.outcome").value("ACTION_NOT_ALLOWED"));

        mockMvc.perform(post("/api/smoke/actions/create_ticket")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "userId": "agent_alex",
                      "sessionId": "agent-alex-smoke",
                      "params": {
                        "title": "Smoke profile laptop setup",
                        "description": "Provision a laptop for a new starter.",
                        "priority": "HIGH"
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.allowed").value(true))
            .andExpect(jsonPath("$.outcome").value("ACTION_EXECUTED"))
            .andExpect(jsonPath("$.result.success").value(true))
            .andExpect(jsonPath("$.result.data.ticketNumber", notNullValue()))
            .andExpect(jsonPath("$.result.data.priority").value("HIGH"))
            .andExpect(jsonPath("$.result.data.status").value("OPEN"));
    }

    @Test
    void gatesConfirmableActionThenExecutesAfterExplicitConfirmation() throws Exception {
        mockMvc.perform(post("/api/smoke/actions/assign_ticket")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "userId": "agent_alex",
                      "sessionId": "agent-alex-smoke",
                      "params": {
                        "ticketNumber": 1001,
                        "assigneeUsername": "agent_maya"
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.allowed").value(true))
            .andExpect(jsonPath("$.confirmationRequired").value(true))
            .andExpect(jsonPath("$.outcome").value("CONFIRMATION_REQUIRED"))
            .andExpect(jsonPath("$.confirmationMessage", containsString("Assign ticket 1001 to agent_maya")));

        mockMvc.perform(post("/api/smoke/actions/assign_ticket")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "userId": "agent_alex",
                      "sessionId": "agent-alex-smoke",
                      "confirmed": true,
                      "params": {
                        "ticketNumber": 1001,
                        "assigneeUsername": "agent_maya"
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.outcome").value("ACTION_EXECUTED"))
            .andExpect(jsonPath("$.result.success").value(true))
            .andExpect(jsonPath("$.result.data.ticketNumber").value(1001))
            .andExpect(jsonPath("$.result.data.assignedTo").value("agent_maya"))
            .andExpect(jsonPath("$.result.data.status").value("IN_PROGRESS"));

        Ticket ticket = ticketRepository.findByTicketNumber(1001L).orElseThrow();
        assertThat(ticket.getAssignedTo()).isEqualTo("agent_maya");
        assertThat(ticket.getStatus()).isEqualTo(Ticket.Status.IN_PROGRESS);
    }
}
