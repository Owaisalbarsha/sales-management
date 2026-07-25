package com.salesmanagement.systemconfig.internal.service;

import com.salesmanagement.shared.exception.BusinessException;
import com.salesmanagement.systemconfig.internal.dto.ConfigResponse;
import com.salesmanagement.systemconfig.internal.dto.UpsertConfigRequest;
import com.salesmanagement.systemconfig.internal.entity.SystemConfig;
import com.salesmanagement.systemconfig.internal.entity.ValueType;
import com.salesmanagement.systemconfig.internal.repository.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * The only writer and the only reader of {@code system_config}. Two responsibilities:
 * <ol>
 *   <li><strong>Write (admin):</strong> validate a submitted value against its declared type, then
 *       upsert by key.</li>
 *   <li><strong>Read (consumers, via {@code ConfigFacade}):</strong> parse the stored override,
 *       returning empty when the key is unset or the value is unparseable so the caller falls back to
 *       its code default.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class SystemConfigService {

    private final SystemConfigRepository repository;

    // ── READ (consumer path, called through ConfigFacade) ────────────────────

    /**
     * The overridden int for a key, or empty if unset or unparseable.
     *
     * <p>Read-only transaction. Empty is a normal result, never an exception: a missing key means the
     * consumer keeps its code default, and an unparseable value fails safe to the same default rather
     * than throwing on a read path that other modules depend on. A value that reached the table
     * should already be valid (validated on write); the parse guard here is defence in depth against a
     * hand-edited row or a seeder mistake.</p>
     */
    @Transactional(readOnly = true)
    public Optional<Integer> getInt(String key) {
        return repository.findByConfigKey(key)
                .flatMap(cfg -> parseIntOrEmpty(cfg.getConfigValue()));
    }

    // ── READ (admin management list) ─────────────────────────────────────────

    /** Every override, key-ordered, for the admin dashboard table. */
    @Transactional(readOnly = true)
    public List<ConfigResponse> listAll() {
        return repository.findAllByOrderByConfigKeyAsc().stream()
                .map(ConfigResponse::from)
                .toList();
    }

    /** One override by key for the admin view, 404 if the key has never been overridden. */
    @Transactional(readOnly = true)
    public ConfigResponse getByKey(String key) {
        return repository.findByConfigKey(key)
                .map(ConfigResponse::from)
                .orElseThrow(() -> BusinessException.notFound(
                        "No override set for config key '" + key + "'",
                        "CONFIG_KEY_NOT_SET"));
    }

    // ── WRITE (admin path, called through the controller) ────────────────────

    /**
     * Sets (creates or replaces) the override for a key.
     *
     * <p>Validates {@code value_type} is supported and that {@code value} parses as that type BEFORE
     * touching the DB — a bad edit is a 400 at this endpoint, never a crash inside a consuming module.
     * Upserts by mutating the surviving row in place when the key already exists rather than
     * delete-and-reinsert: even for a single row that avoids the Hibernate INSERT-before-DELETE
     * ordering that collides with {@code uq_system_config_key}. Explicit {@code save()} in both
     * branches — dirty checking is not relied on anywhere in this system.</p>
     */
    @Transactional
    public ConfigResponse upsert(String key, UpsertConfigRequest request) {
        ValueType type = parseValueType(request.valueType());
        validateValueParses(request.value(), type);

        SystemConfig entity = repository.findByConfigKey(key)
                .map(existing -> {
                    // mutate in place — do NOT clear/reinsert (UNIQUE collision trap)
                    existing.setConfigValue(request.value());
                    existing.setValueType(type.name());
                    existing.setDescription(request.description());
                    return existing;
                })
                .orElseGet(() -> new SystemConfig(
                        key, request.value(), type.name(), request.description()));

        return ConfigResponse.from(repository.save(entity)); // explicit save, both branches
    }

    // ── validation helpers ───────────────────────────────────────────────────

    private ValueType parseValueType(String raw) {
        try {
            return ValueType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw BusinessException.badRequest(
                    "Unsupported value_type '" + raw + "'. Supported: INT",
                    "CONFIG_UNSUPPORTED_TYPE");
        }
    }

    private void validateValueParses(String value, ValueType type) {
        if (type == ValueType.INT && parseIntOrEmpty(value).isEmpty()) {
            throw BusinessException.badRequest(
                    "Value '" + value + "' is not a valid INT",
                    "CONFIG_VALUE_NOT_INT");
        }
    }

    private Optional<Integer> parseIntOrEmpty(String value) {
        try {
            return Optional.of(Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
