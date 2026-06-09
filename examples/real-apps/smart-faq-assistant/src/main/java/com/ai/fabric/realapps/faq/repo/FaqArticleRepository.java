package com.ai.fabric.realapps.faq.repo;

import com.ai.fabric.realapps.faq.domain.FaqArticle;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FaqArticleRepository extends JpaRepository<FaqArticle, Long> {}
