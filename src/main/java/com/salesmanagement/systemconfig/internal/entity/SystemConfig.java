package com.salesmanagement.systemconfig.internal.entity;

import com.salesmanagement.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One admin-overridden runtime setting.
 *
 * <p>This is an <em>override</em> row: it exists only because an admin changed a value away from its
 * compiled-in default. The absence of a row for a key is the normal, expected state and means
 * "use the code default" — see {@code ConfigFacade}. The table therefore starts empty (V12) and a
 * wiped table degrades to documented default behaviour rather than failing.</p>
 *
 * <p>Extends {@link BaseEntity} like every other entity in the system (surrogate {@code Long id},
 * {@code created_at}/{@code updated_at} maintained by JPA lifecycle callbacks). The ERD modelled the
 * key as the natural PK; we keep the surrogate id for BaseEntity consistency and make
 * {@code config_key} a {@code UNIQUE} column instead. {@code updated_at} is the ERD's
 * {@code LastUpdated}.</p>
 *
 * <p>Column names are {@code config_key} / {@code config_value} because {@code key} and {@code value}
 * are SQL reserved words.</p>
 */
@Entity
@Table(name = "system_config")
@Getter
@Setter
@NoArgsConstructor
public class SystemConfig extends BaseEntity {

    /**
     * The setting name (ERD Key), e.g. {@code TRACKING_ACTIVE_WINDOW_MINUTES}. Unique across the
     * table; the service upserts by this value.
     */
    @Column(name = "config_key", nullable = false, unique = true, length = 100)
    private String configKey;

    /**
     * The overridden value, stored as text regardless of {@link #valueType}. Parsed on read by the
     * facade and validated on write by the service against {@code valueType}.
     */
    @Column(name = "config_value", nullable = false, length = 255)
    private String configValue;

    /**
     * Declared type of {@link #configValue}. {@code INT} only today. Validated on write so a consumer
     * never receives an unparseable value.
     */
    @Column(name = "value_type", nullable = false, length = 20)
    private String valueType;

    /**
     * Optional human-facing note describing what this key controls (ERD Description).
     */
    @Column(name = "description", length = 500)
    private String description;

    public SystemConfig(String configKey, String configValue, String valueType, String description) {
        this.configKey = configKey;
        this.configValue = configValue;
        this.valueType = valueType;
        this.description = description;
    }
}
