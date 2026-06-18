package ai.fabric.it.migration;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TestMigrationRepository extends JpaRepository<TestMigrationEntity, String> {
}
