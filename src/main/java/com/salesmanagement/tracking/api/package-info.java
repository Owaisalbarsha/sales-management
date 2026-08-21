/**
 * Public API surface of the {@code tracking} module — the only package other modules may import
 * from tracking.
 *
 * <p>Contains {@code TrackingFacade} and the read models it exchanges. It does <em>not</em>
 * contain HTTP controllers: {@code TrackingController} lives in {@code internal}, because this
 * package is about inter-module Java calls, not the REST surface.</p>
 *
 * <p><strong>Who will call in here.</strong> Nobody today. {@code sync} (V12+) will call
 * {@link com.salesmanagement.tracking.api.TrackingFacade#ingest} to hand over batched offline
 * GPS points, and {@code reporting} will likely want
 * {@link com.salesmanagement.tracking.api.TrackingFacade#getLatestLocation}. The surface is
 * declared now because retrofitting a facade once dependents exist is expensive, and because
 * {@code sync} needs a <em>synchronous</em> pass/fail answer to set its own {@code SyncStatus} and
 * {@code RetryCount} — something a fire-and-forget event cannot give it.</p>
 *
 * <p><strong>Outbound edges:</strong> tracking depends only on {@code identity}
 * ({@code UserFacade}). It publishes no cross-module events: geofencing, heatmaps and movement
 * analytics are out of scope per SRS 4.5.1, so nothing would listen.</p>
 */
@org.springframework.modulith.NamedInterface("api")
package com.salesmanagement.tracking.api;
