package com.urbanpulse.service;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import com.urbanpulse.entity.ComplaintStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    /**
     * Asynchronously sends a push notification to the citizen device using Firebase Admin SDK.
     * Guaranteed failure-isolated: Any FCM network failure or invalid token exception is caught
     * and logged, NEVER propagating an exception back to the caller or failing the complaint transaction.
     */
    @Async
    public void sendComplaintStatusNotification(String fcmToken, String complaintNumber, ComplaintStatus newStatus, String departmentName) {
        if (fcmToken == null || fcmToken.trim().isEmpty()) {
            logger.debug("No FCM token registered for complaint {}; skipping push notification.", complaintNumber);
            return;
        }

        try {
            if (FirebaseApp.getApps().isEmpty()) {
                logger.warn("FirebaseApp is not initialized; skipping push notification for complaint {}", complaintNumber);
                return;
            }

            String title = "UrbanPulse: Complaint Update";
            String body = buildNotificationBody(complaintNumber, newStatus, departmentName);

            Message message = Message.builder()
                    .setToken(fcmToken.trim())
                    .setNotification(Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build())
                    .putData("complaintNumber", complaintNumber != null ? complaintNumber : "")
                    .putData("status", newStatus != null ? newStatus.name() : "")
                    .build();

            String response = FirebaseMessaging.getInstance().send(message);
            logger.info("FCM push notification sent successfully for complaint {} (Status: {}). Message ID: {}",
                    complaintNumber, newStatus, response);

        } catch (Exception ex) {
            logger.warn("FCM push notification delivery failed for complaint {} (Status: {}): {}. Complaint status update remains successful.",
                    complaintNumber, newStatus, ex.getMessage());
        }
    }

    private String buildNotificationBody(String complaintNumber, ComplaintStatus newStatus, String departmentName) {
        if (newStatus == null) {
            return "Your complaint " + complaintNumber + " has an update.";
        }
        switch (newStatus) {
            case SUBMITTED:
                return "Your complaint " + complaintNumber + " has been submitted successfully.";
            case FORWARDED:
                return "Your complaint " + complaintNumber + " has been forwarded to the " + (departmentName != null ? departmentName : "department") + ".";
            case ASSIGNED:
                return "Your complaint " + complaintNumber + " has been assigned to a field worker.";
            case IN_PROGRESS:
                return "Work has started on your complaint " + complaintNumber + ".";
            case RESOLVED:
                return "Your complaint " + complaintNumber + " has been resolved. Open app to view resolution proof.";
            case REJECTED:
                return "Your complaint " + complaintNumber + " could not be resolved.";
            case CLOSED:
                return "Your complaint " + complaintNumber + " has been closed.";
            default:
                return "Your complaint " + complaintNumber + " status updated to " + newStatus + ".";
        }
    }
}
