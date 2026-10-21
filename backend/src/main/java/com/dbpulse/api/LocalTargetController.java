package com.dbpulse.api;

import com.dbpulse.service.LocalTargetService;
import com.dbpulse.service.LocalTargetService.ConnectionDetails;
import com.dbpulse.service.LocalTargetService.LocalTargetSummary;
import com.dbpulse.service.LocalTargetService.RegistrationResult;
import com.dbpulse.service.LocalTargetService.StartResult;
import com.dbpulse.service.LocalTargetService.StopRequest;
import com.dbpulse.service.LocalTargetService.StopResult;
import com.dbpulse.service.LocalTargetService.TestResult;
import com.dbpulse.service.LocalTargetService.RenameRequest;
import com.dbpulse.service.LocalTargetService.RegistryResult;
import com.dbpulse.service.LocalTargetService.CredentialResult;
import com.dbpulse.service.LocalTargetService.CredentialCapability;
import com.dbpulse.service.LocalTargetService.SavedStartRequest;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
        "http://127.0.0.1:18081", "http://localhost:18081",
        "http://127.0.0.1:18082", "http://localhost:18082");
    private final LocalTargetService targets;

    public LocalTargetController(LocalTargetService targets) { this.targets = targets; }

    @GetMapping
    public List<LocalTargetSummary> list() {
        return targets.list();
    }

    @GetMapping("/credential-capability")
    public CredentialCapability credentialCapability() {
        return targets.credentialCapability();
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

    @PostMapping(path="/stop-monitoring", consumes=MediaType.APPLICATION_JSON_VALUE)
    public StopResult stopMonitoring(@RequestBody StopRequest request,
        @RequestHeader(value="X-DBPulse-Local-Request", required=false) String guard,
        @RequestHeader(value="Origin", required=false) String origin) {
        requireLocalPost(guard, origin);
        return targets.stopMonitoring(request.id());
    }

    @PostMapping(path="/{id}/rename", consumes=MediaType.APPLICATION_JSON_VALUE)
    public RegistryResult rename(@PathVariable String id, @RequestBody RenameRequest request,
        @RequestHeader(value="X-DBPulse-Local-Request", required=false) String guard,
        @RequestHeader(value="Origin", required=false) String origin) {
        requireLocalPost(guard, origin);
        return targets.renameRegistered(id, request.name());
    }

    @PostMapping(path="/{id}/unregister", consumes=MediaType.APPLICATION_JSON_VALUE)
    public RegistryResult unregister(@PathVariable String id,
        @RequestHeader(value="X-DBPulse-Local-Request", required=false) String guard,
        @RequestHeader(value="Origin", required=false) String origin) {
        requireLocalPost(guard, origin);
        return targets.unregister(id);
    }

    @PostMapping(path="/{id}/remember-credential", consumes=MediaType.APPLICATION_JSON_VALUE)
    public CredentialResult remember(@PathVariable String id,
        @RequestHeader(value="X-DBPulse-Local-Request", required=false) String guard,
        @RequestHeader(value="Origin", required=false) String origin) {
        requireLocalPost(guard, origin);
        return targets.rememberActiveCredential(id);
    }

    @PostMapping(path="/{id}/forget-credential", consumes=MediaType.APPLICATION_JSON_VALUE)
    public CredentialResult forget(@PathVariable String id,
        @RequestHeader(value="X-DBPulse-Local-Request", required=false) String guard,
        @RequestHeader(value="Origin", required=false) String origin) {
        requireLocalPost(guard, origin);
        return targets.forgetCredential(id);
    }

    @PostMapping(path="/{id}/start-saved", consumes=MediaType.APPLICATION_JSON_VALUE)
    public StartResult startSaved(@PathVariable String id, @RequestBody SavedStartRequest request,
        @RequestHeader(value="X-DBPulse-Local-Request", required=false) String guard,
        @RequestHeader(value="Origin", required=false) String origin) {
        requireLocalPost(guard, origin);
        return targets.startWithSavedCredential(id, request.trustLocalCertificate());
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
