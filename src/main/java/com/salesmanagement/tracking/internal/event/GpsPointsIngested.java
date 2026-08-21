package com.salesmanagement.tracking.internal.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Module-internal event: a batch was ingested and this is the freshest point it contained.
 *
 * <p><strong>Internal, not a Modulith event.</strong> This type lives in {@code internal}, not
 * {@code api}, and is consumed by a plain {@code @TransactionalEventListener} inside the same
 * module. It is emphatically <em>not</em> annotated for {@code @ApplicationModuleListener}
 * consumption: that would write a row to the event publication log for every ingest, roughly
 * 24,000 pointless rows a day, to notify a listener sitting three classes away in the same JVM.
 * Tracking publishes no cross-module events at all (D41).</p>
 *
 * <p><strong>Why one point and not the whole batch.</strong> The live map shows where each rep
 * <em>is</em>. Replaying a 480-point offline backlog through SSE would animate a marker across the
 * city over several seconds and land it exactly where a single event would have put it
 * immediately. History belongs to the trail endpoint, which is bounded, ordered, and built for it.
 * So the service publishes only the newest accepted fix per ingest.</p>
 *
 * <p>Fields are already-normalised values copied out of the persisted entity, not the entity
 * itself. The listener runs after commit, where an entity reference would be detached and any lazy
 * access would fail outside the transaction.</p>
 *
 * @param representativeId the rep whose position moved
 * @param latitude         WGS84 latitude of the freshest accepted fix
 * @param longitude        WGS84 longitude of the freshest accepted fix
 * @param recordedAt       capture instant of that fix
 */
public record GpsPointsIngested(
        Long       representativeId,
        BigDecimal latitude,
        BigDecimal longitude,
        Instant    recordedAt
) {}
