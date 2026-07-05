package eu.wohlben.qits.domain.daemon.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import eu.wohlben.qits.domain.daemon.control.DaemonEventService;
import eu.wohlben.qits.domain.daemon.dto.DaemonEventDto;
import eu.wohlben.qits.domain.daemon.entity.DaemonEventKind;
import eu.wohlben.qits.domain.daemon.entity.DaemonEventSeverity;
import eu.wohlben.qits.domain.daemon.entity.DaemonStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The durable event feed: published events land as {@code daemon_event} rows (no in-memory ring
 * anywhere in the path) and come back from {@code GET /api/daemon-events} newest first, narrowed by
 * the severity/source/workspace filters.
 */
@QuarkusTest
public class DaemonEventControllerTest {

  @Inject DaemonEventService daemonEventService;

  private static DaemonEventDto event(
      String repoId,
      DaemonEventKind kind,
      DaemonEventSeverity severity,
      String summary,
      String source,
      Long anchorFrom,
      Long anchorTo,
      Instant at) {
    return new DaemonEventDto(
        repoId,
        "work",
        "daemon-1",
        "dev-server",
        kind,
        severity,
        DaemonStatus.READY,
        summary,
        "excerpt of " + summary,
        "cmd-1",
        source,
        anchorFrom,
        anchorTo,
        source != null && !source.equals("output") ? at : null,
        at);
  }

  @Test
  public void servesPersistedEventsNewestFirstWithFilters() {
    // A unique repo id isolates this test's events from anything else in the shared DB.
    String repoId = "evt-repo-" + UUID.randomUUID();
    Instant base = Instant.now();
    daemonEventService.publish(
        event(
            repoId,
            DaemonEventKind.STATUS_CHANGED,
            DaemonEventSeverity.INFO,
            "ready (pattern matched)",
            null,
            null,
            null,
            base.minusSeconds(60)));
    daemonEventService.publish(
        event(
            repoId,
            DaemonEventKind.ERROR_DETECTED,
            DaemonEventSeverity.ERROR,
            "NullPointerException: boom",
            "logs/app.log",
            12L,
            14L,
            base));

    given()
        .get("/api/daemon-events?repoId=" + repoId + "&workspaceId=work")
        .then()
        .statusCode(200)
        .body("events.size()", equalTo(2))
        .body("events[0].summary", equalTo("NullPointerException: boom"))
        .body("events[0].source", equalTo("logs/app.log"))
        .body("events[0].anchorFrom", equalTo(12))
        .body("events[0].anchorTo", equalTo(14))
        .body("events[1].summary", equalTo("ready (pattern matched)"));

    given()
        .get("/api/daemon-events?repoId=" + repoId + "&severity=ERROR")
        .then()
        .statusCode(200)
        .body("events.size()", equalTo(1))
        .body("events[0].severity", equalTo("ERROR"));

    given()
        .get("/api/daemon-events?repoId=" + repoId + "&source=logs/app.log")
        .then()
        .statusCode(200)
        .body("events.size()", equalTo(1));

    given()
        .get(
            "/api/daemon-events?repoId="
                + repoId
                + "&since="
                + base.minusSeconds(30)
                + "&workspaceId=work")
        .then()
        .statusCode(200)
        .body("events.size()", equalTo(1))
        .body("events[0].kind", equalTo("ERROR_DETECTED"));

    given()
        .get("/api/daemon-events?repoId=" + repoId + "&pageSize=1&page=1")
        .then()
        .statusCode(200)
        .body("events.size()", equalTo(1))
        .body("events[0].summary", equalTo("ready (pattern matched)"));
  }
}
