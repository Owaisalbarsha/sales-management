package com.salesmanagement.notification.internal.channel;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Initialises the Firebase Admin SDK once at startup, from a service-account
 * credential, so {@link FcmChannel} can call {@code FirebaseMessaging.getInstance()}.
 *
 * <p><strong>Config-gated (D2).</strong> Created only when
 * {@code notification.fcm.enabled=true}. With FCM off (the default for local dev,
 * CI, the seeder, and any environment without a Firebase project), this bean does
 * not exist, {@link FirebaseApp} is never initialised, and the application starts
 * normally with the in-app feed alone.</p>
 *
 * <p><strong>Credential.</strong> {@code notification.fcm.credentials-path} points
 * at the Firebase service-account JSON. That file is a secret — it must never be
 * committed to the repo; supply it via a host path or a mounted secret. If the
 * property is blank, the SDK falls back to Application Default Credentials
 * ({@code GOOGLE_APPLICATION_CREDENTIALS}), which is the standard cloud pattern.</p>
 *
 * <p>Initialisation failure is fatal <em>only when FCM is explicitly enabled</em>:
 * if you turned FCM on but the credential is missing or invalid, that is a
 * misconfiguration worth failing fast on, not something to hide. With FCM off this
 * class never runs, so it can never break startup.</p>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "notification.fcm.enabled", havingValue = "true")
public class FirebaseConfig {

    @Value("${notification.fcm.credentials-path:}")
    private String credentialsPath;

    /**
     * Initialise the default {@link FirebaseApp} once. Idempotent: if an app is
     * already initialised (e.g. in a test context), it is left as-is.
     *
     * @throws IllegalStateException if FCM is enabled but the SDK cannot initialise
     */
    @PostConstruct
    void init() {
        try {
            if (!FirebaseApp.getApps().isEmpty()) {
                log.info("FirebaseApp already initialised; reusing it.");
                return;
            }

            GoogleCredentials credentials = loadCredentials();
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build();
            FirebaseApp.initializeApp(options);
            log.info("FirebaseApp initialised for FCM push (credentials source: {}).",
                    (credentialsPath == null || credentialsPath.isBlank())
                            ? "application default" : credentialsPath);

        } catch (Exception e) {
            // FCM was explicitly enabled — a broken credential is a misconfiguration
            // we want surfaced loudly, not swallowed.
            throw new IllegalStateException(
                    "notification.fcm.enabled=true but the Firebase Admin SDK could not be "
                            + "initialised. Check notification.fcm.credentials-path or "
                            + "GOOGLE_APPLICATION_CREDENTIALS.", e);
        }
    }

    private GoogleCredentials loadCredentials() throws Exception {
        if (credentialsPath == null || credentialsPath.isBlank()) {
            // Application Default Credentials (GOOGLE_APPLICATION_CREDENTIALS env var).
            return GoogleCredentials.getApplicationDefault();
        }
        try (InputStream in = new FileInputStream(credentialsPath)) {
            return GoogleCredentials.fromStream(in);
        }
    }
}
