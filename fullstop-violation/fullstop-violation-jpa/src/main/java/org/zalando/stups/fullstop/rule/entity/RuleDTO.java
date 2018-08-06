package org.zalando.stups.fullstop.rule.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.joda.time.DateTime;

public class RuleDTO {

    @JsonProperty(value = "account_id")
    private String accountId;

    @JsonProperty(value = "region")
    private String region;

    @JsonProperty(value = "application_id")
    private String applicationId;

    @JsonProperty(value = "application_version")
    private String applicationVersion;

    @JsonProperty(value = "image_name")
    private String imageName;

    @JsonProperty(value = "image_owner")
    private String imageOwner;

    @JsonProperty(value = "reason")
    private String reason;

    @JsonProperty(value = "expiry_date")
    private DateTime expiryDate;

    @JsonProperty(value = "violation_type")
    private String violationTypeEntityId;

    @JsonProperty(value = "meta_info_json_path")
    private String metaInfoJsonPath;

    @JsonProperty(value = "version")
    private Long version;

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(final String accountId) {
        this.accountId = accountId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(final String region) {
        this.region = region;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(final String applicationId) {
        this.applicationId = applicationId;
    }

    public String getApplicationVersion() {
        return applicationVersion;
    }

    public void setApplicationVersion(final String applicationVersion) {
        this.applicationVersion = applicationVersion;
    }

    public String getImageName() {
        return imageName;
    }

    public void setImageName(final String imageName) {
        this.imageName = imageName;
    }

    public String getImageOwner() {
        return imageOwner;
    }

    public void setImageOwner(final String imageOwner) {
        this.imageOwner = imageOwner;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(final String reason) {
        this.reason = reason;
    }

    public DateTime getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(final DateTime expiryDate) {
        this.expiryDate = expiryDate;
    }

    public String getViolationTypeEntityId() {
        return violationTypeEntityId;
    }

    public void setViolationTypeEntityId(final String violationTypeEntityId) {
        this.violationTypeEntityId = violationTypeEntityId;
    }

    public String getMetaInfoJsonPath() {
        return metaInfoJsonPath;
    }

    public void setMetaInfoJsonPath(String metaInfoJsonPath) {
        this.metaInfoJsonPath = metaInfoJsonPath;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(final Long version) {
        this.version = version;
    }
}
