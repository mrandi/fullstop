package org.zalando.stups.fullstop.plugin.impl;

import com.amazonaws.AmazonWebServiceClient;
import com.amazonaws.regions.Region;
import com.amazonaws.services.cloudtrail.processinglibrary.model.CloudTrailEvent;
import com.amazonaws.services.ec2.model.Image;
import com.jayway.jsonpath.JsonPath;
import org.apache.commons.lang3.StringUtils;
import org.zalando.stups.clients.kio.Application;
import org.zalando.stups.fullstop.aws.ClientProvider;
import org.zalando.stups.fullstop.events.CloudTrailEventSupport;
import org.zalando.stups.fullstop.plugin.EC2InstanceContext;
import org.zalando.stups.fullstop.plugin.provider.AmiIdProvider;
import org.zalando.stups.fullstop.plugin.provider.AmiProvider;
import org.zalando.stups.fullstop.plugin.provider.KioApplicationProvider;
import org.zalando.stups.fullstop.plugin.provider.PieroneTagProvider;
import org.zalando.stups.fullstop.plugin.provider.ScmSourceProvider;
import org.zalando.stups.fullstop.plugin.provider.TaupageYamlProvider;
import org.zalando.stups.fullstop.taupage.TaupageYaml;
import org.zalando.stups.fullstop.violation.ViolationBuilder;
import org.zalando.stups.pierone.client.TagSummary;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.google.common.base.MoreObjects.toStringHelper;
import static org.zalando.stups.fullstop.events.CloudTrailEventSupport.getUsernameAsString;

class EC2InstanceContextImpl implements EC2InstanceContext {

    private static final String INSTANCE_ID_JSON_PATH_EXPRESSION = "$.instanceId";

    private final String taupageNamePrefix;

    private final List<String> taupageOwners;

    /**
     * The original CloudTrailEvent
     */
    private final CloudTrailEvent event;

    /**
     * An excerpt of the CloudTrailEvent for this particular instance.
     * In other words: one "item" in the responseElements $.instancesSet.items.
     */
    private final String instanceJson;

    private final ClientProvider clientProvider;

    private final AmiIdProvider amiIdProvider;

    private final AmiProvider amiProvider;

    private final TaupageYamlProvider taupageYamlProvider;

    private final KioApplicationProvider kioApplicationProvider;

    private final PieroneTagProvider pieroneTagProvider;

    private final ScmSourceProvider scmSourceProvider;

    EC2InstanceContextImpl(
            final CloudTrailEvent event,
            final String instanceJson,
            final ClientProvider clientProvider,
            final AmiIdProvider amiIdProvider,
            final AmiProvider amiProvider,
            final TaupageYamlProvider taupageYamlProvider,
            final String taupageNamePrefix,
            final List<String> taupageOwners,
            final KioApplicationProvider kioApplicationProvider,
            final PieroneTagProvider pieroneTagProvider,
            final ScmSourceProvider scmSourceProvider) {
        this.event = event;
        this.instanceJson = instanceJson;
        this.clientProvider = clientProvider;
        this.amiIdProvider = amiIdProvider;
        this.amiProvider = amiProvider;
        this.taupageYamlProvider = taupageYamlProvider;
        this.taupageNamePrefix = taupageNamePrefix;
        this.taupageOwners = taupageOwners;
        this.kioApplicationProvider = kioApplicationProvider;
        this.pieroneTagProvider = pieroneTagProvider;
        this.scmSourceProvider = scmSourceProvider;
    }

    @Override
    public CloudTrailEvent getEvent() {
        return event;
    }

    @Override
    public String getInstanceJson() {
        return instanceJson;
    }

    @Override
    public String getInstanceId() {
        return JsonPath.read(getInstanceJson(), INSTANCE_ID_JSON_PATH_EXPRESSION);
    }

    @Override
    public <T extends AmazonWebServiceClient> T getClient(final Class<T> type) {
        return clientProvider.getClient(type, getAccountId(), getRegion());
    }

    @Override
    public ViolationBuilder violation() {
        return new ViolationBuilder()
                .withAccountId(getAccountId())
                .withRegion(getRegion().getName())
                .withEventId(getEventId().toString())
                .withInstanceId(getInstanceId())
                .withUsername(getUsernameAsString(getEvent()))
                .withApplicationId(getApplicationId().orElse(null))
                .withApplicationVersion(getVersionId().orElse(null));

    }

    @Override
    public String getRegionAsString() {
        return CloudTrailEventSupport.getRegionAsString(getEvent());
    }

    @Override
    public Region getRegion() {
        return CloudTrailEventSupport.getRegion(getEvent());
    }

    @Override
    public Optional<String> getApplicationId() {
        return getTaupageYaml().map(TaupageYaml::getApplicationId).map(StringUtils::trimToNull);
    }

    @Override
    public Optional<String> getVersionId() {
        return getTaupageYaml().map(TaupageYaml::getApplicationVersion).map(StringUtils::trimToNull);
    }

    @Override
    public Optional<String> getSource() {
        return getTaupageYaml().map(TaupageYaml::getSource).map(StringUtils::trimToNull);
    }

    @Override
    public Optional<String> getRuntime() {
        return getTaupageYaml().map(TaupageYaml::getRuntime).map(StringUtils::trimToNull);
    }

    @Override
    public Optional<Application> getKioApplication() {
        return kioApplicationProvider.apply(this);
    }

    @Override
    public Optional<String> getAmiId() {
        return amiIdProvider.apply(this);
    }

    @Override
    public Optional<Image> getAmi() {
        return amiProvider.apply(this);
    }

    @Override
    public Optional<Boolean> isTaupageAmi() {
        return getAmi()
                .filter(image -> image.getName().startsWith(taupageNamePrefix))
                .map(Image::getOwnerId)
                .map(taupageOwners::contains);
    }

    @Override
    public Optional<TaupageYaml> getTaupageYaml() {
        return taupageYamlProvider.apply(this);
    }

    @Override
    public Optional<TagSummary> getPieroneTag() {
        return pieroneTagProvider.apply(this);
    }

    @Override
    public Optional<Map<String, String>> getScmSource() {
        return scmSourceProvider.apply(this);
    }

    @Override
    public String getEventName() {
        return getEvent().getEventData().getEventName();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final EC2InstanceContextImpl that = (EC2InstanceContextImpl) o;
        return Objects.equals(getEvent(), that.getEvent()) &&
                Objects.equals(getInstanceJson(), that.getInstanceJson());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getEvent(), getInstanceJson());
    }

    @Override
    public String toString() {
        // make sure to never add "expensive" information here.
        return toStringHelper(this)
                .add("accountId", getAccountId())
                .add("region", getRegion())
                .add("eventId", getEventId())
                .add("eventName", getEventName())
                .add("instanceId", getInstanceId())
                .toString();
    }

    @Override
    public String getAccountId() {
        return getEvent().getEventData().getAccountId();
    }

    private UUID getEventId() {
        return getEvent().getEventData().getEventId();
    }
}
