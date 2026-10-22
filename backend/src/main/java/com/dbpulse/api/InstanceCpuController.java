package com.dbpulse.api;

import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.service.InstanceCpuService;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Read-only JVM snapshot; the endpoint never triggers a new MSSQL query. */
@RestController
@RequestMapping("/api/v1/targets/{id}/instance-cpu")
public class InstanceCpuController {
    private final MssqlTargetProperties target;
    private final InstanceCpuService service;
    public InstanceCpuController(MssqlTargetProperties target,InstanceCpuService service){
        this.target=target;this.service=service;
    }
    @GetMapping(produces="application/json;charset=UTF-8")
    public ResponseEntity<InstanceCpuService.CpuStatus> current(@PathVariable String id){
        if(!target.getId().equals(id))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.current());
    }
}
