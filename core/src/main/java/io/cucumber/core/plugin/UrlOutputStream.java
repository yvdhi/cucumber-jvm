package io.cucumber.core.plugin;

import io.cucumber.core.options.CurlOption;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.newOutputStream;
import static java.util.Objects.requireNonNull;

class UrlOutputStream extends OutputStream {

    private final UrlReporter urlReporter;

    private final CurlOption option;
    private final Path temp;
    private final OutputStream tempOutputStream;

    UrlOutputStream(CurlOption option, UrlReporter urlReporter) throws IOException {
        this.option = requireNonNull(option);
        this.urlReporter = urlReporter;
        this.temp = Files.createTempFile("cucumber", null);
        this.tempOutputStream = newOutputStream(temp);
    }

    @Override
    public void write(int b) throws IOException {
        tempOutputStream.write(b);
    }

    @Override
    public void write(byte[] buffer) throws IOException {
        tempOutputStream.write(buffer);
    }

    @Override
    public void write(byte[] buffer, int offset, int count) throws IOException {
        tempOutputStream.write(buffer, offset, count);
    }

    @Override
    public void flush() throws IOException {
        tempOutputStream.flush();
    }

    @Override
    public void close() throws IOException {
        tempOutputStream.close();

        URL urlOne = option.getUri().toURL();
        HttpURLConnection urlConnectionOne = (HttpURLConnection) urlOne.openConnection();
        for (Entry<String, String> header : option.getHeaders()) {
            urlConnectionOne.setRequestProperty(header.getKey(), header.getValue());
        }
        urlConnectionOne.setInstanceFollowRedirects(false);
        urlConnectionOne.setRequestMethod(option.getMethod().name());
        urlConnectionOne.setDoOutput(false);

        handleResponse(urlConnectionOne, urlConnectionOne.getRequestProperties());

        if (isRedirect(urlConnectionOne.getResponseCode())) {
            if (urlReporter != null) {
                urlReporter.report(urlConnectionOne.getURL());
            }
        } else {
            URL urlTwo = new URL(urlConnectionOne.getHeaderField("Location"));
            HttpURLConnection urlConnectionTwo = (HttpURLConnection) urlTwo.openConnection();
            for (Entry<String, String> header : option.getHeaders()) {
                urlConnectionTwo.setRequestProperty(header.getKey(), header.getValue());
            }
            urlConnectionOne.setInstanceFollowRedirects(true);
            urlConnectionOne.setRequestMethod(option.getMethod().name());
            urlConnectionOne.setDoOutput(true);

            try (OutputStream outputStream = urlConnectionTwo.getOutputStream()) {
                Files.copy(temp, outputStream);
                handleResponse(urlConnectionTwo, urlConnectionOne.getRequestProperties());
            }
            if (urlReporter != null) {
                urlReporter.report(urlConnectionTwo.getURL());
            }
        }
    }

    private boolean isRedirect(int responseCode) {
        return responseCode == 301 ||
                responseCode == 302 ||
                responseCode == 303 ||
                responseCode == 307 ||
                responseCode == 308;
    }

    private static void handleResponse(HttpURLConnection urlConnection, Map<String, List<String>> requestHeaders)
            throws IOException {
        Map<String, List<String>> responseHeaders = urlConnection.getHeaderFields();
        int responseCode = urlConnection.getResponseCode();
        boolean success = 200 <= responseCode && responseCode < 300;

        InputStream inputStream = urlConnection.getErrorStream() != null ? urlConnection.getErrorStream()
                : urlConnection.getInputStream();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, UTF_8))) {
            String responseBody = br.lines().collect(Collectors.joining(System.lineSeparator()));
            if (!success) {
                String method = urlConnection.getRequestMethod();
                URL url = urlConnection.getURL();
                throw createCurlLikeException(method, url, requestHeaders, responseHeaders, responseBody);
            }
        }
    }

    static IOException createCurlLikeException(
            String method,
            URL url,
            Map<String, List<String>> requestHeaders,
            Map<String, List<String>> responseHeaders,
            String responseBody
    ) {
        return new IOException(String.format(
                "%s:\n> %s %s%s%s%s",
                "HTTP request failed",
                method,
                url,
                headersToString("> ", requestHeaders),
                headersToString("< ", responseHeaders),
                responseBody));
    }

    private static String headersToString(String prefix, Map<String, List<String>> headers) {
        return headers
                .entrySet()
                .stream()
                .flatMap(header -> header
                        .getValue()
                        .stream()
                        .map(value -> {
                            if (header.getKey() == null) {
                                return prefix + value;
                            } else if (header.getValue() == null) {
                                return prefix + header.getKey();
                            } else {
                                return prefix + header.getKey() + ": " + value;
                            }
                        }))
                .collect(Collectors.joining("\n", "", "\n"));
    }

}
