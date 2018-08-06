package org.zalando.stups.fullstop.jobs.elb;

import com.amazonaws.regions.Region;
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient;
import com.amazonaws.services.elasticloadbalancing.model.DescribeLoadBalancersRequest;
import com.amazonaws.services.elasticloadbalancing.model.DescribeLoadBalancersResult;
import com.amazonaws.services.elasticloadbalancing.model.DescribeTagsRequest;
import com.amazonaws.services.elasticloadbalancing.model.Instance;
import com.amazonaws.services.elasticloadbalancing.model.LoadBalancerDescription;
import com.amazonaws.services.elasticloadbalancing.model.Tag;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.impl.client.CloseableHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.concurrent.ListenableFuture;
import org.zalando.stups.fullstop.aws.ClientProvider;
import org.zalando.stups.fullstop.jobs.FullstopJob;
import org.zalando.stups.fullstop.jobs.common.AccountIdSupplier;
import org.zalando.stups.fullstop.jobs.common.AmiDetailsProvider;
import org.zalando.stups.fullstop.jobs.common.AwsApplications;
import org.zalando.stups.fullstop.jobs.common.EC2InstanceProvider;
import org.zalando.stups.fullstop.jobs.common.FetchTaupageYaml;
import org.zalando.stups.fullstop.jobs.common.HttpCallResult;
import org.zalando.stups.fullstop.jobs.common.HttpGetRootCall;
import org.zalando.stups.fullstop.jobs.common.PortsChecker;
import org.zalando.stups.fullstop.jobs.common.SecurityGroupCheckDetails;
import org.zalando.stups.fullstop.jobs.common.SecurityGroupsChecker;
import org.zalando.stups.fullstop.jobs.config.JobsProperties;
import org.zalando.stups.fullstop.jobs.exception.JobExceptionHandler;
import org.zalando.stups.fullstop.taupage.TaupageYaml;
import org.zalando.stups.fullstop.violation.Violation;
import org.zalando.stups.fullstop.violation.ViolationBuilder;
import org.zalando.stups.fullstop.violation.ViolationSink;
import org.zalando.stups.fullstop.violation.service.ViolationService;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadPoolExecutor;

import static com.amazonaws.regions.Region.getRegion;
import static com.amazonaws.regions.Regions.fromName;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Lists.partition;
import static com.google.common.collect.Maps.newHashMap;
import static com.google.common.collect.Maps.newHashMapWithExpectedSize;
import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.StringUtils.startsWith;
import static org.apache.commons.lang3.StringUtils.trimToNull;
import static org.springframework.util.CollectionUtils.isEmpty;
import static org.zalando.stups.fullstop.violation.ViolationType.UNSECURED_PUBLIC_ENDPOINT;

/**
 * Created by gkneitschel.
 */
@Component
public class FetchElasticLoadBalancersJob implements FullstopJob {

    private static final String EVENT_ID = "checkElbJob";

    private static final int ELB_NAMES_MAX_SIZE = 20;

    private final Logger log = LoggerFactory.getLogger(FetchElasticLoadBalancersJob.class);

    private final ViolationSink violationSink;

    private final ClientProvider clientProvider;

    private final AccountIdSupplier allAccountIds;

    private final JobsProperties jobsProperties;

    private final SecurityGroupsChecker securityGroupsChecker;

    private final PortsChecker portsChecker;

    private final ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();

    private final CloseableHttpClient httpclient;

    private final AwsApplications awsApplications;

    private final ViolationService violationService;

    private final FetchTaupageYaml fetchTaupageYaml;

    private final AmiDetailsProvider amiDetailsProvider;

    private final EC2InstanceProvider ec2Instance;

    private final JobExceptionHandler jobExceptionHandler;

    @Autowired
    public FetchElasticLoadBalancersJob(final ViolationSink violationSink,
                                        final ClientProvider clientProvider,
                                        final AccountIdSupplier allAccountIds, final JobsProperties jobsProperties,
                                        @Qualifier("elbSecurityGroupsChecker") final SecurityGroupsChecker securityGroupsChecker,
                                        final PortsChecker portsChecker,
                                        final AwsApplications awsApplications,
                                        final ViolationService violationService,
                                        final FetchTaupageYaml fetchTaupageYaml,
                                        final AmiDetailsProvider amiDetailsProvider,
                                        final EC2InstanceProvider ec2Instance,
                                        final CloseableHttpClient httpClient,
                                        final JobExceptionHandler jobExceptionHandler) {
        this.violationSink = violationSink;
        this.clientProvider = clientProvider;
        this.allAccountIds = allAccountIds;
        this.jobsProperties = jobsProperties;
        this.securityGroupsChecker = securityGroupsChecker;
        this.portsChecker = portsChecker;
        this.awsApplications = awsApplications;
        this.violationService = violationService;
        this.fetchTaupageYaml = fetchTaupageYaml;
        this.amiDetailsProvider = amiDetailsProvider;
        this.ec2Instance = ec2Instance;
        this.httpclient = httpClient;
        this.jobExceptionHandler = jobExceptionHandler;

        threadPoolTaskExecutor.setCorePoolSize(12);
        threadPoolTaskExecutor.setMaxPoolSize(20);
        threadPoolTaskExecutor.setQueueCapacity(75);
        threadPoolTaskExecutor.setAllowCoreThreadTimeOut(true);
        threadPoolTaskExecutor.setKeepAliveSeconds(30);
        threadPoolTaskExecutor.setThreadGroupName("elb-check-group");
        threadPoolTaskExecutor.setThreadNamePrefix("elb-check-");
        threadPoolTaskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        threadPoolTaskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        threadPoolTaskExecutor.afterPropertiesSet();
    }

    @PostConstruct
    public void init() {
        log.info("{} initialized", getClass().getSimpleName());
    }

    @Scheduled(fixedRate = 300_000, initialDelay = 120_000) // 5 min rate, 2 min delay
    public void run() {
        log.info("Running job {}", getClass().getSimpleName());
        for (final String account : allAccountIds.get()) {
            for (final String region : jobsProperties.getWhitelistedRegions()) {
                log.debug("Scanning ELBs for {}/{}", account, region);
                final Map<String, String> accountRegionCtx = ImmutableMap.of(
                        "job", this.getClass().getSimpleName(),
                        "aws_account_id", account,
                        "aws_region", region);
                try {
                    final Region awsRegion = getRegion(fromName(region));
                    final AmazonElasticLoadBalancingClient elbClient = clientProvider.getClient(
                            AmazonElasticLoadBalancingClient.class,
                            account,
                            getRegion(fromName(region)));

                    Optional<String> marker = Optional.empty();

                    do {
                        final DescribeLoadBalancersRequest request = new DescribeLoadBalancersRequest();
                        marker.ifPresent(request::setMarker);
                        final DescribeLoadBalancersResult result = elbClient.describeLoadBalancers(request);
                        marker = Optional.ofNullable(trimToNull(result.getNextMarker()));

                        final List<String> publicElbNames = result.getLoadBalancerDescriptions().stream()
                                .filter(this::isInternetFacing) // optimization: fetch only tags for public elbs
                                .map(LoadBalancerDescription::getLoadBalancerName)
                                .collect(toList());
                        final Map<String, List<Tag>> tagsByPublicElb = getElbTags(elbClient, publicElbNames);
                        for (final LoadBalancerDescription elb : result.getLoadBalancerDescriptions()) {
                            if (!isInternetFacing(elb)) {
                                continue;
                            }

                            // This check only works for "Senza" Load Balancers, for Kubernetes it's currently
                            // meaningless. Hence just skip Kubernetes ELBs.
                            if (hasKubernetesTag(tagsByPublicElb.getOrDefault(elb.getLoadBalancerName(), emptyList()))) {
                                continue;
                            }

                            try {
                                processELB(account, awsRegion, elb);
                            } catch (Exception e) {
                                final Map<String, String> elbCtx = ImmutableMap.<String, String>builder()
                                        .putAll(accountRegionCtx)
                                        .put("load_balancer_name", elb.getLoadBalancerName())
                                        .build();
                                jobExceptionHandler.onException(e, elbCtx);
                            }
                        }

                    } while (marker.isPresent());

                } catch (final Exception e) {
                    jobExceptionHandler.onException(e, accountRegionCtx);
                }
            }
        }
    }

    private Map<String, List<Tag>> getElbTags(AmazonElasticLoadBalancingClient elbClient, List<String> elbNames) {
        if (isEmpty(elbNames)) {
            return emptyMap();
        } else {
            final Map<String, List<Tag>> result = newHashMapWithExpectedSize(elbNames.size());
            // http://docs.aws.amazon.com/elasticloadbalancing/2012-06-01/APIReference/API_DescribeTags.html
            // describeTags expects a maximum of 20 load balancer names per call
            for (List<String> elbNamePartition : partition(elbNames, ELB_NAMES_MAX_SIZE)) {
                elbClient.describeTags(new DescribeTagsRequest().withLoadBalancerNames(elbNamePartition))
                        .getTagDescriptions()
                        .forEach(tagDescription -> result.put(tagDescription.getLoadBalancerName(), tagDescription.getTags()));
            }
            return result;
        }
    }

    private boolean hasKubernetesTag(List<Tag> elbTags) {
        for (final Tag tag : elbTags) {
            if (StringUtils.equals(tag.getValue(), "owned")
                    && startsWith(tag.getKey(), "kubernetes.io/cluster/")) {
                return true;
            }
        }
        return false;
    }

    private void processELB(String account, Region awsRegion, LoadBalancerDescription elb) {
        final Map<String, Object> metaData = newHashMap();
        final List<String> errorMessages = newArrayList();
        final String canonicalHostedZoneName = elb.getCanonicalHostedZoneName();

        final List<String> instanceIds = elb.getInstances().stream().map(Instance::getInstanceId).collect(toList());

        instanceIds.stream()
                .map(id -> ec2Instance.getById(account, awsRegion, id))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(com.amazonaws.services.ec2.model.Instance::getImageId)
                .map(amiId -> amiDetailsProvider.getAmiDetails(account, awsRegion, amiId))
                .findFirst()
                .ifPresent(metaData::putAll);

        if (violationService.violationExists(account, awsRegion.getName(), EVENT_ID, canonicalHostedZoneName, UNSECURED_PUBLIC_ENDPOINT)) {
            return;
        }

        final List<Integer> unsecuredPorts = portsChecker.check(elb);
        if (!unsecuredPorts.isEmpty()) {
            metaData.put("unsecuredPorts", unsecuredPorts);
            errorMessages.add(format("ELB %s listens on insecure ports! Only ports 80 and 443 are allowed",
                    elb.getLoadBalancerName()));
        }


        final Map<String, SecurityGroupCheckDetails> unsecureGroups = securityGroupsChecker.check(
                elb.getSecurityGroups(),
                account,
                awsRegion);
        if (!unsecureGroups.isEmpty()) {
            metaData.put("unsecuredSecurityGroups", unsecureGroups);
            errorMessages.add("Unsecured security group! Only ports 80 and 443 are allowed");
        }


        if (errorMessages.size() > 0) {
            metaData.put("errorMessages", errorMessages);
            writeViolation(account, awsRegion.getName(), metaData, canonicalHostedZoneName, instanceIds);

            // skip http response check, as we are already having a violation here
            return;
        }


        // skip check for publicly available apps
        if (awsApplications.isPubliclyAccessible(account, awsRegion.getName(), instanceIds).orElse(false)) {
            return;
        }

        for (final Integer allowedPort : jobsProperties.getElbAllowedPorts()) {
            final HttpGetRootCall HttpGetRootCall = new HttpGetRootCall(httpclient, canonicalHostedZoneName, allowedPort);
            final ListenableFuture<HttpCallResult> listenableFuture = threadPoolTaskExecutor.submitListenable(HttpGetRootCall);
            listenableFuture.addCallback(
                    httpCallResult -> {
                        log.debug("address: {} and port: {}", canonicalHostedZoneName, allowedPort);
                        if (httpCallResult.isOpen()) {
                            final Map<String, Object> md = ImmutableMap.<String, Object>builder()
                                    .putAll(metaData)
                                    .put("canonicalHostedZoneName", canonicalHostedZoneName)
                                    .put("port", allowedPort)
                                    .put("Error", httpCallResult.getMessage())
                                    .build();
                            writeViolation(account, awsRegion.getName(), md, canonicalHostedZoneName, instanceIds);
                        }
                    }, ex -> log.warn(ex.getMessage(), ex));

            log.debug("Active threads in pool: {}/{}", threadPoolTaskExecutor.getActiveCount(), threadPoolTaskExecutor.getMaxPoolSize());
        }
    }

    private boolean isInternetFacing(LoadBalancerDescription elb) {
        return elb.getScheme().equals("internet-facing");
    }

    private void writeViolation(final String account, final String region, final Object metaInfo, final String canonicalHostedZoneName, final List<String> instanceIds) {

        final Optional<TaupageYaml> taupageYaml = instanceIds.
                stream().
                map(id -> fetchTaupageYaml.getTaupageYaml(id, account, region)).
                filter(Optional::isPresent).
                map(Optional::get).
                findFirst();


        final ViolationBuilder violationBuilder = new ViolationBuilder();
        final Violation violation = violationBuilder.withAccountId(account)
                .withRegion(region)
                .withPluginFullyQualifiedClassName(FetchElasticLoadBalancersJob.class)
                .withType(UNSECURED_PUBLIC_ENDPOINT)
                .withMetaInfo(metaInfo)
                .withEventId(EVENT_ID)
                .withInstanceId(canonicalHostedZoneName)
                .withApplicationId(taupageYaml.map(TaupageYaml::getApplicationId).map(StringUtils::trimToNull).orElse(null))
                .withApplicationVersion(taupageYaml.map(TaupageYaml::getApplicationVersion).map(StringUtils::trimToNull).orElse(null))
                .build();
        violationSink.put(violation);
    }
}
