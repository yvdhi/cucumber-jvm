package io.cucumber.core.plugin;

import io.cucumber.core.options.CurlOption;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;

import static io.cucumber.core.plugin.UrlOutputStreamTest.makeStringWithEmoji;

public class CucumberReportsAcceptanceTest {
    @Test
    void sends_10Mb_body_to_s3() throws IOException {
        String requestBody = makeStringWithEmoji(10 * 1024 * 1024);
        CurlOption url = CurlOption.parse("https://messages.cucumber.io/api/reports");

        OutputStream out = new UrlOutputStream(url, new UrlReporter(System.out));
        Writer w = new UTF8OutputStreamWriter(out);
        w.write(requestBody);
        w.flush();
        w.close();
    }
}
