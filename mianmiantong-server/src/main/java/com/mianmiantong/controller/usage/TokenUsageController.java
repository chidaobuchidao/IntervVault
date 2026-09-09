package com.mianmiantong.controller.usage;

import com.mianmiantong.common.Result;
import com.mianmiantong.config.JwtAuthFilter;
import com.mianmiantong.service.ai.usage.TokenUsageResponse;
import com.mianmiantong.service.ai.usage.TokenUsageService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
public class TokenUsageController {
    private final TokenUsageService service;

    public TokenUsageController(TokenUsageService service) {
        this.service = service;
    }

    @GetMapping("/api/user/token-usage")
    public ResponseEntity<Result<TokenUsageResponse>> personal(
            @RequestParam(name = "days", defaultValue = "7") int days,
            @RequestParam(name = "model", required = false) String model,
            @RequestParam(name = "feature", required = false) String feature,
            @RequestParam(name = "userId", required = false) String requestedUserId,
            @RequestParam(name = "keySource", required = false) String keySource) {
        Long userId = authenticatedUserId();
        if (userId == null) return failure(401, "请先登录");
        if (requestedUserId != null || keySource != null) {
            return failure(400, "个人用量接口不支持 userId 或 keySource 参数");
        }
        return ResponseEntity.ok(Result.ok(service.personal(userId, days, model, feature)));
    }

    @GetMapping("/api/admin/token-usage")
    public ResponseEntity<Result<TokenUsageResponse>> admin(
            @RequestParam(name = "days", defaultValue = "7") int days,
            @RequestParam(name = "model", required = false) String model,
            @RequestParam(name = "feature", required = false) String feature,
            @RequestParam(name = "userId", required = false) String requestedUserId,
            @RequestParam(name = "keySource", required = false) String keySource) {
        if (authenticatedUserId() == null) return failure(401, "请先登录");
        if (!JwtAuthFilter.isAdmin()) return failure(403, "无管理员权限");
        Long userId = null;
        if (requestedUserId != null) {
            try {
                userId = Long.valueOf(requestedUserId);
            } catch (NumberFormatException exception) {
                return failure(400, "userId 必须为正整数");
            }
        }
        return ResponseEntity.ok(Result.ok(service.admin(days, model, feature, userId, keySource)));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<TokenUsageResponse>> malformedParameter(MethodArgumentTypeMismatchException exception) {
        return failure(400, exception.getName() + " 参数格式不正确");
    }

    private static Long authenticatedUserId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        Long userId = JwtAuthFilter.getCurrentUserId();
        return authentication != null && authentication.isAuthenticated() && userId != null && userId > 0
                ? userId : null;
    }

    private static ResponseEntity<Result<TokenUsageResponse>> failure(int code, String message) {
        return ResponseEntity.status(code).body(Result.fail(code, message));
    }
}
