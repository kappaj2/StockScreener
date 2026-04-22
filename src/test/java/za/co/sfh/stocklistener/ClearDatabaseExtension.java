package za.co.sfh.stocklistener;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@Slf4j
public class ClearDatabaseExtension implements BeforeEachCallback {
    @Override
    public void beforeEach(@NonNull ExtensionContext extensionContext) throws Exception {
        var flyway = SpringExtension.getApplicationContext(extensionContext)
                .getBean(Flyway.class);
        flyway.clean();
        flyway.migrate();
    }
}