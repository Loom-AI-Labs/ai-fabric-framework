package ai.fabric.it.repository;

import ai.fabric.it.entity.TestUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Test User Entity
 *
 * Provides data access methods for testing AI infrastructure integration.
 *
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Repository
public interface TestUserRepository extends JpaRepository<TestUser, Long> {

    /**
     * Find user by email
     */
    Optional<TestUser> findByEmail(String email);

    /**
     * Find users by location
     */
    List<TestUser> findByLocation(String location);

    /**
     * Find active users
     */
    List<TestUser> findByActiveTrue();

    /**
     * Find users by age range
     */
    @Query("SELECT u FROM TestUser u WHERE u.age BETWEEN :minAge AND :maxAge")
    List<TestUser> findByAgeRange(@Param("minAge") Integer minAge, @Param("maxAge") Integer maxAge);

    /**
     * Find users by first name containing text
     */
    List<TestUser> findByFirstNameContainingIgnoreCase(String firstName);

    /**
     * Find users by last name containing text
     */
    List<TestUser> findByLastNameContainingIgnoreCase(String lastName);

    /**
     * Find users with bio
     */
    @Query("SELECT u FROM TestUser u WHERE u.bio IS NOT NULL AND u.bio != ''")
    List<TestUser> findUsersWithBio();

    /**
     * Find adult users
     */
    @Query("SELECT u FROM TestUser u WHERE u.age >= 18")
    List<TestUser> findAdultUsers();

    /**
     * Count users by location
     */
    long countByLocation(String location);

    /**
     * Find users created after date
     */
    List<TestUser> findByCreatedAtAfter(java.time.LocalDateTime date);
}