package ai.fabric.relationship.it.repository;

import ai.fabric.relationship.it.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, String> {
}
