package za.co.sfh.stocklistener;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@Disabled("Requires Docker/Testcontainers")
@SpringBootTest
class StocklistenerApplicationTests implements DatabaseContainerTest{

	@Test
	void contextLoads() {
	}

}
