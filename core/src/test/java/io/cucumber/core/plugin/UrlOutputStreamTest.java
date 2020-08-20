package io.cucumber.core.plugin;

import io.cucumber.core.options.CurlOption;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.lang.String.format;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

@ExtendWith({VertxExtension.class})
public class UrlOutputStreamTest {

    private static final int TIMEOUT_SECONDS = 15;
    private int port;
    private Exception exception;

    @BeforeEach
    void randomPort() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        port = socket.getLocalPort();
        socket.close();
    }

    @Test
    void throws_exception_for_500_status(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        String requestBody = "";
        TestServer testServer = new TestServer(port, testContext, requestBody, HttpMethod.PUT, null, 500,
                "Oh noes");
        CurlOption option = CurlOption.parse(format("http://localhost:%d/storage", port));

        verifyRequest(option, testServer, vertx, testContext, requestBody);
        assertThat(testContext.awaitCompletion(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
        assertThat(exception.getMessage(), equalTo("HTTP request failed:\n" +
                "> PUT http://localhost:" + port + "/storage\n" +
                "< HTTP/1.1 500 Internal Server Error\n" +
                "< transfer-encoding: chunked\n" +
                "Oh noes"));
    }

    @Test
    void sends_empty_body_on_initial_request(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        String requestBody = "hello";
        TestServer testServer = new TestServer(port, testContext, "", HttpMethod.PUT, null, 200, "");
        CurlOption url = CurlOption.parse(format("http://localhost:%d/storage", port));
        verifyRequest(url, testServer, vertx, testContext, requestBody);

        assertThat(testContext.awaitCompletion(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
    }

    @Test
    void follows_307_temporary_redirects(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        String requestBody = "hello";
        TestServer testServer = new TestServer(port, testContext, requestBody, HttpMethod.PUT, null, 200, "");
        CurlOption url = CurlOption.parse(format("http://localhost:%d/redirect", port));
        verifyRequest(url, testServer, vertx, testContext, requestBody);

        assertThat(testContext.awaitCompletion(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
    }

    @Test
    void streams_request_body_in_chunks(Vertx vertx, VertxTestContext testContext) {
        String requestBody = makeStringWithEmoji(1024);
        TestServer testServer = new TestServer(port, testContext, requestBody, HttpMethod.PUT, null, 200, "");
        CurlOption url = CurlOption.parse(format("http://localhost:%d/redirect", port));
        verifyRequest(url, testServer, vertx, testContext, requestBody);
    }

    @Test
    void overrides_request_method(Vertx vertx, VertxTestContext testContext) {
        String requestBody = "";
        TestServer testServer = new TestServer(port, testContext, requestBody, HttpMethod.POST,
                "application/x-www-form-urlencoded", 200, "");
        CurlOption url = CurlOption.parse(format("http://localhost:%d -X POST", port));
        verifyRequest(url, testServer, vertx, testContext, requestBody);
    }

    @Test
    void sets_request_headers(Vertx vertx, VertxTestContext testContext) {
        String requestBody = "";
        TestServer testServer = new TestServer(port, testContext, requestBody, HttpMethod.PUT,
                "application/x-ndjson", 200, "");
        CurlOption url = CurlOption
                .parse(format("http://localhost:%d -H 'Content-Type: application/x-ndjson'", port));
        verifyRequest(url, testServer, vertx, testContext, requestBody);
    }

    private void verifyRequest(
            CurlOption url, TestServer testServer, Vertx vertx, VertxTestContext testContext, String requestBody
    ) {
        vertx.deployVerticle(testServer, testContext.succeeding(id -> {
            try {
                OutputStream out = new UrlOutputStream(url, null);
                Writer w = new UTF8OutputStreamWriter(out);
                w.write(requestBody);
                w.flush();
                w.close();
                testContext.completeNow();
            } catch (Exception e) {
                exception = e;
                testContext.completeNow();
            }
        }));
    }

    static String makeStringWithEmoji(int size) {
        String base = "abcå\uD83D\uDE02";
        int baseLength = base.length();
        String string = IntStream.range(0, size).mapToObj(i -> base.substring(i % baseLength, i % baseLength + 1))
                .collect(Collectors.joining());
        return string;
    }

    public static class TestServer extends AbstractVerticle {

        private final int port;
        private final VertxTestContext testContext;
        private final String expectedBody;
        private final HttpMethod expectedMethod;
        private final String expectedContentType;
        private final int statusCode;
        private final String responseBody;

        public TestServer(
                int port,
                VertxTestContext testContext,
                String expectedBody,
                HttpMethod expectedMethod,
                String expectedContentType,
                int statusCode,
                String responseBody
        ) {
            this.port = port;
            this.testContext = testContext;
            this.expectedBody = expectedBody;
            this.expectedMethod = expectedMethod;
            this.expectedContentType = expectedContentType;
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        @Override
        public void start(Promise<Void> startPromise) {
            Router router = Router.router(vertx);

            router.route("/redirect").handler(ctx -> {
                ctx.response().setStatusCode(307);
                ctx.response().headers().add("Location", "http://localhost:" + port + "/storage");
                ctx.response().end();
            });

            router.route("/storage").handler(ctx -> {
                ctx.response().setStatusCode(statusCode);
                testContext.verify(() -> {
                    assertThat(ctx.request().method(), is(equalTo(expectedMethod)));
                    assertThat(ctx.request().getHeader("Content-Type"), is(equalTo(expectedContentType)));

                    Buffer body = Buffer.buffer(0);
                    ctx.request().handler(body::appendBuffer);
                    ctx.request().endHandler(e -> {
                        String receivedBody = body.toString("utf-8");
                        ctx.response().setChunked(true);
                        ctx.response().write(responseBody);
                        ctx.response().end();
                        testContext.verify(() -> assertThat(receivedBody, is(equalTo(expectedBody))));
                    });
                });
            });
            vertx
                    .createHttpServer()
                    .requestHandler(router)
                    .listen(port, e -> startPromise.complete());
        }

    }

}
