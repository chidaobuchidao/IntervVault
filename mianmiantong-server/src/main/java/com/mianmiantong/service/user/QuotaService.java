package com.mianmiantong.service.user;

import com.mianmiantong.config.JwtAuthFilter;
import com.mianmiantong.entity.user.User;
import com.mianmiantong.entity.user.UserAiConfig;
import com.mianmiantong.mapper.user.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Unified daily quota management.
 * Regular users consume the system quota; admins and users with their own API key are unlimited.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    private final UserMapper userMapper;
    private final UserAiConfigService userAiConfigService;

    /**
     * Check and consume quota. Pro models cost two units, other models cost one.
     */
    public void checkAndConsume(Long userId, String model) {
        if (userId == null) return;
        if (JwtAuthFilter.isAdmin()) return;

        UserAiConfig config = userAiConfigService.getByUserId(userId);
        if (hasApiKey(config)) return;

        User user = userMapper.selectById(userId);
        if (user == null) throw new QuotaExhaustedException("用户不存在: userId=" + userId);

        refreshDailyQuota(user);

        int daily = dailyQuota(user);
        int used = quotaUsed(user);
        int steps = isProModel(model) ? 2 : 1;

        if (used + steps > daily) {
            throw new QuotaExhaustedException("今日免费次数不足（剩余 " + Math.max(0, daily - used) + " 次，需 " + steps + " 次），请配置 AI API Key 后无限使用");
        }

        int updated = userMapper.incrementQuota(userId, steps);
        if (updated == 0) {
            throw new QuotaExhaustedException("今日免费次数不足（剩余 " + Math.max(0, daily - used) + " 次，需 " + steps + " 次），请配置 AI API Key 后无限使用");
        }
    }

    /**
     * Check quota without consuming it. Admins and users with their own key are unlimited.
     */
    public String checkQuota() {
        Long userId = JwtAuthFilter.getCurrentUserId();
        if (userId == null) return null;
        if (JwtAuthFilter.isAdmin()) return null;

        UserAiConfig config = userAiConfigService.getByUserId(userId);
        if (hasApiKey(config)) return null;

        User user = userMapper.selectById(userId);
        if (user == null) throw new QuotaExhaustedException("用户不存在: userId=" + userId);

        refreshDailyQuota(user);

        int daily = dailyQuota(user);
        int used = quotaUsed(user);
        if (used >= daily) {
            throw new QuotaExhaustedException("今日免费次数已用完（" + daily + "次/天），请配置 AI API Key 后无限使用");
        }
        return null;
    }

    /** Get quota information for the current user-facing display. */
    public QuotaInfo getQuotaInfo(Long userId) {
        if (userId == null) return new QuotaInfo(true, 0, 0, 0);

        if (JwtAuthFilter.isAdmin()) {
            return new QuotaInfo(true, 0, 0, -1);
        }

        UserAiConfig config = userAiConfigService.getByUserId(userId);
        if (hasApiKey(config)) {
            return new QuotaInfo(true, 0, 0, -1);
        }

        User user = userMapper.selectById(userId);
        if (user == null) return new QuotaInfo(false, 0, 0, 0);

        refreshDailyQuota(user);

        int daily = dailyQuota(user);
        int used = quotaUsed(user);
        int remaining = Math.max(0, daily - used);
        return new QuotaInfo(false, daily, used, remaining);
    }

    /** Reset stale daily usage and persist it so all display endpoints see the same quota state. */
    public void refreshDailyQuota(User user) {
        if (user == null) return;
        LocalDate today = LocalDate.now();
        if (!today.equals(user.getQuotaDate())) {
            user.setQuotaUsed(0);
            user.setQuotaDate(today);
            userMapper.updateById(user);
        }
    }

    private boolean hasApiKey(UserAiConfig config) {
        return config != null && config.getApiKey() != null && !config.getApiKey().isBlank();
    }

    private boolean isProModel(String model) {
        if (model == null) return false;
        String lower = model.toLowerCase();
        return lower.contains("pro");
    }

    private int dailyQuota(User user) {
        return user.getDailyQuota() != null ? user.getDailyQuota() : 10;
    }

    private int quotaUsed(User user) {
        return user.getQuotaUsed() != null ? user.getQuotaUsed() : 0;
    }

    public static class QuotaExhaustedException extends RuntimeException {
        public QuotaExhaustedException(String message) { super(message); }
    }

    public record QuotaInfo(boolean unlimited, int dailyQuota, int quotaUsed, int quotaRemaining) {}
}
