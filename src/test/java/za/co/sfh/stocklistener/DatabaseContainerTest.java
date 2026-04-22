package za.co.sfh.stocklistener;

import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

@DirtiesContext
@Testcontainers
public interface DatabaseContainerTest {

    DockerImageName mariaDBContainerName = DockerImageName.parse("mariadb:11.4");

    @Container
    MariaDBContainer mariaDBContainer = new MariaDBContainer(mariaDBContainerName)
            .withUsername("stock")
            .withPassword("stock")
            .withDatabaseName("stock");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mariaDBContainer::getJdbcUrl);
        registry.add("spring.datasource.username", mariaDBContainer::getUsername);
        registry.add("spring.datasource.password", mariaDBContainer::getPassword);
        registry.add("spring.flyway.user", mariaDBContainer::getUsername);
        registry.add("spring.flyway.password", mariaDBContainer::getPassword);
        registry.add("spring.flyway.schemas", () -> "stock");
        registry.add("spring.flyway.url", mariaDBContainer::getJdbcUrl);
    }
}
