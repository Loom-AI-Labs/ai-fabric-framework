package com.ai.fabric.realapps.agenticresolver.repository;

import com.ai.fabric.realapps.agenticresolver.entity.DemoReviewTaskBinding;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DemoReviewTaskBindingRepository
    extends JpaRepository<DemoReviewTaskBinding, String> {

    List<DemoReviewTaskBinding> findByDemoSessionId(String demoSessionId);
}
