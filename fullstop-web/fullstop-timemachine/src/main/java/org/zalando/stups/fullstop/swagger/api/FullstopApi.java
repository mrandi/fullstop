package org.zalando.stups.fullstop.swagger.api;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.zalando.stups.fullstop.s3.LogType;
import org.zalando.stups.fullstop.s3.S3Service;
import org.zalando.stups.fullstop.swagger.model.LogObj;
import org.zalando.stups.fullstop.violation.entity.LifecycleEntity;
import org.zalando.stups.fullstop.violation.service.ApplicationLifecycleService;

import static org.joda.time.DateTimeZone.UTC;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@RestController
@RequestMapping(value = "/api", produces = {APPLICATION_JSON_VALUE})
@Api(value = "/api", description = "the api API")
public class FullstopApi {

    private final Logger log = LoggerFactory.getLogger(FullstopApi.class);

    @Autowired
    private S3Service s3Writer;

    @Autowired
    private ApplicationLifecycleService applicationLifecycleService;

    @ApiOperation(value = "Put instance log in S3", notes = "Add log for instance in S3")
    @ApiResponses(value = {@ApiResponse(code = 201, message = "Logs saved successfully")})
    @RequestMapping(value = "/instance-logs", method = RequestMethod.POST)
    public ResponseEntity<Void> instanceLogs(@ApiParam(value = "", required = true) @RequestBody final LogObj log) {
        saveLog(log);

        return new ResponseEntity<>(CREATED);
    }

    private void saveLog(final LogObj instanceLog) {
        if (instanceLog.getLogType() == null) {
            log.error("You should use one of the allowed types.");
            throw new IllegalArgumentException("You should use one of the allowed types.");
        }

        final String userdataPath = s3Writer.writeToS3(
                instanceLog.getAccountId(), instanceLog.getRegion(), instanceLog.getInstanceBootTime(),
                instanceLog.getLogData(), instanceLog.getLogType().toString(), instanceLog.getInstanceId());
        log.debug("Saved S3 logs with userdatapath: {}", userdataPath);

        if (instanceLog.getLogType() == LogType.USER_DATA) {
            final LifecycleEntity lifecycleEntity = applicationLifecycleService.saveInstanceLogLifecycle(
                    instanceLog.getInstanceId(),
                    new DateTime(instanceLog.getInstanceBootTime(), UTC),
                    userdataPath,
                    instanceLog.getRegion(),
                    instanceLog.getLogData(),
                    instanceLog.getAccountId());
            log.info("Saving Lifecycle Entity: {}", lifecycleEntity);

        }
    }
}
