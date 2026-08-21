package com.salesmanagement.systemconfig.internal.web;

import com.salesmanagement.shared.api.ApiResponse;
import com.salesmanagement.systemconfig.internal.dto.ConfigResponse;
import com.salesmanagement.systemconfig.internal.dto.UpsertConfigRequest;
import com.salesmanagement.systemconfig.internal.service.SystemConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin config management endpoints.
 *
 * <p>Authorization is on the controller only (@PreAuthorize), per the system's hard rule; there is no
 * per-row ownership concept for config, so no ownership guard is needed in the service. Two audiences:
 * <ul>
 *   <li><strong>Write</strong> ({@code PUT}) — ADMIN only. The ERD and {@code UserRole} both put
 *       system config under ADMIN.</li>
 *   <li><strong>Read</strong> ({@code GET}) — ADMIN and SALES_MANAGER, the dashboard audience. Reps
 *       do not read config over HTTP (the client GPS interval stayed client-side), so no rep-facing
 *       read endpoint exists.</li>
 * </ul>
 *
 * <p>Cross-module reads do NOT come through here — they go through {@code ConfigFacade}, which carries
 * no HTTP guard because it is an internal call, not a request.</p>
 *
 * <p>The write verb is PUT, not POST: setting a key is idempotent and keyed by the path variable
 * (create-or-replace), so the same request repeated is a no-op change, which is exactly PUT's
 * contract.</p>
 */
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class SystemConfigController {

    private final SystemConfigService service;

    /** All overrides, key-ordered, for the admin dashboard table. */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ApiResponse<List<ConfigResponse>> list() {
        return ApiResponse.ok(service.listAll());
    }

    /** One override by key. 404 if the key has never been overridden. */
    @GetMapping("/{key}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SALES_MANAGER')")
    public ApiResponse<ConfigResponse> getByKey(@PathVariable String key) {
        return ApiResponse.ok(service.getByKey(key));
    }

    /** Set (create or replace) the override for a key. ADMIN only. */
    @PutMapping("/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ConfigResponse> upsert(@PathVariable String key,
                                              @Valid @RequestBody UpsertConfigRequest request) {
        return ApiResponse.ok(service.upsert(key, request), "Configuration updated");
    }
}
