package za.co.sfh.stocklistener;

import org.mockserver.client.MockServerClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mockserver.MockServerContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
public interface MockServerContainerTest {

    DockerImageName localstackImage = DockerImageName.parse("mockserver/mockserver:5.15.0");

    @Container
    MockServerContainer MOCK_SERVER_CONTAINER = new MockSingletonContainer(localstackImage);

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("mockserver.url", MOCK_SERVER_CONTAINER::getEndpoint);
    }

    class MockSingletonContainer extends MockServerContainer {

        public MockSingletonContainer(final DockerImageName image) {
            super(image);
        }

        public static MockServerClient mockServerClient = null;
        private static final Object lock = new Object();


        @Override
        public void start() {
            System.setProperty("mockserver.logLevel", "WARN");
            super.start();
            logger().info("Starting MockServer container on {}:{}", this.getHost(), this.getServerPort());
            synchronized (lock) {
                try {
                    if (mockServerClient != null) {
                        try {
                            logger().info("Resetting existing MockServerClient expectations");
                            try {
                                mockServerClient.reset();
                            } catch (Exception e) {
                                logger().warn("Error resetting existing MockServerClient expectations", e);
                            }

                            logger().info("Closing existing MockServerClient");
                            mockServerClient.close();
                        } catch (Exception e) {
                            logger().warn("Error closing existing MockServerClient", e);
                        }
                        mockServerClient = null;
                    }

                    // Add a small delay before creating a new client
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }

                    logger().info("Creating new MockServerClient for {}:{}", this.getHost(), this.getServerPort());
                    mockServerClient = new MockServerClient(
                            this.getHost(),
                            this.getServerPort());

                    // Verify the client is connected
                    if (mockServerClient.hasStarted()) {
                        logger().info("MockServerClient successfully started and connected");

                        // Reset any existing expectations to ensure a clean state
                        try {
                            mockServerClient.reset();
                            logger().info("MockServerClient expectations reset successfully");
                        } catch (Exception e) {
                            logger().warn("Error resetting MockServerClient expectations", e);
                        }
                    } else {
                        logger().warn("MockServerClient created but not started properly");
                    }
                } catch (final Exception ex) {
                    logger().error("MockServer startup failure", ex);
                    throw new RuntimeException("MockServer startup failure: " + ex.getMessage(), ex);
                }
            }
        }

        @Override
        public void stop() {
            logger().info("Shutting down MockServer container");
            try {
                Thread.sleep(3000); // Increased delay to allow pending requests to complete
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            synchronized (lock) {
                if (mockServerClient != null) {
                    try {
                        logger().info("Resetting MockServerClient expectations");
                        try {
                            mockServerClient.reset();
                        } catch (Exception e) {
                            logger().warn("Error resetting MockServerClient expectations: {}", e.getMessage(), e);
                        }

                        logger().info("Closing MockServerClient");
                        mockServerClient.close();
                        logger().info("MockServerClient closed successfully");
                    } catch (Exception e) {
                        logger().warn("Error closing MockServerClient: {}", e.getMessage(), e);
                    } finally {
                        mockServerClient = null;
                    }
                } else {
                    logger().info("MockServerClient was already null, nothing to close");
                }
            }

            try {
                Thread.sleep(2000); // Additional delay after client close before container stop
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            super.stop();
            logger().info("MockServer container shutdown completed");
        }

    }
}
