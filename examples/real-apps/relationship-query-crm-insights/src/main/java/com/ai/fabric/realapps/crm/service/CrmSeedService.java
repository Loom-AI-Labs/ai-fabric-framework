package com.ai.fabric.realapps.crm.service;

import com.ai.fabric.realapps.crm.domain.AccountStatus;
import com.ai.fabric.realapps.crm.domain.CrmAccount;
import com.ai.fabric.realapps.crm.domain.CrmContact;
import com.ai.fabric.realapps.crm.domain.CrmDeal;
import com.ai.fabric.realapps.crm.domain.CrmSupportTicket;
import com.ai.fabric.realapps.crm.domain.DealStage;
import com.ai.fabric.realapps.crm.domain.TicketPriority;
import com.ai.fabric.realapps.crm.domain.TicketStatus;
import com.ai.fabric.realapps.crm.repo.AccountRepository;
import com.ai.fabric.realapps.crm.repo.ContactRepository;
import com.ai.fabric.realapps.crm.repo.DealRepository;
import com.ai.fabric.realapps.crm.repo.SupportTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CrmSeedService {

    private final AccountRepository accountRepository;
    private final ContactRepository contactRepository;
    private final DealRepository dealRepository;
    private final SupportTicketRepository ticketRepository;

    @Transactional
    public Map<String, Object> seed() {
        ticketRepository.deleteAll();
        dealRepository.deleteAll();
        contactRepository.deleteAll();
        accountRepository.deleteAll();

        CrmAccount acme = createAccount("Acme", "Manufacturing", "NA", AccountStatus.ACTIVE, BigDecimal.valueOf(2500000));
        CrmAccount globex = createAccount("Globex", "SaaS", "EMEA", AccountStatus.CHURN_RISK, BigDecimal.valueOf(950000));
        CrmAccount initech = createAccount("Initech", "FinTech", "EMEA", AccountStatus.ACTIVE, BigDecimal.valueOf(4200000));

        createContact(acme, "Sarah", "Ahmed", "sarah.ahmed@acme.example", "VP Operations");
        createContact(acme, "John", "Doe", "john.doe@acme.example", "Procurement Lead");
        createContact(globex, "Mona", "Khaled", "mona.khaled@globex.example", "Customer Success");
        createContact(initech, "Peter", "Gibbons", "peter@initech.example", "Director IT");

        createDeal(acme, "Renewal - Acme Core", DealStage.OPEN, BigDecimal.valueOf(180000), LocalDate.now().plusDays(21), "Alicia");
        createDeal(acme, "Expansion - Acme Seats", DealStage.WON, BigDecimal.valueOf(90000), LocalDate.now().minusDays(14), "Alicia");
        createDeal(globex, "Save Offer - Globex", DealStage.OPEN, BigDecimal.valueOf(45000), LocalDate.now().plusDays(7), "Rami");
        createDeal(initech, "New Logos - Initech", DealStage.OPEN, BigDecimal.valueOf(320000), LocalDate.now().plusDays(35), "Nour");

        createTicket(globex, "TCK-1001", TicketStatus.OPEN, TicketPriority.HIGH, "Billing dispute and refund request", "Sam");
        createTicket(globex, "TCK-1002", TicketStatus.PENDING, TicketPriority.MEDIUM, "Login MFA reset", "Sam");
        createTicket(acme, "TCK-2001", TicketStatus.RESOLVED, TicketPriority.LOW, "How to update invoice address", "Lina");

        return Map.of(
            "accounts", accountRepository.count(),
            "contacts", contactRepository.count(),
            "deals", dealRepository.count(),
            "tickets", ticketRepository.count()
        );
    }

    private CrmAccount createAccount(String name, String industry, String region, AccountStatus status, BigDecimal revenue) {
        CrmAccount account = new CrmAccount();
        account.setName(name);
        account.setIndustry(industry);
        account.setRegion(region);
        account.setStatus(status);
        account.setAnnualRevenue(revenue);
        account.setCreatedAt(LocalDateTime.now().minusDays(120));
        return accountRepository.save(account);
    }

    private CrmContact createContact(CrmAccount account, String firstName, String lastName, String email, String title) {
        CrmContact contact = new CrmContact();
        contact.setAccount(account);
        contact.setFirstName(firstName);
        contact.setLastName(lastName);
        contact.setEmail(email);
        contact.setTitle(title);
        return contactRepository.save(contact);
    }

    private CrmDeal createDeal(CrmAccount account,
                               String name,
                               DealStage stage,
                               BigDecimal amount,
                               LocalDate closeDate,
                               String ownerName) {
        CrmDeal deal = new CrmDeal();
        deal.setAccount(account);
        deal.setName(name);
        deal.setStage(stage);
        deal.setAmount(amount);
        deal.setCloseDate(closeDate);
        deal.setOwnerName(ownerName);
        return dealRepository.save(deal);
    }

    private CrmSupportTicket createTicket(CrmAccount account,
                                          String ticketNumber,
                                          TicketStatus status,
                                          TicketPriority priority,
                                          String summary,
                                          String assignedTo) {
        CrmSupportTicket ticket = new CrmSupportTicket();
        ticket.setAccount(account);
        ticket.setTicketNumber(ticketNumber);
        ticket.setStatus(status);
        ticket.setPriority(priority);
        ticket.setSummary(summary);
        ticket.setAssignedTo(assignedTo);
        ticket.setCreatedAt(LocalDateTime.now().minusDays(7));
        return ticketRepository.save(ticket);
    }
}
