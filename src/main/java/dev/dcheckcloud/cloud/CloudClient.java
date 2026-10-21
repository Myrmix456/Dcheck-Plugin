package dev.dcheckcloud.cloud;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CloudClient {
    private final String baseUrl;
    private final String key;
    private final HttpClient http;
    private final Duration timeout;

    public CloudClient(String baseUrl, String key, int timeoutMs) {
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        this.baseUrl = normalized;
        this.key = key == null ? ""
                : key.replaceAll("[\\s\\uFEFF\\u200B\\u200C\\u200D\\u00A0]", "")
                     .toUpperCase(java.util.Locale.ROOT);
        this.timeout = Duration.ofMillis(Math.max(500, timeoutMs));
        this.http = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean hasKey() {
        return !key.isEmpty();
    }

    public record VerifyResult(boolean ok, String name, long expiresAt, int daysLeft, String error) {
    }

    public VerifyResult verify() throws IOException, InterruptedException {
        HttpResponse<String> response = post("/api/verify", "text/plain", new byte[0]);
        String body = response.body();
        if (response.statusCode() == 200 && bool(body, "ok")) {
            return new VerifyResult(true, str(body, "name"), (long) num(body, "expiresAt"),
                    (int) num(body, "daysLeft"), "");
        }
        return new VerifyResult(false, "", 0, 0, errorOf(body, response.statusCode()));
    }

    public record UploadResult(boolean ok, String status, String error) {
    }

    public UploadResult uploadSession(String name, String csv) throws IOException, InterruptedException {
        String query = "?name=" + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8);
        HttpResponse<String> response = post("/api/sessions" + query, "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        String body = response.body();
        if (response.statusCode() != 200 || !bool(body, "ok")) {
            return new UploadResult(false, "", errorOf(body, response.statusCode()));
        }
        return new UploadResult(true, str(body, "status"), "");
    }

    public double infer(float[] sequence, int rows, int features) throws IOException, InterruptedException {
        ByteBuffer buffer = ByteBuffer.allocate(4 + rows * features * 4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(rows);
        for (int i = 0; i < rows * features; i++) {
            buffer.putFloat(sequence[i]);
        }
        HttpResponse<String> response = post("/api/infer", "application/octet-stream", buffer.array());
        String body = response.body();
        if (response.statusCode() != 200 || !bool(body, "ok")) {
            throw new IOException(errorOf(body, response.statusCode()));
        }
        return num(body, "p");
    }

    private HttpResponse<String> post(String path, String contentType, byte[] body)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("x-dcheck-key", key)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String errorOf(String body, int status) {
        String error = str(body, "error");
        if (error == null || error.isEmpty()) {
            error = "HTTP " + status;
        }
        return error;
    }

    private static String str(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static double num(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?[0-9.eE+]+|null)")
                .matcher(json);
        if (!m.find() || m.group(1).equals("null")) {
            return 0.0;
        }
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private static boolean bool(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false)")
                .matcher(json);
        return m.find() && m.group(1).equals("true");
    }
}
