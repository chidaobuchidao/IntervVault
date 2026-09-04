package com.mianmiantong.service.coding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CodingService {

    /** Piston 代码执行超时（毫秒） */
    private static final int PISTON_RUN_TIMEOUT_MS = 3000;
    /** HTTP 连接超时（毫秒） */
    private static final int HTTP_CONNECT_TIMEOUT_MS = 5000;
    /** HTTP 读取超时（毫秒） */
    private static final int HTTP_READ_TIMEOUT_MS = 15000;

    @Value("${PISTON_API_URL:http://localhost:2000}")
    private String pistonUrl;

    private final ObjectMapper mapper = new ObjectMapper();

    public Map<String, Object> runCode(String code, String language, String stdin) {
        HttpURLConnection conn = null;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("language", getPistonLanguage(language));
            body.put("version", getPistonVersion(language));
            body.put("files", List.of(Map.of("name", resolveFileName(language, code), "content", code)));
            body.put("stdin", stdin != null && !stdin.isEmpty() ? stdin : "\n");
            body.put("run_timeout", PISTON_RUN_TIMEOUT_MS);

            String json = mapper.writeValueAsString(body);

            var url = URI.create(pistonUrl + "/api/v2/execute").toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_READ_TIMEOUT_MS);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            var inputStream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (inputStream == null) {
                return Map.of("error", "Piston 无响应, HTTP " + status);
            }
            String response = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);

            if (response != null && !response.isEmpty()) {
                return mapper.readValue(response, Map.class);
            }
            return Map.of("error", "空响应, HTTP " + status);
        } catch (java.net.SocketTimeoutException e) {
            return Map.of("error", "Piston 执行超时");
        } catch (java.net.ConnectException e) {
            return Map.of("error", "Piston 服务不可达");
        } catch (Exception e) {
            return Map.of("error", "执行失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static final Pattern JAVA_CLASS_PATTERN = Pattern.compile("public\\s+class\\s+(\\w+)");

    private String resolveFileName(String lang, String code) {
        return switch (lang) {
            case "java" -> resolveJavaFileName(code);
            case "python" -> "main.py";
            case "javascript", "js" -> "main.js";
            case "cpp", "c" -> "main.cpp";
            case "go" -> "main.go";
            default -> "code.txt";
        };
    }

    /** Extract the public class name from Java source to match filename requirement. */
    private String resolveJavaFileName(String code) {
        if (code != null) {
            Matcher m = JAVA_CLASS_PATTERN.matcher(code);
            if (m.find()) return m.group(1) + ".java";
        }
        return "Solution.java";
    }

    private String getPistonLanguage(String lang) {
        return switch (lang) {
            case "java" -> "java";
            case "python" -> "python";
            case "javascript", "js" -> "javascript";
            case "cpp", "c++" -> "c++";
            case "c" -> "c";
            case "go" -> "go";
            default -> lang;
        };
    }

    private String getPistonVersion(String lang) {
        return switch (lang) {
            case "java" -> "15.0.2";
            case "python" -> "3.10.0";
            case "javascript", "js" -> "18.15.0";
            case "cpp", "c++", "c" -> "10.2.0";
            case "go" -> "1.16.2";
            default -> "*";
        };
    }
}
