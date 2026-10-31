package com.example.board;

import com.example.board.domain.Post;
import com.example.board.domain.User;
import com.example.board.repository.PostRepository;
import com.example.board.repository.UserRepository;
import com.example.board.service.PostService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.EntityManager;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/** Trusted adapter: real Spring service + JPA, fresh rollback transaction per example. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:invariant;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
}, classes = BoardApplication.class)
class PrGuardInvariantBridgeTest {
    @Autowired PostService service;
    @Autowired PostRepository posts;
    @Autowired UserRepository users;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void driveHypothesis() throws Exception {
        // Web security requires an HttpSecurity bean, so use a servlet context without listening publicly.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/case", exchange -> {
            int status = 200;
            Map<String, Object> result;
            try {
                var input = json.readTree(exchange.getRequestBody());
                result = tx.execute(transaction -> {
                    transaction.setRollbackOnly();
                    User owner = users.saveAndFlush(new User("owner@example.test", "owner", "USER"));
                    users.saveAndFlush(new User("other@example.test", "other", "USER"));
                    Post post = posts.saveAndFlush(new Post(owner, "original", "original"));
                    long id = post.getId();
                    long before = owner.getId();
                    service.update(id, owner, input.path("title").asText(), input.path("content").asText());
                    em.flush();
                    em.clear();
                    Post after = posts.findById(id).orElseThrow();
                    Map<String, Object> observation = new LinkedHashMap<>();
                    observation.put("status", 200);
                    observation.put("author_before", before);
                    observation.put("author_after", after.getAuthor().getId());
                    return observation;
                });
            } catch (Exception e) {
                status = 500;
                result = Map.of("error", e.getClass().getSimpleName());
            }
            byte[] body = json.writeValueAsBytes(result);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Process child = new ProcessBuilder(System.getenv("PRGUARD_PYTHON"), System.getenv("PRGUARD_SIDE_DRIVER"),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/case")
                    .inheritIO().start();
            if (!child.waitFor(90, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                throw new IllegalStateException("Hypothesis side timed out");
            }
            if (child.exitValue() != 0) throw new IllegalStateException("Hypothesis adapter failed");
        } finally {
            server.stop(0);
        }
    }
}
