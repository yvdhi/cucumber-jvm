package io.cucumber.core.plugin;

import io.cucumber.core.options.CurlOption;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;

import static io.cucumber.core.plugin.UrlOutputStreamTest.bytes;

public class CucumberReportsAcceptanceTest {
    @Test
    void sends_10Mb_to_s3() throws IOException {
        byte[] requestBody = bytes(10 * 1024 * 1024);
        CurlOption url = CurlOption.parse("https://messages.cucumber.io/api/reports");

        OutputStream out = new UrlOutputStream(url, new UrlReporter(System.out));
        out.write(requestBody);
        out.flush();
        out.close();
    }
}
