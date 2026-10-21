package com.dbpulse.api;

import com.dbpulse.service.LocalTargetService;
import com.dbpulse.service.LocalTargetService.ConnectionDetails;
import com.dbpulse.service.LocalTargetService.LocalTargetSummary;
import com.dbpulse.service.LocalTargetService.RegistrationResult;
import com.dbpulse.service.LocalTargetService.StartResult;
import com.dbpulse.service.LocalTargetService.TestResult;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Loopback-only onboarding prototype. Never enable this on a public/LAN bind.
 * No CORS approval for cross-site callers; header forces browser preflight.
 */
@RestController
@RequestMapping("/api/v1/local-targets")
public class LocalTargetController {
    private static final Set<String> LOCAL_ORIGINS = Set.of(
        "http://127.0.0.1:5174", "http://localhost:5174",
        "http://127.0.0.1:18081", "http://localhost:18081");
    private final LocalTargetService targets;

    public LocalTargetController(LocalTargetService targets) { this.targets = targets; }

    @GetMapping
    public List<LocalTargetSummary> list() {
        return targets.list();
    }

    @PostMapping(path="/test-connection", consumes=MediaType.APPLICATION_JSON_VALUE)
    public TestResult test(@RequestBody ConnectionDetails request,
        @RequestHeader(value="X-DBPulse-Local-Request", required=false) String guard,
        @RequestHeader(value="Origin", required=false) String origin) {
        requireLocalPost(guard, origin);
        return targets.test(request);
    }

    @PostMapping(path="/start-monitoring", consumes=MediaType.APPLICATION_JSON_VALUE)
    public StartResult startMonitoring(@RequestBody ConnectionDetails request,
        @RequestHeader(value="X-DBPulse-Local-Request", required=false) String guard,
        @RequestHeader(value="Origin", required=false) String origin) {
        requireLocalPost(guard, origin);
        return targets.startMonitoring(request);
    }

    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    public RegistrationResult register(@RequestBody ConnectionDetails request,
        @RequestHeader(value="X-DBPulse-Local-Request", required=false) String guard,
        @RequestHeader(value="Origin", required=false) String origin) {
        requireLocalPost(guard, origin);
        return targets.register(request);
    }

    private void requireLocalPost(String guard, String origin) {
        targets.requireEnabled();
        if (!"1".equals(guard) || (origin != null && !LOCAL_ORIGINS.contains(origin))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
